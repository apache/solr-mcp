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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.mcp.server.util.PromptNames;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Spring Service providing comprehensive search capabilities for Apache Solr
 * collections through Model Context Protocol (MCP) integration.
 *
 * <p>
 * This service serves as the primary interface for executing search operations
 * against Solr collections, offering a rich set of features including text
 * search, filtering, faceting, sorting, and pagination. It transforms complex
 * Solr query syntax into accessible MCP tools that AI clients can invoke
 * through natural language requests.
 *
 * <p>
 * <strong>Core Features:</strong>
 *
 * <ul>
 * <li><strong>Full-Text Search</strong>: Advanced text search with relevance
 * scoring
 * <li><strong>Filtering</strong>: Multi-criteria filtering using Solr filter
 * queries
 * <li><strong>Faceting</strong>: Dynamic facet generation for result
 * categorization
 * <li><strong>Sorting</strong>: Flexible result ordering by multiple fields
 * <li><strong>Pagination</strong>: Efficient handling of large result sets
 * </ul>
 *
 * <p>
 * <strong>Dynamic Field Support:</strong>
 *
 * <p>
 * The service handles Solr's dynamic field naming conventions where field names
 * include type suffixes that indicate data types and indexing behavior:
 *
 * <ul>
 * <li><strong>_s</strong>: String fields for exact matching
 * <li><strong>_t</strong>: Text fields with tokenization and analysis
 * <li><strong>_i, _l, _f, _d</strong>: Numeric fields (int, long, float,
 * double)
 * <li><strong>_dt</strong>: Date/time fields
 * <li><strong>_b</strong>: Boolean fields
 * </ul>
 *
 * <p>
 * <strong>MCP Tool Integration:</strong>
 *
 * <p>
 * Search operations are exposed as MCP tools that AI clients can invoke through
 * natural language requests such as "search for books by George R.R. Martin" or
 * "find products under $50 in the electronics category".
 *
 * <p>
 * <strong>Response Format:</strong>
 *
 * <p>
 * Returns structured {@link SearchResponse} objects that encapsulate search
 * results, metadata, and facet information in a format optimized for JSON
 * serialization and consumption by AI clients.
 *
 * @see SearchResponse
 * @see SolrClient
 * @see McpTool
 */
@Service
@Observed
public class SearchService {

	/** Key for the field name within a sort clause map. */
	public static final String SORT_ITEM = "item";
	/**
	 * Key for the sort direction ({@code asc} / {@code desc}) within a sort clause
	 * map.
	 */
	public static final String SORT_ORDER = "order";

	/**
	 * Fragments of Solr's own error text that identify a failure we can advise on.
	 *
	 * <p>
	 * These are matched rather than imported because they are produced by
	 * <em>solr-core</em>, which is not on this server's classpath — only
	 * {@code solr-solrj} is. Nothing couples them to Solr at compile time, so
	 * {@code SearchServiceIntegrationTest} pins each one against a real Solr
	 * server: if a future Solr rewords a message, that test fails rather than the
	 * hint silently disappearing. Prefer a structured signal (see
	 * {@link SolrException#code()} below) whenever one exists.
	 */
	static final String UNDEFINED_FIELD_TOKEN = "undefined field";
	/** @see #UNDEFINED_FIELD_TOKEN */
	static final String SORT_FIELD_NOT_FOUND_TOKEN = "field can't be found";
	/** @see #UNDEFINED_FIELD_TOKEN */
	static final String SYNTAX_ERROR_TOKEN = "syntaxerror";
	/** @see #UNDEFINED_FIELD_TOKEN */
	static final String CANNOT_PARSE_TOKEN = "cannot parse";

	/**
	 * Remediation hint naming the {@code get-schema} tool; takes the collection.
	 */
	static final String GET_SCHEMA_HINT_FORMAT = ". Hint: call get-schema on collection '%s' to see the fields that"
			+ " exist; schemaless collections often store values under dynamic-suffix names such as name_s or price_d.";
	/** Remediation hint for an unparseable {@code q}. */
	static final String LUCENE_SYNTAX_HINT = ". Hint: the q parameter uses Lucene query syntax; quote or escape"
			+ " special characters and check any {!...} local params.";
	/** Remediation hint naming the {@code list-collections} tool. */
	static final String LIST_COLLECTIONS_HINT = ". Hint: call list-collections to see available collections.";

	private final SolrClient solrClient;

