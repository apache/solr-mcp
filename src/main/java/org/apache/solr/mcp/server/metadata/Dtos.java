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
package org.apache.solr.mcp.server.metadata;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Date;

/**
 * Data Transfer Objects (DTOs) for the Apache Solr MCP Server.
 *
 * <p>This package contains all the data transfer objects used to serialize and deserialize Solr
 * metrics, search results, and health status information for Model Context Protocol (MCP) clients.
 * All DTOs use Java records for immutability and Jackson annotations for JSON serialization.
 *
 * <p><strong>Key Features:</strong>
 *
 * <ul>
 *   <li>Automatic null value exclusion from JSON output using
 *       {@code @JsonInclude(JsonInclude.Include.NON_NULL)}
 *   <li>Resilient JSON parsing with {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 *   <li>Immutable data structures using Java records
 *   <li>ISO 8601 timestamp formatting for consistent date serialization
 * </ul>
 *
 * @version 0.0.1
 * @since 0.0.1
 */

/**
 * Top-level container for comprehensive Solr collection metrics.
 *
 * <p>This class aggregates various types of Solr performance and operational metrics including
 * index statistics, query performance, cache utilization, and request handler metrics. It serves as
 * the primary response object for collection monitoring and analysis tools.
 *
 * <p>The metrics are collected from multiple Solr admin endpoints and MBeans to provide a
 * comprehensive view of collection health and performance characteristics.
 *
 * <p><strong>Null-Safe Design:</strong>
 *
 * <p>Individual metric components (cache stats, handler stats) may be null if the corresponding
 * data is unavailable or empty. Always check for null values before accessing nested properties.
 *
 * <p><strong>Example usage:</strong>
 *
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
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record SolrMetrics(
        /** Index-related statistics including document counts and segment information */
        IndexStats indexStats,

        /** Query performance metrics from the most recent search operations */
        QueryStats queryStats,

        /**
         * Cache utilization statistics for query result, document, and filter caches (may be null)
         */
        CacheStats cacheStats,

        /** Request handler performance metrics for select and update operations (may be null) */
        HandlerStats handlerStats,

        /** Timestamp when these metrics were collected, formatted as ISO 8601 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        Date timestamp) {
}

/**
 * Lucene index statistics for a Solr collection.
 *
 * <p>Provides essential information about the underlying Lucene index structure and document
 * composition. These metrics are retrieved using Solr's Luke request handler which exposes
 * Lucene-level index information.
 *
 * <p><strong>Available Metrics:</strong>
 *
 * <ul>
 *   <li><strong>numDocs</strong>: Total number of documents excluding deleted documents
 *   <li><strong>segmentCount</strong>: Number of Lucene segments (affects search performance)
 * </ul>
 *
 * <p><strong>Performance Implications:</strong>
 *
 * <p>High segment counts may indicate the need for index optimization to improve search
 * performance. The optimal segment count depends on index size and update frequency.
 *
 * @see org.apache.solr.client.solrj.request.LukeRequest
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record IndexStats(
        /** Total number of documents in the index (excluding deleted documents) */
        Integer numDocs,

        /**
         * Number of Lucene segments in the index (lower numbers generally indicate better
         * performance)
         */
        Integer segmentCount) {
}

/**
 * Field-level statistics for individual Solr schema fields.
 *
 * <p>Provides detailed information about how individual fields are utilized within the Solr index.
 * This information helps with schema optimization and understanding field usage patterns.
 *
 * <p><strong>Statistics include:</strong>
 *
 * <ul>
 *   <li><strong>type</strong>: Solr field type (e.g., "text_general", "int", "date")
 *   <li><strong>docs</strong>: Number of documents containing this field
 *   <li><strong>distinct</strong>: Number of unique values for this field
 * </ul>
 *
 * <p><strong>Analysis Insights:</strong>
 *
 * <p>High cardinality fields (high distinct values) may require special indexing considerations,
 * while sparsely populated fields (low docs count) might benefit from different storage strategies.
 *
 * <p><strong>Note:</strong> This class is currently unused in the collection statistics but is
 * available for future field-level analysis features.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record FieldStats(
        /** Solr field type as defined in the schema configuration */
        String type,

        /** Number of documents in the index that contain this field */
        Integer docs,

        /** Number of unique/distinct values for this field across all documents */
        Integer distinct) {
}

