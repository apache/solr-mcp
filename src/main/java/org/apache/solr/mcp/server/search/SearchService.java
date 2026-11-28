/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.search;

import io.micrometer.observation.annotation.Observed;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.DisMaxParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.util.JsonTextWriter;
import org.apache.solr.mcp.server.util.McpResults;
import org.apache.solr.mcp.server.util.PromptNames;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Spring Service providing comprehensive search capabilities for Apache Solr collections through
 * Model Context Protocol (MCP) integration.
 *
 * <p>This service serves as the primary interface for executing search operations against Solr
 * collections, offering a rich set of features including text search, filtering, faceting, sorting,
 * and pagination. It transforms complex Solr query syntax into accessible MCP tools that AI clients
 * can invoke through natural language requests.
 *
 * <p><strong>Core Features:</strong>
 *
 * <ul>
 *   <li><strong>Full-Text Search</strong>: Advanced text search with relevance scoring
 *   <li><strong>Filtering</strong>: Multi-criteria filtering using Solr filter queries
 *   <li><strong>Faceting</strong>: Dynamic facet generation for result categorization
 *   <li><strong>Sorting</strong>: Flexible result ordering by multiple fields
 *   <li><strong>Pagination</strong>: Efficient handling of large result sets
 * </ul>
 *
 * <p><strong>Dynamic Field Support:</strong>
 *
 * <p>The service handles Solr's dynamic field naming conventions where field names include type
 * suffixes that indicate data types and indexing behavior:
 *
 * <ul>
 *   <li><strong>_s</strong>: String fields for exact matching
 *   <li><strong>_t</strong>: Text fields with tokenization and analysis
 *   <li><strong>_i, _l, _f, _d</strong>: Numeric fields (int, long, float, double)
 *   <li><strong>_dt</strong>: Date/time fields
 *   <li><strong>_b</strong>: Boolean fields
 * </ul>
 *
 * <p><strong>MCP Tool Integration:</strong>
 *
 * <p>Search operations are exposed as MCP tools that AI clients can invoke through natural language
 * requests such as "search for books by George R.R. Martin" or "find products under $50 in the
 * electronics category".
 *
 * <p><strong>Response Format:</strong>
 *
 * <p>Returns a {@link SearchResponse}: the totals, one {@link SearchResponse.SearchHit} per
 * document with its uniqueKey value as {@code id}, and a list of facet counts per field.
 *
 * @see SearchResponse
 * @see SolrClient
 * @see McpTool
 */
@Service
@Observed
public class SearchService {

  private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

  /**
   * Fragments of Solr's own error text that identify a failure we can advise on.
   *
   * <p>These are matched rather than imported because they are produced by <em>solr-core</em>,
   * which is not on this server's classpath — only {@code solr-solrj} is. Nothing couples them to
   * Solr at compile time, so {@code SearchServiceIntegrationTest} pins each one against a real Solr
   * server: if a future Solr rewords a message, that test fails rather than the hint silently
   * disappearing. Prefer a structured signal (see {@link SolrException#code()} below) whenever one
   * exists.
   */
  static final String UNDEFINED_FIELD_TOKEN = "undefined field";

  /**
   * @see #UNDEFINED_FIELD_TOKEN
   */
  static final String SORT_FIELD_NOT_FOUND_TOKEN = "field can't be found";

  /**
   * An undefined field in {@code qf}. eDisMax reports it as a {@code SyntaxError}, so it is checked
   * before {@link #SYNTAX_ERROR_TOKEN}.
   *
   * @see #UNDEFINED_FIELD_TOKEN
   */
  static final String INVALID_QUERY_FIELD_TOKEN = "is not a valid field name";

  /**
   * @see #UNDEFINED_FIELD_TOKEN
   */
  static final String SYNTAX_ERROR_TOKEN = "syntaxerror";

  /**
   * @see #UNDEFINED_FIELD_TOKEN
   */
  static final String CANNOT_PARSE_TOKEN = "cannot parse";

  /** Remediation hint naming the {@code list-fields} tool; takes the collection. */
  static final String LIST_FIELDS_HINT_FORMAT =
      ". Hint: call list-fields on collection '%s' to see the field names that"
          + " hold data, dynamic-field instances such as name_s or price_d included.";

