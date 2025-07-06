package org.apache.solr.mcp.server;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Date;
import java.util.Map;

/**
 * Data Transfer Objects (DTOs) for the Apache Solr MCP Server.
 * 
 * <p>This package contains all the data transfer objects used to serialize and deserialize
 * Solr metrics, search results, and health status information for Model Context Protocol (MCP) clients.
 * All DTOs use Lombok annotations for reduced boilerplate and Jackson annotations for JSON serialization.</p>
 * 
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Automatic null value exclusion from JSON output using {@code @JsonInclude(JsonInclude.Include.NON_NULL)}</li>
 *   <li>Resilient JSON parsing with {@code @JsonIgnoreProperties(ignoreUnknown = true)}</li>
 *   <li>Immutable builder pattern using {@code @Jacksonized} and {@code @Builder}</li>
 *   <li>ISO 8601 timestamp formatting for consistent date serialization</li>
 * </ul>
 * 
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 */

/**
 * Top-level container for comprehensive Solr collection metrics.
 * 
 * <p>This class aggregates various types of Solr performance and operational metrics
 * including index statistics, query performance, cache utilization, and request handler metrics.
 * It serves as the primary response object for collection monitoring and analysis tools.</p>
 * 
 * <p>The metrics are collected from multiple Solr admin endpoints and MBeans to provide
 * a comprehensive view of collection health and performance characteristics.</p>
 * 
 * <p><strong>Null-Safe Design:</strong></p>
 * <p>Individual metric components (cache stats, handler stats) may be null if the corresponding
 * data is unavailable or empty. Always check for null values before accessing nested properties.</p>
 * 
 * <p><strong>Example usage:</strong></p>
 * <pre>{@code
 * SolrMetrics metrics = collectionService.getCollectionStats("my_collection");
 * System.out.println("Documents: " + metrics.getIndexStats().getNumDocs());
 * 
 * // Safe null checking for optional metrics
 * if (metrics.getCacheStats() != null && metrics.getCacheStats().getQueryResultCache() != null) {
 *     System.out.println("Cache hit ratio: " + metrics.getCacheStats().getQueryResultCache().getHitratio());
 * }
 * }</pre>
 * 
 * @see IndexStats
 * @see QueryStats
 * @see CacheStats
 * @see HandlerStats
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class SolrMetrics {
    
    /** Index-related statistics including document counts and segment information */
    private IndexStats indexStats;
    
    /** Query performance metrics from the most recent search operations */
    private QueryStats queryStats;
    
    /** Cache utilization statistics for query result, document, and filter caches (may be null) */
    private CacheStats cacheStats;
    
    /** Request handler performance metrics for select and update operations (may be null) */
    private HandlerStats handlerStats;
    
    /** Timestamp when these metrics were collected, formatted as ISO 8601 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date timestamp;
}

/**
 * Lucene index statistics for a Solr collection.
 * 
 * <p>Provides essential information about the underlying Lucene index structure
 * and document composition. These metrics are retrieved using Solr's Luke request handler
 * which exposes Lucene-level index information.</p>
 * 
 * <p><strong>Available Metrics:</strong></p>
 * <ul>
 *   <li><strong>numDocs</strong>: Total number of documents excluding deleted documents</li>
 *   <li><strong>segmentCount</strong>: Number of Lucene segments (affects search performance)</li>
 * </ul>
 * 
 * <p><strong>Performance Implications:</strong></p>
 * <p>High segment counts may indicate the need for index optimization to improve
 * search performance. The optimal segment count depends on index size and update frequency.</p>
 * 
 * @see org.apache.solr.client.solrj.request.LukeRequest
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class IndexStats {
    
    /** Total number of documents in the index (excluding deleted documents) */
    private Integer numDocs;
    
    /** Number of Lucene segments in the index (lower numbers generally indicate better performance) */
    private Integer segmentCount;
}