/**
 * Query execution performance metrics from Solr search operations.
 *
 * <p>Captures performance characteristics and result metadata from the most recent query execution.
 * These metrics help identify query performance patterns and potential optimization opportunities.
 *
 * <p><strong>Available Metrics:</strong>
 *
 * <ul>
 *   <li><strong>queryTime</strong>: Time in milliseconds to execute the query
 *   <li><strong>totalResults</strong>: Total number of matching documents found
 *   <li><strong>start</strong>: Starting offset for pagination
 *   <li><strong>maxScore</strong>: Highest relevance score in the result set
 * </ul>
 *
 * <p><strong>Performance Analysis:</strong>
 *
 * <p>Query time metrics help identify slow queries that may need optimization, while result counts
 * and scores provide insight into search effectiveness and relevance tuning needs.
 *
 * @see org.apache.solr.client.solrj.response.QueryResponse
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record QueryStats(
        /** Time in milliseconds required to execute the most recent query */
        Integer queryTime,

        /** Total number of documents matching the query criteria */
        Long totalResults,

        /** Starting position for paginated results (0-based offset) */
        Long start,

        /** Highest relevance score among the returned documents */
        Float maxScore) {
}

/**
 * Solr cache utilization statistics across all cache types.
 *
 * <p>Aggregates cache performance metrics for the three primary Solr caches. Cache performance
 * directly impacts query response times and system resource utilization, making these metrics
 * critical for performance tuning.
 *
 * <p><strong>Monitored Cache Types:</strong>
 *
 * <ul>
 *   <li><strong>queryResultCache</strong>: Caches complete query results
 *   <li><strong>documentCache</strong>: Caches retrieved document data
 *   <li><strong>filterCache</strong>: Caches filter query results
 * </ul>
 *
 * <p><strong>Cache Analysis:</strong>
 *
 * <p>Poor cache hit ratios may indicate undersized caches or query patterns that don't benefit from
 * caching. Cache evictions suggest memory pressure or cache size optimization needs.
 *
 * @see CacheInfo
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record CacheStats(
        /** Performance metrics for the query result cache */
        CacheInfo queryResultCache,

        /** Performance metrics for the document cache */
        CacheInfo documentCache,

        /** Performance metrics for the filter cache */
        CacheInfo filterCache) {
}

/**
 * Detailed performance metrics for individual Solr cache instances.
 *
 * <p>Provides comprehensive cache utilization statistics including hit ratios, eviction rates, and
 * current size metrics. These metrics are essential for cache tuning and memory management
 * optimization.
 *
 * <p><strong>Key Performance Indicators:</strong>
 *
 * <ul>
 *   <li><strong>hitratio</strong>: Cache effectiveness (higher is better)
 *   <li><strong>evictions</strong>: Memory pressure indicator
 *   <li><strong>size</strong>: Current cache utilization
 *   <li><strong>lookups vs hits</strong>: Cache request patterns
 * </ul>
 *
 * <p><strong>Performance Targets:</strong>
 *
 * <p>Optimal cache performance typically shows high hit ratios (>0.80) with minimal evictions. High
 * eviction rates suggest cache size increases may improve performance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record CacheInfo(
        /** Total number of cache lookup requests */
        Long lookups,

        /** Number of successful cache hits */
        Long hits,

        /** Cache hit ratio (hits/lookups) - higher values indicate better cache performance */
        Float hitratio,

        /** Number of new entries added to the cache */
        Long inserts,

        /** Number of entries removed due to cache size limits (indicates memory pressure) */
        Long evictions,

        /** Current number of entries stored in the cache */
        Long size) {
}