  /** Remediation hint for an unparseable {@code q}. */
  static final String LUCENE_SYNTAX_HINT =
      ". Hint: the q parameter uses Lucene query syntax; quote or escape"
          + " special characters and check any {!...} local params.";

  /** Rejection for {@code queryFields} combined with a local-params {@code query}. */
  static final String QUERY_FIELDS_WITH_LOCAL_PARAMS_ERROR =
      "Search failed: queryFields cannot be combined with {!...} local params in query:"
          + " put qf inside the local params, or drop the prefix.";

  /**
   * Solr's {@code defType} request parameter. SolrJ has no constant for it; it lives in solr-core's
   * {@code QueryParsing}, which is not on the classpath.
   */
  private static final String DEF_TYPE = "defType";

  private static final String EDISMAX = "edismax";

  /**
   * Opens a local-params block. Under {@code defType=edismax} Solr searches such a prefix as
   * literal text instead of parsing it.
   */
  private static final String LOCAL_PARAMS_PREFIX = "{!";

  /** Leads every error text the search tool reports for a failed Solr call. */
  static final String SEARCH_FAILED = "Search failed";

  /** Follows Solr's reason for a 400 that names no field and no syntax error. */
  static final String BAD_REQUEST_HINT =
      ". Check query syntax, query fields, filter queries, facet fields, sort clauses, and"
          + " pagination; call list-fields to verify the field names, and facet and sort only on"
          + " single-valued fields with D (docValues) in their schema flags.";

  private static final String RESPONSE_ERROR =
      "Search failed: unable to process the Solr response."
          + " If facetFields were supplied, call list-fields to check those fields or retry without facetFields;"
          + " if it persists, ask the operator to check server logs and Solr compatibility.";

  /**
   * Failure for a collection whose schema declares no uniqueKey, which only standalone Solr allows.
   */
  static final String NO_UNIQUE_KEY_ERROR =
      "Search failed: the collection's schema has no unique key, so its documents cannot be"
          + " identified.";

  /**
   * Failure for a hit without the remembered uniqueKey: the collection was deleted and created
   * again with another schema since the key was read.
   */
  static final String SCHEMA_CHANGED_ERROR =
      "Search failed: the collection's schema changed since its unique key was read; retry the"
          + " search.";

  /** Rejection for a {@code fields} entry such as {@code id:name_s} when {@code id} is the key. */
  static final String RENAME_TO_UNIQUE_KEY_ERROR =
      "Search failed: fields cannot rename another field to the unique key, which each hit"
          + " already reports as id. Choose another name for the renamed field.";

  /**
   * The field Solr fills with each document's relevance score when {@code fl} asks for it. It is
   * reported as {@link SearchResponse.SearchHit#score()}, not among the hit's fields.
   */
  private static final String SCORE_FIELD = "score";

  /** Separates the new name from the field in an {@code fl} rename such as {@code title:name_s}. */
  private static final char RENAME_SEPARATOR = ':';

  private final SolrClient solrClient;

  private final UniqueKeyCache uniqueKeys;

  /**
   * Constructs a new SearchService with the required SolrClient dependency.
   *
   * <p>This constructor is automatically called by Spring's dependency injection framework during
   * application startup, providing the service with the necessary Solr client for executing search
   * operations.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @see SolrClient
   */
  public SearchService(SolrClient solrClient) {
    this.solrClient = solrClient;
    this.uniqueKeys = new UniqueKeyCache(solrClient);
  }

  /**
   * Converts Solr's facet fields into the response's facet list.
   *
   * @param queryResponse the response holding the facet counts
   * @return one entry per faceted field, with values in Solr's (count-sorted) order; empty when the
   *     search asked for no facets
   */
  private static List<SearchResponse.FacetField> toFacets(QueryResponse queryResponse) {
    final List<FacetField> facetFields = queryResponse.getFacetFields();
    if (facetFields == null) {
      return List.of();
    }
    final List<SearchResponse.FacetField> facets = new ArrayList<>(facetFields.size());
    for (FacetField facetField : facetFields) {
      final List<SearchResponse.FacetValue> values = new ArrayList<>(facetField.getValueCount());
      for (FacetField.Count count : facetField.getValues()) {
        values.add(new SearchResponse.FacetValue(count.getName(), count.getCount()));
      }
      facets.add(new SearchResponse.FacetField(facetField.getName(), values));
    }
    return facets;
  }

