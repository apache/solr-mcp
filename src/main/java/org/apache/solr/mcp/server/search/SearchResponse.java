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

import java.util.List;
import java.util.Map;

/**
 * Immutable record representing a structured search response from Apache Solr operations.
 *
 * <p>This record encapsulates all essential components of a Solr search result in a type-safe,
 * immutable structure that can be easily serialized to JSON for MCP client consumption. It provides
 * a clean abstraction over Solr's native response format while preserving all critical search
 * metadata and result data.
 *
 * <p><strong>Record Benefits:</strong>
 *
 * <ul>
 *   <li><strong>Immutability</strong>: Response data cannot be modified after creation
 *   <li><strong>Type Safety</strong>: Compile-time validation of response structure
 *   <li><strong>JSON Serialization</strong>: Automatic conversion to JSON for MCP clients
 *   <li><strong>Memory Efficiency</strong>: Compact representation with minimal overhead
 * </ul>
 *
 * <p><strong>Search Metadata:</strong>
 *
 * <p>The response includes comprehensive search metadata that helps clients understand the query
 * results and implement pagination, relevance analysis, and user interfaces:
 *
 * <ul>
 *   <li><strong>Total Results</strong>: Complete count of matching documents
 *   <li><strong>Pagination Info</strong>: Current offset for result windowing
 *   <li><strong>Relevance Scoring</strong>: Maximum relevance score in the result set
 * </ul>
 *
 * <p><strong>Document Structure:</strong>
 *
 * <p>Documents are represented as flexible key-value maps to accommodate Solr's dynamic field
 * capabilities and schema-less operation. Each document map contains field names as keys and field
 * values as objects, preserving the original data types from Solr (strings, numbers, dates, arrays,
 * etc.).
 *
 * <p><strong>Faceting Support:</strong>
 *
 * <p>Facet information is structured as a nested map hierarchy where the outer map represents facet
 * field names and inner maps contain facet values with their corresponding document counts. This
 * structure efficiently supports multiple faceting strategies including field faceting and range
 * faceting.
 *
 * <p><strong>Usage Examples:</strong>
 *
 * <pre>{@code
 * // Access search results
 * SearchResponse response = searchService.search("products", "laptop", null, null, null, 0, 10);
 * System.out.println("Found " + response.numFound() + " products");
 *
 * // Iterate through documents
 * for (Map<String, Object> doc : response.documents()) {
 *     System.out.println("Title: " + doc.get("title"));
 *     System.out.println("Price: " + doc.get("price"));
 * }
 *
 * // Access facet data
 * Map<String, Long> categoryFacets = response.facets().get("category");
 * categoryFacets.forEach((category, count) ->
 *     System.out.println(category + ": " + count + " items"));
 * }</pre>
 *
 * <p><strong>JSON Serialization Example:</strong>
 *
 * <pre>{@code
 * {
 *   "numFound": 150,
 *   "start": 0,
 *   "maxScore": 2.34,
 *   "documents": [
 *     {"id": "1", "title": "Product 1", "price": 29.99},
 *     {"id": "2", "title": "Product 2", "price": 19.99}
 *   ],
 *   "facets": {
 *     "category": {"electronics": 45, "books": 30},
 *     "brand": {"apple": 12, "samsung": 8}
 *   }
 * }
 * }</pre>
 *
 * @param numFound total number of documents matching the search query across all pages
 * @param start zero-based offset indicating the starting position of returned results
 * @param maxScore highest relevance score among the returned documents (null if scoring disabled)
 * @param documents list of document maps containing field names and values for each result
 * @param facets nested map structure containing facet field names, values, and document counts
 * @version 0.0.1
 * @see SearchService#search(String, String, List, List, List, Integer, Integer)
 * @see org.apache.solr.client.solrj.response.QueryResponse
 * @see org.apache.solr.common.SolrDocumentList
 * @since 0.0.1
 */
public record SearchResponse(
        long numFound,
        long start,
        Float maxScore,
        List<Map<String, Object>> documents,
        Map<String, Map<String, Long>> facets) {
}