	/**
	 * Constructs a new SearchService with the required SolrClient dependency.
	 *
	 * <p>
	 * This constructor is automatically called by Spring's dependency injection
	 * framework during application startup, providing the service with the
	 * necessary Solr client for executing search operations.
	 *
	 * @param solrClient
	 *            the SolrJ client instance for communicating with Solr
	 * @see SolrClient
	 */
	public SearchService(SolrClient solrClient) {
		this.solrClient = solrClient;
	}

	/**
	 * Converts a SolrDocumentList to a List of Maps for optimized JSON
	 * serialization.
	 *
	 * <p>
	 * This method transforms Solr's native document format into a structure that
	 * can be easily serialized to JSON and consumed by MCP clients. Each document
	 * becomes a flat map of field names to field values, preserving all data types.
	 *
	 * <p>
	 * <strong>Conversion Process:</strong>
	 *
	 * <ul>
	 * <li>Iterates through each SolrDocument in the list
	 * <li>Extracts all field names and their corresponding values
	 * <li>Creates a HashMap for each document with field-value pairs
	 * <li>Preserves original data types (strings, numbers, dates, arrays)
	 * </ul>
	 *
	 * <p>
	 * <strong>Performance Optimization:</strong>
	 *
	 * <p>
	 * Pre-allocates the ArrayList with the known document count to minimize memory
	 * allocations and improve conversion performance for large result sets.
	 *
	 * @param documents
	 *            the SolrDocumentList to convert from Solr's native format
	 * @return a List of Maps where each Map represents a document with field names
	 *         as keys
	 * @see SolrDocument
	 * @see SolrDocumentList
	 */
	private static List<Map<String, Object>> getDocs(SolrDocumentList documents) {
		List<Map<String, Object>> docs = new ArrayList<>(documents.size());
		documents.forEach(doc -> {
			Map<String, Object> docMap = new HashMap<>();
			for (String fieldName : doc.getFieldNames()) {
				docMap.put(fieldName, doc.getFieldValue(fieldName));
			}
			docs.add(docMap);
		});
		return docs;
	}

	/**
	 * Extracts facet information from a QueryResponse.
	 *
	 * @param queryResponse
	 *            The QueryResponse containing facet results
	 * @return A Map where keys are facet field names and values are Maps of facet
	 *         values to counts
	 */
	private static Map<String, Map<String, Long>> getFacets(QueryResponse queryResponse) {
		Map<String, Map<String, Long>> facets = new HashMap<>();
		if (queryResponse.getFacetFields() != null && !queryResponse.getFacetFields().isEmpty()) {
			queryResponse.getFacetFields().forEach(facetField -> {
				Map<String, Long> facetValues = new HashMap<>();
				for (FacetField.Count count : facetField.getValues()) {
					facetValues.put(count.getName(), count.getCount());
				}
				facets.put(facetField.getName(), facetValues);
			});
		}
		return facets;
	}