  /**
   * Converts one Solr document into a hit: the uniqueKey value becomes {@code id}, {@code score}
   * becomes the hit's score, and every other field stays in {@code fields} in Solr's order.
   *
   * @param document the document as Solr returned it
   * @param uniqueKey the collection's uniqueKey field
   * @return the hit, or {@code null} when the document has no value for {@code uniqueKey}
   */
  private static SearchResponse.@Nullable SearchHit toHit(SolrDocument document, String uniqueKey) {
    final Object id = document.getFieldValue(uniqueKey);
    if (id == null) {
      return null;
    }
    final Float score =
        document.getFieldValue(SCORE_FIELD) instanceof Number number ? number.floatValue() : null;
    final Map<String, Object> fields = new LinkedHashMap<>(document);
    fields.remove(uniqueKey);
    fields.remove(SCORE_FIELD);
    return new SearchResponse.SearchHit(String.valueOf(id), score, fields);
  }

  /**
   * Searches a Solr collection returning all stored fields and using the default query parser.
   * Equivalent to {@link #search(String, String, List, List, List, Integer, Integer, List, List)}
   * with no {@code fields} and no {@code queryFields}. Not an MCP tool; it keeps Java callers on
   * the original signature.
   *
   * @param collection The Solr collection to query
   * @param query The Solr query string (q parameter). Defaults to "*:*" if not specified
   * @param filterQueries List of filter queries (fq parameter); blank entries are ignored
   * @param facetFields List of fields to facet on; blank entries are ignored
   * @param sortClauses List of sort clauses for ordering results; entries with blank fields are
   *     ignored
   * @param start Starting offset for pagination
   * @param rows Number of rows to return
   * @return A SearchResponse containing the search results and facets
   * @throws IllegalArgumentException if Solr refused the search, with Solr's reason and a hint
   * @throws IllegalStateException if Solr failed or could not be reached
   */
  @PreAuthorize("isAuthenticated()")
  public SearchResponse search(
      String collection,
      @Nullable String query,
      @Nullable List<String> filterQueries,
      @Nullable List<String> facetFields,
      @Nullable List<SortClause> sortClauses,
      @Nullable Integer start,
      @Nullable Integer rows) {
    return search(
        collection, query, filterQueries, facetFields, sortClauses, start, rows, null, null);
  }

