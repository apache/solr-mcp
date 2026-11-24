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
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.DisMaxParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.mcp.server.util.PromptNames;
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
 * <p>Returns structured {@link SearchResponse} objects that encapsulate search results, metadata,
 * and facet information in a format optimized for JSON serialization and consumption by AI clients.
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

  /** Remediation hint naming the {@code list-collections} tool. */
  static final String LIST_COLLECTIONS_HINT =
      ". Hint: call list-collections to see available collections.";

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

  private static final String CONNECTION_ERROR =
      "Search failed: unable to communicate with Solr. Retry later;"
          + " if it persists, ask the operator to check Solr availability and connection settings.";
  private static final String RESPONSE_ERROR =
      "Search failed: unable to process the Solr response."
          + " If facetFields were supplied, call list-fields to check those fields or retry without facetFields;"
          + " if it persists, ask the operator to check server logs and Solr compatibility.";

  private final SolrClient solrClient;

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
  }

  /**
   * Extracts facet information from a QueryResponse.
   *
   * @param queryResponse The QueryResponse containing facet results
   * @return A Map where keys are facet field names and values are Maps of facet values to counts,
   *     both in Solr's original (count-sorted) order
   */
  private static Map<String, Map<String, Long>> getFacets(QueryResponse queryResponse) {
    Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
    if (queryResponse.getFacetFields() != null && !queryResponse.getFacetFields().isEmpty()) {
      queryResponse
          .getFacetFields()
          .forEach(
              facetField -> {
                Map<String, Long> facetValues = new LinkedHashMap<>();
                for (FacetField.Count count : facetField.getValues()) {
                  facetValues.put(count.getName(), count.getCount());
                }
                facets.put(facetField.getName(), facetValues);
              });
    }
    return facets;
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
   * @throws SolrServerException If there's an error communicating with Solr
   * @throws IOException If there's an I/O error
   */
  @PreAuthorize("isAuthenticated()")
  public SearchResponse search(
      String collection,
      @Nullable String query,
      @Nullable List<String> filterQueries,
      @Nullable List<String> facetFields,
      @Nullable List<SortClause> sortClauses,
      @Nullable Integer start,
      @Nullable Integer rows)
      throws SolrServerException, IOException {
    return search(
        collection, query, filterQueries, facetFields, sortClauses, start, rows, null, null);
  }

  /**
   * Searches a Solr collection with the specified parameters. This method is exposed as a tool for
   * MCP clients to use.
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
   * @param fields Fields to return (fl parameter); {@code score} is always added. Blank entries are
   *     ignored; all stored fields are returned when none remain
   * @param queryFields Fields to search the query against, with optional boosts (qf parameter).
   *     Blank entries are ignored; any remaining entry switches the request to eDisMax
   * @return A SearchResponse containing the search results and facets
   * @throws IllegalArgumentException if {@code queryFields} is combined with a {@code query} that
   *     starts with {@literal {!...}} local params
   * @throws SolrServerException If there's an error communicating with Solr
   * @throws IOException If there's an I/O error
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
			Put filters in fq (filterQueries) instead of q so Solr can cache and reuse them.
			Call list-fields first to get the field names that hold data and their types, rather than
			guessing names: dynamic fields such as genre_s exist only once documents use them. Facet
			only on fields that list-fields reports with docValues, and sort only on single-valued ones.
			""")
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
                  "Solr fl parameter: fields to return, e.g. [\"id\", \"name\"]. score is always"
                      + " included. If none specified, all stored fields are returned. Blank entries"
                      + " are ignored",
              required = false)
          @Nullable List<String> fields,
      @McpToolParam(
              description =
                  "Solr qf parameter: fields to search the query against, with optional boosts,"
                      + " e.g. [\"name^2\", \"author\"]. Uses the eDisMax parser; cannot be combined"
                      + " with a query that starts with {!...} local params. Blank entries are ignored",
              required = false)
          @Nullable List<String> queryFields)
      throws SolrServerException, IOException {

    // query
    final SolrQuery solrQuery = new SolrQuery("*:*");
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

    try {
      final QueryResponse queryResponse = solrClient.query(collection, solrQuery);

      // Add documents
      final SolrDocumentList documents = queryResponse.getResults();

      // Convert SolrDocuments to Maps
      // SolrDocument is a Map in Solr's field order, so no per-document copy
      final List<Map<String, Object>> docs = Collections.unmodifiableList(documents);

      // Add facets if present
      final var facets = getFacets(queryResponse);

      return new SearchResponse(
          documents.getNumFound(), documents.getStart(), documents.getMaxScore(), docs, facets);
    } catch (SolrException e) {
      throw withRemediationHint(e, collection);
    } catch (SolrServerException e) {
      logger.warn("Solr query failed on collection {}", collection, e);
      throw new SolrServerException(CONNECTION_ERROR);
    } catch (IOException e) {
      logger.warn("Solr query failed on collection {}", collection, e);
      throw new IOException(CONNECTION_ERROR);
    } catch (RuntimeException e) {
      // The client only sees RESPONSE_ERROR; the log is the sole record of the cause.
      logger.warn("Solr query response failed on collection {}", collection, e);
      throw new IllegalStateException(RESPONSE_ERROR);
    }
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
   * Wraps common Solr query failures with a next-step hint. MCP unwraps exception causes, so
   * client-facing exceptions must omit the cause to preserve safe guidance; the original failure is
   * logged for server-side diagnostics.
   *
   * @param e the Solr exception raised by the query
   * @param collection the collection that was queried
   * @return an exception carrying safe guidance; never with a cause, which MCP would unwrap in
   *     place of the guidance
   */
  private static RuntimeException withRemediationHint(SolrException e, String collection) {
    final String message = String.valueOf(e.getMessage());

    // Deliberately no cause on the exceptions below: the MCP annotation layer
    // reports the ROOT cause's message as the tool error, so attaching the Solr
    // failure would discard the guidance and the client would never see it. This
    // log is therefore the only server-side record of the failure.
    logger.warn("Solr query failed on collection {}", collection, e);

    // An unknown collection is a 404 whose body is Solr's HTML "not found" page,
    // so SolrJ reports it as a mime-type mismatch and leaves getMetadata() null.
    // The status code is the only signal that survives; match it rather than the
    // message text, which mentions neither the collection nor "404".
    if (e.code() == SolrException.ErrorCode.NOT_FOUND.code) {
      return new IllegalArgumentException(
          "Search failed: the collection was not found" + LIST_COLLECTIONS_HINT);
    }

    // Everything below is a 400 carrying a generic SolrException, indistinguishable
    // except by Solr's message text.
    final String lower = message.toLowerCase(Locale.ROOT);
    if (e.code() == SolrException.ErrorCode.BAD_REQUEST.code) {
      if (lower.contains(UNDEFINED_FIELD_TOKEN)
          || lower.contains(SORT_FIELD_NOT_FOUND_TOKEN)
          || lower.contains(INVALID_QUERY_FIELD_TOKEN)) {
        return new IllegalArgumentException(
            "Search failed: a referenced field is not defined"
                + LIST_FIELDS_HINT_FORMAT.formatted(collection));
      }
      if (lower.contains(SYNTAX_ERROR_TOKEN) || lower.contains(CANNOT_PARSE_TOKEN)) {
        return new IllegalArgumentException(
            "Search failed: Solr could not parse the query or filters" + LUCENE_SYNTAX_HINT);
      }
      return new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Search failed: Solr rejected the request. Check query syntax, query fields, filter queries,"
              + " facet fields, sort clauses, and pagination; call list-fields to verify the field names, and facet and"
              + " sort only on single-valued fields with D (docValues) in their schema flags.");
    }
    if (e.code() == SolrException.ErrorCode.UNAUTHORIZED.code
        || e.code() == SolrException.ErrorCode.FORBIDDEN.code) {
      return new SolrException(
          SolrException.ErrorCode.getErrorCode(e.code()),
          "Search failed: Solr denied access. Ask the operator to check the configured Solr credentials"
              + " and collection permissions.");
    }
    return new SolrException(
        SolrException.ErrorCode.getErrorCode(e.code()),
        "Search failed: Solr could not complete the request. Retry later; if it persists,"
            + " ask the operator to check Solr health and server logs.");
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
      description =
          "Guides the assistant through inspecting the schema, translating a user question into a Solr query, running the search, and refining the result.")
  public String searchCollectionPrompt(
      @McpArg(name = "collection", description = "Target Solr collection name", required = true)
          String collection,
      @McpArg(
              name = "question",
              description = "The user's natural-language search question or information need",
              required = true)
          String question) {
    return """
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
				     * Free-text concepts → `q` against tokenized fields, e.g.
				       `description:apocalyptic` or `title:Solr`. To search the same words across
				       several text fields, put the plain words in `query` and the fields in
				       `queryFields`, e.g. `query=apocalyptic survival`, `queryFields=["title", "description"]`.
				     * Exact-match attributes → `filterQueries` against `string`/`strings`, e.g.
				       `platform:Netflix`, `genres:Sci-Fi`. Quote multi-word values:
				       `platform:"Amazon Prime Video"`.
				     * Numeric/date constraints → range syntax in `filterQueries`, e.g.
				       `release_year:[2010 TO 2020]`.
				   - Start with `*:*` as `q` if the question is purely filter-driven; let
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
				                   general term), or fall back to `q=*:*` with the strongest filter to confirm the
				                   collection contains relevant data.
				                 * Many results: add a `filterQueries` constraint to narrow, or pass
				                   `facetFields` on a relevant `string`/`strings` field to surface the distribution
				                   and pick a sharper filter.
				   - Inspect `documents` for the actual content. Each document carries a relevance
				     `score`, and the response's `maxScore` is the top score among them; both are most
				     meaningful as a relative confidence signal for non-`*:*` queries.

				5. Summarize.
				   - Answer the user's question grounded in the documents found, citing concrete field
				     values (title, id, etc.). If the search did not produce a clear answer, surface
				     that explicitly rather than guessing.
				"""
        .formatted(collection, question, collection, collection);
  }
}