	/**
	 * Searches a Solr collection with the specified parameters. This method is
	 * exposed as a tool for MCP clients to use.
	 *
	 * @param collection
	 *            The Solr collection to query
	 * @param query
	 *            The Solr query string (q parameter). Defaults to "*:*" if not
	 *            specified
	 * @param filterQueries
	 *            List of filter queries (fq parameter)
	 * @param facetFields
	 *            List of fields to facet on
	 * @param sortClauses
	 *            List of sort clauses for ordering results
	 * @param start
	 *            Starting offset for pagination
	 * @param rows
	 *            Number of rows to return
	 * @return A SearchResponse containing the search results and facets
	 * @throws SolrServerException
	 *             If there's an error communicating with Solr
	 * @throws IOException
	 *             If there's an I/O error
	 */
	@PreAuthorize("isAuthenticated()")
	// @formatter:off — keep this @McpTool wrapped like the others; the text block disguises the real line length.
	@McpTool(
			name = "search",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true),
			description = """
			Search specified Solr collection with query, optional filters, facets, sorting, and pagination.
			The q parameter accepts Lucene query syntax. There are no separate defType/qf/mm parameters:
			to use eDisMax features (multi-field search, minimum-match, boosts), embed Solr local params
			in q, e.g. {!edismax qf='name author' mm=2}george martin.
			Put filters in fq (filterQueries) instead of q so Solr can cache and reuse them.
			Note that solr has dynamic fields where name of field in schema may end with suffixes
			_s: Represents a string field, used for exact string matching.
			_i: Represents an integer field.
			_l: Represents a long field.
			_f: Represents a float field.
			_d: Represents a double field.
			_dt: Represents a date field.
			_b: Represents a boolean field.
			_t: Often used for text fields that undergo tokenization and analysis.
			One example from the books collection:
			{
			      "id":"0553579908",
			      "cat":["book"],
			      "name":["A Clash of Kings"],
			      "price":[7.99],
			      "inStock":[true],
			      "author":["George R.R. Martin"],
			      "series_t":"A Song of Ice and Fire",
			      "sequence_i":2,
			      "genre_s":"fantasy",
			      "_version_":1836275819373133824,
			      "_root_":"0553579908"
			    }
			""")
	// @formatter:on
	public SearchResponse search(@McpToolParam(description = "Solr collection to query") String collection,
			@McpToolParam(
					description = "Solr q parameter. Lucene syntax; supports local params such as"
							+ " {!edismax qf='name author'}. If none specified defaults to \"*:*\"",
					required = false) @Nullable String query,
			@McpToolParam(
					description = "Solr fq parameter: list of filter queries, one filter per entry",
					required = false) @Nullable List<String> filterQueries,
			@McpToolParam(description = "Solr facet fields", required = false) @Nullable List<String> facetFields,
			@McpToolParam(
					description = "Solr sort parameter",
					required = false) @Nullable List<Map<String, String>> sortClauses,
			@McpToolParam(description = "Starting offset for pagination", required = false) @Nullable Integer start,
			@McpToolParam(description = "Number of rows to return", required = false) @Nullable Integer rows)
			throws SolrServerException, IOException {

		// query
		final SolrQuery solrQuery = new SolrQuery("*:*");
		if (StringUtils.hasText(query)) {
			solrQuery.setQuery(query);
		}

		// filter queries
		if (!CollectionUtils.isEmpty(filterQueries)) {
			solrQuery.setFilterQueries(filterQueries.toArray(new String[0]));
		}

		// facets
		if (!CollectionUtils.isEmpty(facetFields)) {
			solrQuery.setFacet(true);
			solrQuery.addFacetField(facetFields.toArray(new String[0]));
			solrQuery.setFacetMinCount(1);
			solrQuery.setFacetSort(FacetParams.FACET_SORT_COUNT);
		}

		// sorting
		if (!CollectionUtils.isEmpty(sortClauses)) {
			solrQuery.setSorts(sortClauses.stream().map(SearchService::toSortClause).toList());
		}

		// pagination
		if (start != null) {
			solrQuery.setStart(start);
		}

		if (rows != null) {
			solrQuery.setRows(rows);
		}

		final QueryResponse queryResponse;
		try {
			queryResponse = solrClient.query(collection, solrQuery);
		} catch (SolrException e) {
			throw withRemediationHint(e, collection);
		}

		// Add documents
		final SolrDocumentList documents = queryResponse.getResults();

		// Convert SolrDocuments to Maps
		final var docs = getDocs(documents);

		// Add facets if present
		final var facets = getFacets(queryResponse);

		return new SearchResponse(documents.getNumFound(), documents.getStart(), documents.getMaxScore(), docs, facets);
	}

	/**
	 * Builds a {@link SolrQuery.SortClause} from one caller-supplied map.
	 *
	 * <p>
	 * Both keys are validated up front: {@code SortClause}'s constructor calls
	 * {@code ORDER.valueOf(order)}, which throws {@link NullPointerException} on a
	 * missing order and an opaque {@link IllegalArgumentException} on an
	 * unrecognised one. Callers are LLMs, so the message needs to say what to send.
	 */
	private static SolrQuery.SortClause toSortClause(Map<String, String> sortClause) {
		String field = sortClause.get(SORT_ITEM);
		String order = sortClause.get(SORT_ORDER);
		if (field == null || field.isBlank()) {
			throw new IllegalArgumentException("Each sort clause requires a non-empty '" + SORT_ITEM + "' key");
		}
		if (order == null || order.isBlank()) {
			throw new IllegalArgumentException(
					"Sort clause for '" + field + "' requires a '" + SORT_ORDER + "' key of 'asc' or 'desc'");
		}
		SolrQuery.ORDER parsed;
		try {
			parsed = SolrQuery.ORDER.valueOf(order.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"Unsupported sort order '" + order + "' for '" + field + "'; expected 'asc' or 'desc'", e);
		}
		return new SolrQuery.SortClause(field, parsed);
	}

	/**
	 * Wraps common Solr query failures with a next-step hint. MCP clients receive
	 * the exception message as the tool error, so naming the follow-up tool lets
	 * them self-correct instead of retrying blind.
	 *
	 * @param e
	 *            the Solr exception raised by the query
	 * @param collection
	 *            the collection that was queried
	 * @return an exception carrying the original message plus a remediation hint,
	 *         or the original exception when no hint applies
	 */
	private static RuntimeException withRemediationHint(SolrException e, String collection) {
		final String message = String.valueOf(e.getMessage());

		// An unknown collection is a 404 whose body is Solr's HTML "not found" page,
		// so SolrJ reports it as a mime-type mismatch and leaves getMetadata() null.
		// The status code is the only signal that survives; match it rather than the
		// message text, which mentions neither the collection nor "404".
		if (e.code() == SolrException.ErrorCode.NOT_FOUND.code) {
			return new IllegalArgumentException(message + LIST_COLLECTIONS_HINT, e);
		}

		// Everything below is a 400 carrying a generic SolrException, indistinguishable
		// except by Solr's message text.
		final String lower = message.toLowerCase(Locale.ROOT);
		if (lower.contains(UNDEFINED_FIELD_TOKEN) || lower.contains(SORT_FIELD_NOT_FOUND_TOKEN)) {
			return new IllegalArgumentException(message + GET_SCHEMA_HINT_FORMAT.formatted(collection), e);
		}
		if (lower.contains(SYNTAX_ERROR_TOKEN) || lower.contains(CANNOT_PARSE_TOKEN)) {
			return new IllegalArgumentException(message + LUCENE_SYNTAX_HINT, e);
		}
		return e;
	}

	/**
	 * MCP prompt that guides the client through translating a natural-language
	 * question into a Solr query: inspect the schema, build the query, run the
	 * search, and refine the result.
	 *
	 * @param collection
	 *            target Solr collection name
	 * @param question
	 *            the user's natural-language search question or information need
	 * @return the prompt text instructing the model how to run the search
	 */
	@PreAuthorize("isAuthenticated()")

	@McpPrompt(
			name = PromptNames.SEARCH_COLLECTION,
			title = "Search a Solr collection from a natural-language question",
			description = "Guides the assistant through inspecting the schema, translating a user question into a Solr query, running the search, and refining the result.")
	public String searchCollectionPrompt(
			@McpArg(
					name = "collection",
					description = "Target Solr collection name",
					required = true) String collection,
			@McpArg(
					name = "question",
					description = "The user's natural-language search question or information need",
					required = true) String question) {
		return """
				You are searching collection `%s` to answer:

				    %s

				Work incrementally — Solr query design is sensitive to the schema, so anchor on the
				schema before constructing queries.

				1. Learn the schema.
				   - Call `get-schema` on `%s`. Identify which fields are searchable text
				     (`text_general` or similar tokenized types), which are filterable exact-match
				     (`string`/`strings`), which are numeric/date ranges, and which have `docValues`
				     (needed for faceting and sorting).

				2. Translate the question into Solr query parts.
				   - Pick the most informative tokens from the question. Map them to fields:
				     * Free-text concepts → `q` against tokenized fields, e.g.
				       `description:apocalyptic` or `title:Solr` or a multi-field edismax-style
				       construction `(title:foo OR description:foo)`.
				     * Exact-match attributes → `filterQueries` against `string`/`strings`, e.g.
				       `platform:Netflix`, `genres:Sci-Fi`. Quote multi-word values:
				       `platform:"Amazon Prime Video"`.
				     * Numeric/date constraints → range syntax in `filterQueries`, e.g.
				       `release_year:[2010 TO 2020]`.
				   - Start with `*:*` as `q` if the question is purely filter-driven; let
				     `filterQueries` do the work.

				3. Run the search.
				   - Call `search` with `collection=%s` and the chosen `query` plus optional
				     `filterQueries`, `facetFields`, `sortClauses`, `start`, `rows`. Set `rows=10` for a
				     focused look or `rows=0` if you only need counts / facets.

				4. Interpret and refine.
				   - Check `numFound` first.
				                 * Zero results: relax filters one at a time, broaden the query (try a more
				                   general term), or fall back to `q=*:*` with the strongest filter to confirm the
				                   collection contains relevant data.
				                 * Many results: add a `filterQueries` constraint to narrow, or pass
				                   `facetFields` on a relevant `string`/`strings` field to surface the distribution
				                   and pick a sharper filter.
				   - Inspect `documents` for the actual content. The response includes `maxScore` when
				     the query is not `*:*`; use it as a relative confidence signal across queries.

				5. Summarize.
				   - Answer the user's question grounded in the documents found, citing concrete field
				     values (title, id, etc.). If the search did not produce a clear answer, surface
				     that explicitly rather than guessing.
				""".formatted(collection, question, collection, collection);
	}
}