  /**
   * Searches a Solr collection with the specified parameters. This method is exposed as a tool for
   * MCP clients to use.
   *
   * <p>Each hit reports the collection's uniqueKey value as its {@code id}. The uniqueKey is read
   * with the Schema API ({@code GET /schema/uniquekey}) on the first search of each collection and
   * again every few minutes, so the Solr account needs Solr's {@code schema-read} permission as
   * well as {@code read}. A collection whose schema has no uniqueKey, which only standalone Solr
   * allows, cannot be searched.
   *
   * @param collection The Solr collection to query
   * @param query The Solr query string (q parameter). Defaults to "*:*" if not specified
   * @param filterQueries List of filter queries (fq parameter); blank entries are ignored
   * @param facetFields List of fields to facet on; blank entries are ignored
   * @param sortClauses List of sort clauses for ordering results; each names a field and an
   *     optional {@code asc}/{@code desc} order (default {@code asc}); entries with blank fields
   *     are ignored
   * @param start Starting offset for pagination
   * @param rows Number of rows to return
   * @param fields Fields to return (fl parameter); {@code score} is always added, and so is the
   *     uniqueKey field, which each hit reports as {@code id}. Blank entries are ignored; all
   *     stored fields are returned when none remain
   * @param queryFields Fields to search the query against, with optional boosts (qf parameter).
   *     Blank entries are ignored; any remaining entry switches the request to eDisMax
   * @return A SearchResponse containing the search results and facets
   * @throws IllegalArgumentException if {@code queryFields} is combined with a {@code query} that
   *     starts with {@literal {!...}} local params, or if a {@code fields} entry renames another
   *     field to the uniqueKey (such as {@code id:name_s} when the uniqueKey is {@code id})
   * @throws IllegalArgumentException if Solr refused the search: the collection does not exist, or
   *     Solr rejected the request, whose reason the message carries with a hint
   * @throws IllegalStateException if the collection's schema has no uniqueKey, or changed since the
   *     uniqueKey was read, or Solr failed, denied access, timed out or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize("isAuthenticated()")
  // @formatter:off — keep this @McpTool wrapped like the others; the text block disguises the real
  // line length.
  @McpTool(
      name = "search",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description =
          """
			Search specified Solr collection with query, optional filters, facets, sorting, and pagination.
			The q parameter accepts Lucene query syntax. To search several text fields at once, list them in
			queryFields (e.g. ["name^2", "author"]); this uses eDisMax, where any query term matches and
			results rank by score. For other eDisMax features (mm, pf, bq, boost), embed Solr local params
			in q instead and leave queryFields empty, e.g. {!edismax qf='name author' mm=2}george martin.
			Pass fields to return only the fields you need; by default every stored field comes back.
			Each hit in documents has id (the collection's uniqueKey value), score, and its other fields
			under fields; facets lists each faceted field with its values and counts, highest count first.
			Put filters in fq (filterQueries) instead of q so Solr can cache and reuse them.
			Call list-fields first to get the field names that hold data and their types, rather than
			guessing names: dynamic fields such as genre_s exist only once documents use them. Facet
			only on fields that list-fields reports with docValues, and sort only on single-valued ones.
			""",
      generateOutputSchema = true)
  // @formatter:on
  public SearchResponse search(
      @McpToolParam(description = "Solr collection to query") String collection,
      @McpToolParam(
              description =
                  "Solr q parameter. Lucene syntax, or plain terms when queryFields is set."
                      + " Supports local params such as {!edismax qf='name author'} when"
                      + " queryFields is not set. If none specified defaults to \"*:*\"",
              required = false)
          @Nullable String query,
      @McpToolParam(
              description =
                  "Solr fq parameter: list of filter queries, one filter per entry. Blank entries are ignored",
              required = false)
          @Nullable List<String> filterQueries,
      @McpToolParam(description = "Solr facet fields. Blank entries are ignored", required = false)
          @Nullable List<String> facetFields,
      @McpToolParam(
              description =
                  "Sort clauses applied in order. Each has 'field' (field name to sort"
                      + " on) and 'order' ('asc' or 'desc', default 'asc'). Entries with blank fields are ignored",
              required = false)
          @Nullable List<SortClause> sortClauses,
      @McpToolParam(description = "Starting offset for pagination", required = false)
          @Nullable Integer start,
      @McpToolParam(description = "Number of rows to return", required = false)
          @Nullable Integer rows,
      @McpToolParam(
              description =
                  "Solr fl parameter: fields to return, e.g. [\"name\", \"year\"]. score and the"
                      + " uniqueKey (returned as each hit's id) are always included. If none"
                      + " specified, all stored fields are returned. Blank entries are ignored",
              required = false)
          @Nullable List<String> fields,
      @McpToolParam(
              description =
                  "Solr qf parameter: fields to search the query against, with optional boosts,"
                      + " e.g. [\"name^2\", \"author\"]. Uses the eDisMax parser; cannot be combined"
                      + " with a query that starts with {!...} local params. Blank entries are ignored",
              required = false)
          @Nullable List<String> queryFields) {

    // query
    final SolrQuery solrQuery = new SolrQuery("*:*");
    // Facet counts are a NamedList that Solr writes as a flat ["term", 5, ...] array by
    // default; map style writes it as an object, which JsonResponseParser reads as a NamedList.
    solrQuery.set(JsonTextWriter.JSON_NL_STYLE, JsonTextWriter.JSON_NL_MAP);
    if (StringUtils.hasText(query)) {
      solrQuery.setQuery(query);
    }

    // query fields — switch to eDisMax, which ignores local params in q
    final List<String> nonBlankQueryFields = nonBlank(queryFields);
    if (!nonBlankQueryFields.isEmpty()) {
      if (query != null && query.startsWith(LOCAL_PARAMS_PREFIX)) {
        throw new IllegalArgumentException(QUERY_FIELDS_WITH_LOCAL_PARAMS_ERROR);
      }
      solrQuery.set(DisMaxParams.QF, String.join(" ", nonBlankQueryFields));
      solrQuery.set(DEF_TYPE, EDISMAX);
    }

    // fields — set before score, which setIncludeScore appends to the current fl
    final List<String> nonBlankFields = nonBlank(fields);
    if (!nonBlankFields.isEmpty()) {
      solrQuery.setFields(nonBlankFields.toArray(String[]::new));
    }

    // fl=*,score unless fields narrowed it — Solr only returns maxScore and per-document score
    // when asked
    solrQuery.setIncludeScore(true);

    // filter queries
    if (!CollectionUtils.isEmpty(filterQueries)) {
      filterQueries.stream().filter(StringUtils::hasText).forEach(solrQuery::addFilterQuery);
    }

    // facets
    if (!CollectionUtils.isEmpty(facetFields)) {
      facetFields.stream().filter(StringUtils::hasText).forEach(solrQuery::addFacetField);
    }
    // addFacetField sets facet=true; getFacetFields() is null until the first one
    if (solrQuery.getFacetFields() != null) {
      solrQuery.setFacetMinCount(1);
      solrQuery.setFacetSort(FacetParams.FACET_SORT_COUNT);
    }

    // sorting
    if (!CollectionUtils.isEmpty(sortClauses)) {
      sortClauses.stream()
          .filter(clause -> StringUtils.hasText(clause.field()))
          .map(SortClause::toSolrSortClause)
          .forEach(solrQuery::addSort);
    }

    // pagination
    if (start != null) {
      solrQuery.setStart(start);
    }

    if (rows != null) {
      solrQuery.setRows(rows);
    }

    final String uniqueKey =
        callSolr(collection, () -> uniqueKeys.get(collection))
            .orElseThrow(
                () -> {
                  logger.warn(
                      "Collection {} has no uniqueKey, so it cannot be searched", collection);
                  return new IllegalStateException(NO_UNIQUE_KEY_ERROR);
                });

    // Every hit reports its uniqueKey value as id, so a narrowed fl must still return it, and no
    // other field may be renamed onto it.
    if (!nonBlankFields.isEmpty()) {
      if (nonBlankFields.stream().anyMatch(field -> renamesOnto(field, uniqueKey))) {
        throw new IllegalArgumentException(RENAME_TO_UNIQUE_KEY_ERROR);
      }
      if (!nonBlankFields.contains(uniqueKey)) {
        solrQuery.addField(uniqueKey);
      }
    }

    final QueryResponse queryResponse =
        callSolr(collection, () -> solrClient.query(collection, solrQuery));
    final SolrDocumentList documents = readResponse(collection, queryResponse::getResults);
    final List<SearchResponse.FacetField> facets =
        readResponse(collection, () -> toFacets(queryResponse));

    final List<SearchResponse.SearchHit> hits = new ArrayList<>(documents.size());
    for (SolrDocument document : documents) {
      final SearchResponse.SearchHit hit = toHit(document, uniqueKey);
      if (hit == null) {
        // Only a remembered uniqueKey that the collection no longer has (it was deleted and created
        // again with another schema) leaves a hit without it; look it up afresh next time.
        uniqueKeys.evict(collection);
        logger.warn(
            "A hit on collection {} has no value for its remembered uniqueKey {}; forgot the key",
            collection,
            uniqueKey);
        throw new IllegalStateException(SCHEMA_CHANGED_ERROR);
      }
      hits.add(hit);
    }

    logger.info(
        "Search on collection {} matched {} documents", collection, documents.getNumFound());

    return new SearchResponse(
        documents.getNumFound(), documents.getStart(), documents.getMaxScore(), hits, facets);
  }

  /**
   * Whether an {@code fl} entry renames some other field to {@code uniqueKey}, as {@code id:name_s}
   * does when the uniqueKey is {@code id}. Renaming the uniqueKey itself, as {@code key:id} does,
   * is fine.
   *
   * @param field one {@code fields} entry
   * @param uniqueKey the collection's uniqueKey field
   * @return true if the entry would put another value where the hit's {@code id} comes from
   */
  private static boolean renamesOnto(String field, String uniqueKey) {
    final int separator = field.indexOf(RENAME_SEPARATOR);
    return separator >= 0
        && field.substring(0, separator).strip().equals(uniqueKey)
        && !field.substring(separator + 1).strip().equals(uniqueKey);
  }