/**
 * Request handler performance statistics for core Solr operations.
 *
 * <p>Tracks performance metrics for the primary Solr request handlers that process search and
 * update operations. Handler performance directly affects user experience and system throughput
 * capacity.
 *
 * <p><strong>Monitored Handlers:</strong>
 *
 * <ul>
 *   <li><strong>selectHandler</strong>: Processes search/query requests (/select)
 *   <li><strong>updateHandler</strong>: Processes document indexing requests (/update)
 * </ul>
 *
 * <p><strong>Performance Analysis:</strong>
 *
 * <p>Handler metrics help identify bottlenecks in request processing and guide capacity planning
 * decisions. High error rates or response times indicate potential optimization needs.
 *
 * @see HandlerInfo
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record HandlerStats(
        /** Performance metrics for the search/select request handler */
        HandlerInfo selectHandler,

        /** Performance metrics for the document update request handler */
        HandlerInfo updateHandler) {
}

/**
 * Detailed performance metrics for individual Solr request handlers.
 *
 * <p>Provides comprehensive request handler statistics including throughput, error rates, and
 * performance characteristics. These metrics are crucial for identifying performance bottlenecks
 * and system reliability issues.
 *
 * <p><strong>Performance Metrics:</strong>
 *
 * <ul>
 *   <li><strong>requests</strong>: Total volume processed
 *   <li><strong>errors</strong>: Reliability indicator
 *   <li><strong>avgTimePerRequest</strong>: Response time performance
 *   <li><strong>avgRequestsPerSecond</strong>: Throughput capacity
 * </ul>
 *
 * <p><strong>Health Indicators:</strong>
 *
 * <p>High error rates may indicate system stress or configuration issues. Increasing response times
 * suggest capacity limits or optimization needs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record HandlerInfo(
        /** Total number of requests processed by this handler */
        Long requests,

        /** Number of requests that resulted in errors */
        Long errors,

        /** Number of requests that exceeded timeout limits */
        Long timeouts,

        /** Cumulative time spent processing all requests (milliseconds) */
        Long totalTime,

        /** Average time per request in milliseconds */
        Float avgTimePerRequest,

        /** Average throughput in requests per second */
        Float avgRequestsPerSecond) {
}

/**
 * Comprehensive health status assessment for Solr collections.
 *
 * <p>Provides a complete health check result including availability status, performance metrics,
 * and diagnostic information. This serves as a primary monitoring endpoint for collection
 * operational status.
 *
 * <p><strong>Health Assessment Components:</strong>
 *
 * <ul>
 *   <li><strong>isHealthy</strong>: Overall collection availability
 *   <li><strong>responseTime</strong>: Performance indicator
 *   <li><strong>totalDocuments</strong>: Content availability
 *   <li><strong>errorMessage</strong>: Diagnostic information when unhealthy
 * </ul>
 *
 * <p><strong>Monitoring Integration:</strong>
 *
 * <p>This DTO is typically used by monitoring systems and dashboards to provide real-time
 * collection health status and enable automated alerting on failures.
 *
 * <p><strong>Example usage:</strong>
 *
 * <pre>{@code
 * SolrHealthStatus status = collectionService.checkHealth("my_collection");
 * if (!status.isHealthy()) {
 *     logger.error("Collection unhealthy: " + status.getErrorMessage());
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record SolrHealthStatus(
        /** Overall health status - true if collection is operational and responding */
        boolean isHealthy,

        /** Detailed error message when isHealthy is false, null when healthy */
        String errorMessage,

        /** Response time in milliseconds for the health check ping request */
        Long responseTime,

        /** Total number of documents currently indexed in the collection */
        Long totalDocuments,

        /** Timestamp when this health check was performed, formatted as ISO 8601 */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        Date lastChecked,

        /** Name of the collection that was checked */
        String collection,

        /** Version of Solr server (when available) */
        String solrVersion,

        /** Additional status information or state description */
        String status) {
}