/**
 * Field-level statistics for individual Solr schema fields.
 * 
 * <p>Provides detailed information about how individual fields are utilized within
 * the Solr index. This information helps with schema optimization and understanding
 * field usage patterns.</p>
 * 
 * <p><strong>Statistics include:</strong></p>
 * <ul>
 *   <li><strong>type</strong>: Solr field type (e.g., "text_general", "int", "date")</li>
 *   <li><strong>docs</strong>: Number of documents containing this field</li>
 *   <li><strong>distinct</strong>: Number of unique values for this field</li>
 * </ul>
 * 
 * <p><strong>Analysis Insights:</strong></p>
 * <p>High cardinality fields (high distinct values) may require special indexing
 * considerations, while sparsely populated fields (low docs count) might benefit
 * from different storage strategies.</p>
 * 
 * <p><strong>Note:</strong> This class is currently unused in the collection statistics
 * but is available for future field-level analysis features.</p>
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class FieldStats {
    
    /** Solr field type as defined in the schema configuration */
    private String type;
    
    /** Number of documents in the index that contain this field */
    private Integer docs;
    
    /** Number of unique/distinct values for this field across all documents */
    private Integer distinct;
}

/**
 * Query execution performance metrics from Solr search operations.
 * 
 * <p>Captures performance characteristics and result metadata from the most recent
 * query execution. These metrics help identify query performance patterns and
 * potential optimization opportunities.</p>
 * 
 * <p><strong>Available Metrics:</strong></p>
 * <ul>
 *   <li><strong>queryTime</strong>: Time in milliseconds to execute the query</li>
 *   <li><strong>totalResults</strong>: Total number of matching documents found</li>
 *   <li><strong>start</strong>: Starting offset for pagination</li>
 *   <li><strong>maxScore</strong>: Highest relevance score in the result set</li>
 * </ul>
 * 
 * <p><strong>Performance Analysis:</strong></p>
 * <p>Query time metrics help identify slow queries that may need optimization,
 * while result counts and scores provide insight into search effectiveness and relevance tuning needs.</p>
 * 
 * @see org.apache.solr.client.solrj.response.QueryResponse
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class QueryStats {
    
    /** Time in milliseconds required to execute the most recent query */
    private Integer queryTime;
    
    /** Total number of documents matching the query criteria */
    private Long totalResults;
    
    /** Starting position for paginated results (0-based offset) */
    private Long start;
    
    /** Highest relevance score among the returned documents */
    private Float maxScore;
}

/**
 * Solr cache utilization statistics across all cache types.
 * 
 * <p>Aggregates cache performance metrics for the three primary Solr caches.
 * Cache performance directly impacts query response times and system resource
 * utilization, making these metrics critical for performance tuning.</p>
 * 
 * <p><strong>Monitored Cache Types:</strong></p>
 * <ul>
 *   <li><strong>queryResultCache</strong>: Caches complete query results</li>
 *   <li><strong>documentCache</strong>: Caches retrieved document data</li>
 *   <li><strong>filterCache</strong>: Caches filter query results</li>
 * </ul>
 * 
 * <p><strong>Cache Analysis:</strong></p>
 * <p>Poor cache hit ratios may indicate undersized caches or query patterns
 * that don't benefit from caching. Cache evictions suggest memory pressure
 * or cache size optimization needs.</p>
 * 
 * @see CacheInfo
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class CacheStats {
    
    /** Performance metrics for the query result cache */
    private CacheInfo queryResultCache;
    
    /** Performance metrics for the document cache */
    private CacheInfo documentCache;
    
    /** Performance metrics for the filter cache */
    private CacheInfo filterCache;
}

/**
 * Detailed performance metrics for individual Solr cache instances.
 * 
 * <p>Provides comprehensive cache utilization statistics including hit ratios,
 * eviction rates, and current size metrics. These metrics are essential for
 * cache tuning and memory management optimization.</p>
 * 
 * <p><strong>Key Performance Indicators:</strong></p>
 * <ul>
 *   <li><strong>hitratio</strong>: Cache effectiveness (higher is better)</li>
 *   <li><strong>evictions</strong>: Memory pressure indicator</li>
 *   <li><strong>size</strong>: Current cache utilization</li>
 *   <li><strong>lookups vs hits</strong>: Cache request patterns</li>
 * </ul>
 * 
 * <p><strong>Performance Targets:</strong></p>
 * <p>Optimal cache performance typically shows high hit ratios (>0.80) with
 * minimal evictions. High eviction rates suggest cache size increases may
 * improve performance.</p>
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class CacheInfo {
    
    /** Total number of cache lookup requests */
    private Long lookups;
    
    /** Number of successful cache hits */
    private Long hits;
    
    /** Cache hit ratio (hits/lookups) - higher values indicate better cache performance */
    private Float hitratio;
    
    /** Number of new entries added to the cache */
    private Long inserts;
    
    /** Number of entries removed due to cache size limits (indicates memory pressure) */
    private Long evictions;
    
    /** Current number of entries stored in the cache */
    private Long size;
}

