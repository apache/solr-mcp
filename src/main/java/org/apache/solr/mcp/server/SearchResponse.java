package org.apache.solr.mcp.server;

import java.util.List;
import java.util.Map;

/**
 * Immutable record representing a structured search response from Apache Solr operations.
 * 
 * <p>This record encapsulates all essential components of a Solr search result in a
 * type-safe, immutable structure that can be easily serialized to JSON for MCP client
 * consumption. It provides a clean abstraction over Solr's native response format while
 * preserving all critical search metadata and result data.</p>
 * 
 * <p><strong>Record Benefits:</strong></p>
 * <ul>
 *   <li><strong>Immutability</strong>: Response data cannot be modified after creation</li>
 *   <li><strong>Type Safety</strong>: Compile-time validation of response structure</li>
 *   <li><strong>JSON Serialization</strong>: Automatic conversion to JSON for MCP clients</li>
 *   <li><strong>Memory Efficiency</strong>: Compact representation with minimal overhead</li>
 * </ul>
 * 
 * <p><strong>Search Metadata:</strong></p>
 * <p>The response includes comprehensive search metadata that helps clients understand
 * the query results and implement pagination, relevance analysis, and user interfaces:</p>
 * <ul>
 *   <li><strong>Total Results</strong>: Complete count of matching documents</li>
 *   <li><strong>Pagination Info</strong>: Current offset for result windowing</li>
 *   <li><strong>Relevance Scoring</strong>: Maximum relevance score in the result set</li>
 * </ul>
 * 
 * <p><strong>Document Structure:</strong></p>
 * <p>Documents are represented as flexible key-value maps to accommodate Solr's
 * dynamic field capabilities and schema-less operation. Each document map contains
 * field names as keys and field values as objects, preserving the original data types
 * from Solr (strings, numbers, dates, arrays, etc.).</p>
 * 
 * <p><strong>Faceting Support:</strong></p>
 * <p>Facet information is structured as a nested map hierarchy where the outer map
 * represents facet field names and inner maps contain facet values with their
 * corresponding document counts. This structure efficiently supports multiple
 * faceting strategies including field faceting and range faceting.</p>
 * 
 * <p><strong>Usage Examples:</strong></p>
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
 * <p><strong>JSON Serialization Example:</strong></p>
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
 * 
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 * 
 * @see SearchService#search(String, String, List, List, List, Integer, Integer)
 * @see org.apache.solr.client.solrj.response.QueryResponse
 * @see org.apache.solr.common.SolrDocumentList
 */
public record SearchResponse(
        long numFound,
        long start,
        Float maxScore,
        List<Map<String, Object>> documents,
        Map<String, Map<String, Long>> facets
) {
}