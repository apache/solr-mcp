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

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.FacetParams;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * @version 0.0.1
 * @see SearchResponse
 * @see SolrClient
 * @see org.springframework.ai.tool.annotation.Tool
 * @since 0.0.1
 */
@Service
public class SearchService {

    public static final String SORT_ITEM = "item";
    public static final String SORT_ORDER = "order";
    private final SolrClient solrClient;

    /**
     * Constructs a new SearchService with the required SolrClient dependency.
     *
     * <p>This constructor is automatically called by Spring's dependency injection framework during
     * application startup, providing the service with the necessary Solr client for executing
     * search operations.
     *
     * @param solrClient the SolrJ client instance for communicating with Solr
     * @see SolrClient
     */
    public SearchService(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    /**
     * Converts a SolrDocumentList to a List of Maps for optimized JSON serialization.
     *
     * <p>This method transforms Solr's native document format into a structure that can be easily
     * serialized to JSON and consumed by MCP clients. Each document becomes a flat map of field
     * names to field values, preserving all data types.
     *
     * <p><strong>Conversion Process:</strong>
     *
     * <ul>
     *   <li>Iterates through each SolrDocument in the list
     *   <li>Extracts all field names and their corresponding values
     *   <li>Creates a HashMap for each document with field-value pairs
     *   <li>Preserves original data types (strings, numbers, dates, arrays)
     * </ul>
     *
     * <p><strong>Performance Optimization:</strong>
     *
     * <p>Pre-allocates the ArrayList with the known document count to minimize memory allocations
     * and improve conversion performance for large result sets.
     *
     * @param documents the SolrDocumentList to convert from Solr's native format
     * @return a List of Maps where each Map represents a document with field names as keys
     * @see org.apache.solr.common.SolrDocument
     * @see org.apache.solr.common.SolrDocumentList
     */
    private static List<Map<String, Object>> getDocs(SolrDocumentList documents) {
        List<Map<String, Object>> docs = new java.util.ArrayList<>(documents.size());
        documents.forEach(
                doc -> {
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
     * @param queryResponse The QueryResponse containing facet results
     * @return A Map where keys are facet field names and values are Maps of facet values to counts
     */
    private static Map<String, Map<String, Long>> getFacets(QueryResponse queryResponse) {
        Map<String, Map<String, Long>> facets = new HashMap<>();
        if (queryResponse.getFacetFields() != null && !queryResponse.getFacetFields().isEmpty()) {
            queryResponse
                    .getFacetFields()
                    .forEach(
                            facetField -> {
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
     * Searches a Solr collection with the specified parameters. This method is exposed as a tool
     * for MCP clients to use.
     *
     * @param collection The Solr collection to query
     * @param query The Solr query string (q parameter). Defaults to "*:*" if not specified
     * @param filterQueries List of filter queries (fq parameter)
     * @param facetFields List of fields to facet on
     * @param sortClauses List of sort clauses for ordering results
     * @param start Starting offset for pagination
     * @param rows Number of rows to return
     * @return A SearchResponse containing the search results and facets
     * @throws SolrServerException If there's an error communicating with Solr
     * @throws IOException If there's an I/O error
     */
    @McpTool(
            name = "Search",
            description =
                    """
                            Search specified Solr collection with query, optional filters, facets, sorting, and pagination.
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
    public SearchResponse search(
            @McpToolParam(description = "Solr collection to query") String collection,
            @McpToolParam(
                    description = "Solr q parameter. If none specified defaults to \"*:*\"",
                    required = false)
            String query,
            @McpToolParam(description = "Solr fq parameter", required = false)
            List<String> filterQueries,
            @McpToolParam(description = "Solr facet fields", required = false)
            List<String> facetFields,
            @McpToolParam(description = "Solr sort parameter", required = false)
            List<Map<String, String>> sortClauses,
            @McpToolParam(description = "Starting offset for pagination", required = false)
            Integer start,
            @McpToolParam(description = "Number of rows to return", required = false) Integer rows)
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
            solrQuery.setSorts(
                    sortClauses.stream()
                            .map(
                                    sortClause ->
                                            new SolrQuery.SortClause(
                                                    sortClause.get(SORT_ITEM),
                                                    sortClause.get(SORT_ORDER)))
                            .toList());
        }

        // pagination
        if (start != null) {
            solrQuery.setStart(start);
        }

        if (rows != null) {
            solrQuery.setRows(rows);
        }

        final QueryResponse queryResponse = solrClient.query(collection, solrQuery);

        // Add documents
        final SolrDocumentList documents = queryResponse.getResults();

        // Convert SolrDocuments to Maps
        final var docs = getDocs(documents);

        // Add facets if present
        final var facets = getFacets(queryResponse);

        return new SearchResponse(
                documents.getNumFound(),
                documents.getStart(),
                documents.getMaxScore(),
                docs,
                facets);
    }
}