/**
 * Request handler performance statistics for core Solr operations.
 * 
 * <p>Tracks performance metrics for the primary Solr request handlers that process
 * search and update operations. Handler performance directly affects user experience
 * and system throughput capacity.</p>
 * 
 * <p><strong>Monitored Handlers:</strong></p>
 * <ul>
 *   <li><strong>selectHandler</strong>: Processes search/query requests (/select)</li>
 *   <li><strong>updateHandler</strong>: Processes document indexing requests (/update)</li>
 * </ul>
 * 
 * <p><strong>Performance Analysis:</strong></p>
 * <p>Handler metrics help identify bottlenecks in request processing and guide
 * capacity planning decisions. High error rates or response times indicate
 * potential optimization needs.</p>
 * 
 * @see HandlerInfo
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class HandlerStats {
    
    /** Performance metrics for the search/select request handler */
    private HandlerInfo selectHandler;
    
    /** Performance metrics for the document update request handler */
    private HandlerInfo updateHandler;
}

/**
 * Detailed performance metrics for individual Solr request handlers.
 * 
 * <p>Provides comprehensive request handler statistics including throughput,
 * error rates, and performance characteristics. These metrics are crucial for
 * identifying performance bottlenecks and system reliability issues.</p>
 * 
 * <p><strong>Performance Metrics:</strong></p>
 * <ul>
 *   <li><strong>requests</strong>: Total volume processed</li>
 *   <li><strong>errors</strong>: Reliability indicator</li>
 *   <li><strong>avgTimePerRequest</strong>: Response time performance</li>
 *   <li><strong>avgRequestsPerSecond</strong>: Throughput capacity</li>
 * </ul>
 * 
 * <p><strong>Health Indicators:</strong></p>
 * <p>High error rates may indicate system stress or configuration issues.
 * Increasing response times suggest capacity limits or optimization needs.</p>
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class HandlerInfo {
    
    /** Total number of requests processed by this handler */
    private Long requests;
    
    /** Number of requests that resulted in errors */
    private Long errors;
    
    /** Number of requests that exceeded timeout limits */
    private Long timeouts;
    
    /** Cumulative time spent processing all requests (milliseconds) */
    private Long totalTime;
    
    /** Average time per request in milliseconds */
    private Float avgTimePerRequest;
    
    /** Average throughput in requests per second */
    private Float avgRequestsPerSecond;
}

/**
 * Comprehensive health status assessment for Solr collections.
 * 
 * <p>Provides a complete health check result including availability status,
 * performance metrics, and diagnostic information. This serves as a primary
 * monitoring endpoint for collection operational status.</p>
 * 
 * <p><strong>Health Assessment Components:</strong></p>
 * <ul>
 *   <li><strong>isHealthy</strong>: Overall collection availability</li>
 *   <li><strong>responseTime</strong>: Performance indicator</li>
 *   <li><strong>totalDocuments</strong>: Content availability</li>
 *   <li><strong>errorMessage</strong>: Diagnostic information when unhealthy</li>
 * </ul>
 * 
 * <p><strong>Monitoring Integration:</strong></p>
 * <p>This DTO is typically used by monitoring systems and dashboards to provide
 * real-time collection health status and enable automated alerting on failures.</p>
 * 
 * <p><strong>Example usage:</strong></p>
 * <pre>{@code
 * SolrHealthStatus status = collectionService.checkHealth("my_collection");
 * if (!status.isHealthy()) {
 *     logger.error("Collection unhealthy: " + status.getErrorMessage());
 * }
 * }</pre>
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class SolrHealthStatus {
    
    /** Overall health status - true if collection is operational and responding */
    private boolean isHealthy;
    
    /** Detailed error message when isHealthy is false, null when healthy */
    private String errorMessage;
    
    /** Response time in milliseconds for the health check ping request */
    private Long responseTime;
    
    /** Total number of documents currently indexed in the collection */
    private Long totalDocuments;
    
    /** Timestamp when this health check was performed, formatted as ISO 8601 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date lastChecked;
    
    /** Name of the collection that was checked */
    private String collection;
    
    /** Version of Solr server (when available) */
    private String solrVersion;
    
    /** Additional status information or state description */
    private String status;
}