  /**
   * Runs one call to Solr through {@link SolrCalls}, which reports Solr's reason for a refused
   * search followed by {@link #remediationHint}, and keeps host names and URLs out of the error.
   * SolrJ also builds the typed {@code QueryResponse} inside the call, so a failure to convert
   * Solr's answer becomes {@link #RESPONSE_ERROR} as in {@link #readResponse}.
   *
   * @param collection the collection being searched, for the hints
   * @param call the call to run
   * @param <T> what the call returns
   * @return the call's result
   */
  private static <T> T callSolr(String collection, SolrCalls.SolrCall<T> call) {
    return SolrCalls.call(
        SEARCH_FAILED,
        SolrCalls.collectionNotFound(collection),
        (statusCode, reason) -> remediationHint(collection, statusCode, reason),
        () -> {
          try {
            return call.call();
          } catch (SolrException solrFailure) {
            // Solr's own failure: SolrCalls translates it.
            throw solrFailure;
          } catch (RuntimeException conversionFailure) {
            throw responseError(collection, conversionFailure);
          }
        });
  }

  /**
   * Reads part of a response Solr returned, turning a failure to convert it into {@link
   * #RESPONSE_ERROR}.
   *
   * @param collection the collection being searched, for the log
   * @param reader reads the part
   * @param <T> what the part is
   * @return the part
   */
  private static <T> T readResponse(String collection, Supplier<T> reader) {
    try {
      return reader.get();
    } catch (RuntimeException conversionFailure) {
      throw responseError(collection, conversionFailure);
    }
  }

  /**
   * The error that reports a failure to convert Solr's answer. The failure is logged, never
   * returned: its text names this server's classes.
   *
   * @param collection the collection being searched, for the log
   * @param conversionFailure what the conversion threw
   * @return an exception naming a next step, without a cause
   */
  private static IllegalStateException responseError(
      String collection, RuntimeException conversionFailure) {
    // The client only sees RESPONSE_ERROR; the log is the sole record of the cause.
    logger.warn("Solr query response failed on collection {}", collection, conversionFailure);
    return new IllegalStateException(RESPONSE_ERROR);
  }

  /**
   * Drops blank entries from an optional list parameter.
   *
   * @param values the list as received; may be null
   * @return the non-blank entries in order, or an empty list
   */
  private static List<String> nonBlank(@Nullable List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().filter(StringUtils::hasText).toList();
  }

  /**
   * The next step to suggest after Solr's reason for a search it refused. Solr's reason itself is
   * already in the error text; this adds which tool or parameter to check.
   *
   * @param collection the collection that was queried
   * @param statusCode the HTTP status Solr answered with, a 4xx other than 404
   * @param reason Solr's reason, without SolrJ's wording or URLs
   * @return the hint, starting with its own punctuation, or an empty string
   */
  private static String remediationHint(String collection, int statusCode, String reason) {
    // Every 400 is a generic SolrException, told apart only by Solr's message text.
    if (statusCode != SolrException.ErrorCode.BAD_REQUEST.code) {
      return "";
    }
    final String lower = reason.toLowerCase(Locale.ROOT);
    if (lower.contains(UNDEFINED_FIELD_TOKEN)
        || lower.contains(SORT_FIELD_NOT_FOUND_TOKEN)
        || lower.contains(INVALID_QUERY_FIELD_TOKEN)) {
      return LIST_FIELDS_HINT_FORMAT.formatted(collection);
    }
    if (lower.contains(SYNTAX_ERROR_TOKEN) || lower.contains(CANNOT_PARSE_TOKEN)) {
      return LUCENE_SYNTAX_HINT;
    }
    return BAD_REQUEST_HINT;
  }

  /**
   * MCP prompt that guides the client through translating a natural-language question into a Solr
   * query: inspect the schema, build the query, run the search, and refine the result.
   *
   * @param collection target Solr collection name
   * @param question the user's natural-language search question or information need
   * @return the prompt text instructing the model how to run the search
   */
  @PreAuthorize("isAuthenticated()")
  @McpPrompt(
      name = PromptNames.SEARCH_COLLECTION,
      title = "Search a Solr collection from a natural-language question",
      description = PromptNames.SEARCH_COLLECTION_DESCRIPTION)
  public GetPromptResult searchCollectionPrompt(
      @McpArg(name = "collection", description = "Target Solr collection name", required = true)
          String collection,
      @McpArg(
              name = "question",
              description = "The user's natural-language search question or information need",
              required = true)
          String question) {
    return McpResults.prompt(
        PromptNames.SEARCH_COLLECTION_DESCRIPTION,
        Role.USER,
        """
				You are searching collection `%s` to answer:

				    %s

				Work incrementally — Solr query design is sensitive to the schema, so anchor on the
				schema before constructing queries.

				1. Learn the fields.
				   - Call `list-fields` on `%s`. It lists the fields that hold data, dynamic-field
				     instances included. From each field's `type` identify which are searchable text
				     (`text_general` or similar tokenized types), which are filterable exact-match
				     (`string`/`strings`), and which are numeric/date ranges. A `D` in a field's
				     `schema` flags (decoded by `info.key`) means docValues: those are the fields you
				     can facet on, and sort on when they are single-valued (no `M`). `dynamicBase`
				     shows which pattern a field came from (e.g. `*_s`).

				2. Translate the question into Solr query parts.
				   - Pick the most informative tokens from the question. Map them to fields:
				     * Free-text concepts → `query` against tokenized fields, e.g.
				       `description:apocalyptic` or `title:Solr`. To search the same words across
				       several text fields, put the plain words in `query` and the fields in
				       `queryFields`, e.g. `query=apocalyptic survival`, `queryFields=["title", "description"]`.
				     * Exact-match attributes → `filterQueries` against `string`/`strings`, e.g.
				       `platform:Netflix`, `genres:Sci-Fi`. Quote multi-word values:
				       `platform:"Amazon Prime Video"`.
				     * Numeric/date constraints → range syntax in `filterQueries`, e.g.
				       `release_year:[2010 TO 2020]`.
				   - Start with `*:*` as `query` if the question is purely filter-driven; let
				     `filterQueries` do the work.

				3. Run the search.
				   - Call `search` with `collection=%s` and the chosen `query` plus optional
				     `queryFields`, `filterQueries`, `facetFields`, `sortClauses`, `start`, `rows`,
				     `fields`. Set `rows=10` for a focused look or `rows=0` if you only need counts /
				     facets. Pass `fields` with the few fields that answer the question so long text
				     fields do not fill the response.

				4. Interpret and refine.
				   - Check `numFound` first.
				     * Zero results: relax filters one at a time, broaden the query (try a more
				       general term), or fall back to `query=*:*` with the strongest filter to
				       confirm the collection contains relevant data.
				     * Many results: add a `filterQueries` constraint to narrow, or pass
				       `facetFields` on a relevant `string`/`strings` field to surface the
				       distribution and pick a sharper filter.
				   - Inspect `documents` for the actual content. Each hit has `id` (the collection's
				     uniqueKey value), a relevance `score`, and its other values under `fields`. The
				     response's `maxScore` is the top score; scores are most meaningful as a relative
				     confidence signal for non-`*:*` queries.
				   - `facets` is a list with one entry per faceted field: `field` and its `values`, each
				     a `value` with its `count`, highest count first.

				5. Summarize.
				   - Answer the user's question grounded in the documents found, citing concrete field
				     values (title, id, etc.). If the search did not produce a clear answer, surface
				     that explicitly rather than guessing.
				"""
            .formatted(collection, question, collection, collection));
  }
}
