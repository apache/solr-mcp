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

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.config.SolrConfigurationProperties;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.apache.solr.mcp.server.metadata.CollectionUtils.getFloat;
import static org.apache.solr.mcp.server.metadata.CollectionUtils.getInteger;
import static org.apache.solr.mcp.server.metadata.CollectionUtils.getLong;

/**
 * Spring Service providing comprehensive Solr collection management and monitoring capabilities for
 * Model Context Protocol (MCP) clients.
 *
 * <p>This service acts as the primary interface for collection-level operations in the Solr MCP
 * Server, providing tools for collection discovery, metrics gathering, health monitoring, and
 * performance analysis. It bridges the gap between MCP clients (like Claude Desktop) and Apache
 * Solr through the SolrJ client library.
 *
 * <p><strong>Core Capabilities:</strong>
 *
 * <ul>
 *   <li><strong>Collection Discovery</strong>: Lists available collections/cores with automatic
 *       SolrCloud vs standalone detection
 *   <li><strong>Performance Monitoring</strong>: Comprehensive metrics collection including index,
 *       query, cache, and handler statistics
 *   <li><strong>Health Monitoring</strong>: Real-time health checks with availability and
 *       performance indicators
 *   <li><strong>Shard-Aware Operations</strong>: Intelligent handling of SolrCloud shard names and
 *       collection name extraction
 * </ul>
 *
 * <p><strong>Implementation Details:</strong>
 *
 * <p>This class uses extensively documented constants for all API parameters, field names, and
 * paths to ensure maintainability and reduce the risk of typos. All string literals have been
 * replaced with well-named constants that are organized by category (API parameters, response
 * parsing keys, handler paths, statistics fields, etc.).
 *
 * <p><strong>MCP Tool Integration:</strong>
 *
 * <p>Methods annotated with {@code @McpTool} are automatically exposed as MCP tools that can be
 * invoked by AI clients. These tools provide natural language interfaces to Solr operations.
 *
 * <p><strong>Supported Solr Deployments:</strong>
 *
 * <ul>
 *   <li><strong>SolrCloud</strong>: Distributed mode using Collections API
 *   <li><strong>Standalone</strong>: Single-node mode using Core Admin API
 * </ul>
 *
 * <p><strong>Error Handling:</strong>
 *
 * <p>The service implements robust error handling with graceful degradation. Failed operations
 * return null values rather than throwing exceptions (except where validation requires it),
 * allowing partial metrics collection when some endpoints are unavailable.
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // List all available collections
 * List<String> collections = collectionService.listCollections();
 *
 * // Get comprehensive metrics for a collection
 * SolrMetrics metrics = collectionService.getCollectionStats("my_collection");
 *
 * // Check collection health
 * SolrHealthStatus health = collectionService.checkHealth("my_collection");
 * }</pre>
 *
 * @version 0.0.1
 * @see SolrMetrics
 * @see SolrHealthStatus
 * @see org.apache.solr.client.solrj.SolrClient
 * @since 0.0.1
 */
@Service
public class CollectionService {

    // ========================================
    // Constants for API Parameters and Paths
    // ========================================

    /**
     * Category parameter value for cache-related MBeans requests
     */
    private static final String CACHE_CATEGORY = "CACHE";

    /** Category parameter value for query handler MBeans requests */
    private static final String QUERY_HANDLER_CATEGORY = "QUERYHANDLER";

    /** Combined category parameter value for both query and update handler MBeans requests */
    private static final String HANDLER_CATEGORIES = "QUERYHANDLER,UPDATEHANDLER";

    /** Universal Solr query pattern to match all documents in a collection */
    private static final String ALL_DOCUMENTS_QUERY = "*:*";

    /** Suffix pattern used to identify shard names in SolrCloud deployments */
    private static final String SHARD_SUFFIX = "_shard";

    /** Request parameter name for enabling statistics in MBeans requests */
    private static final String STATS_PARAM = "stats";

    /** Request parameter name for specifying category filters in MBeans requests */
    private static final String CAT_PARAM = "cat";

    /** Request parameter name for specifying response writer type */
    private static final String WT_PARAM = "wt";

    /** JSON format specification for response writer type */
    private static final String JSON_FORMAT = "json";

    // ========================================
    // Constants for Response Parsing
    // ========================================

    /** Key name for collections list in Collections API responses */
    private static final String COLLECTIONS_KEY = "collections";

    /** Key name for segment count information in Luke response */
    private static final String SEGMENT_COUNT_KEY = "segmentCount";

    /** Key name for query result cache in MBeans cache responses */
    private static final String QUERY_RESULT_CACHE_KEY = "queryResultCache";

    /** Key name for document cache in MBeans cache responses */
    private static final String DOCUMENT_CACHE_KEY = "documentCache";

    /** Key name for filter cache in MBeans cache responses */
    private static final String FILTER_CACHE_KEY = "filterCache";

    /** Key name for statistics section in MBeans responses */
    private static final String STATS_KEY = "stats";

    // ========================================
    // Constants for Handler Paths
    // ========================================

    /** URL path for Solr select (query) handler */
    private static final String SELECT_HANDLER_PATH = "/select";

    /** URL path for Solr update handler */
    private static final String UPDATE_HANDLER_PATH = "/update";

    /** URL path for Solr MBeans admin endpoint */
    private static final String ADMIN_MBEANS_PATH = "/admin/mbeans";

    // ========================================
    // Constants for Statistics Field Names
    // ========================================

    /** Field name for cache/handler lookup count statistics */
    private static final String LOOKUPS_FIELD = "lookups";

    /** Field name for cache hit count statistics */
    private static final String HITS_FIELD = "hits";

    /** Field name for cache hit ratio statistics */
    private static final String HITRATIO_FIELD = "hitratio";

    /** Field name for cache insert count statistics */
    private static final String INSERTS_FIELD = "inserts";

    /** Field name for cache eviction count statistics */
    private static final String EVICTIONS_FIELD = "evictions";

    /** Field name for cache size statistics */
    private static final String SIZE_FIELD = "size";

    /** Field name for handler request count statistics */
    private static final String REQUESTS_FIELD = "requests";

    /** Field name for handler error count statistics */
    private static final String ERRORS_FIELD = "errors";

    /** Field name for handler timeout count statistics */
    private static final String TIMEOUTS_FIELD = "timeouts";

    /** Field name for handler total processing time statistics */
    private static final String TOTAL_TIME_FIELD = "totalTime";

    /** Field name for handler average time per request statistics */
    private static final String AVG_TIME_PER_REQUEST_FIELD = "avgTimePerRequest";

    /** Field name for handler average requests per second statistics */
    private static final String AVG_REQUESTS_PER_SECOND_FIELD = "avgRequestsPerSecond";

    // ========================================
    // Constants for Error Messages
    // ========================================

    /** Error message prefix for collection not found exceptions */
    private static final String COLLECTION_NOT_FOUND_ERROR = "Collection not found: ";

    /** SolrJ client for communicating with Solr server */
    private final SolrClient solrClient;

    /**
     * Constructs a new CollectionService with the required dependencies.
     *
     * <p>This constructor is automatically called by Spring's dependency injection framework during
     * application startup.
     *
     * @param solrClient the SolrJ client instance for communicating with Solr
     * @see SolrClient
     * @see SolrConfigurationProperties
     */
    public CollectionService(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    /**
     * Lists all available Solr collections or cores in the cluster.
     *
     * <p>This method automatically detects the Solr deployment type and uses the appropriate API:
     *
     * <ul>
     *   <li><strong>SolrCloud</strong>: Uses Collections API to list distributed collections
     *   <li><strong>Standalone</strong>: Uses Core Admin API to list individual cores
     * </ul>
     *
     * <p>In SolrCloud environments, the returned names may include shard identifiers (e.g.,
     * "films_shard1_replica_n1"). Use {@link #extractCollectionName(String)} to get the base
     * collection name if needed.
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>If the operation fails due to connectivity issues or API errors, an empty list is returned
     * rather than throwing an exception, allowing the application to continue functioning with
     * degraded capabilities.
     *
     * <p><strong>MCP Tool Usage:</strong>
     *
     * <p>This method is exposed as an MCP tool and can be invoked by AI clients with natural
     * language requests like "list all collections" or "show me available databases".
     *
     * @return a list of collection/core names, or an empty list if unable to retrieve them
     * @see CollectionAdminRequest.List
     * @see CoreAdminRequest
     */
    @McpTool(description = "List solr collections")
    public List<String> listCollections() {
        try {
            if (solrClient instanceof CloudSolrClient) {
                // For SolrCloud - use Collections API
                CollectionAdminRequest.List request = new CollectionAdminRequest.List();
                CollectionAdminResponse response = request.process(solrClient);

                @SuppressWarnings("unchecked")
                List<String> collections =
                        (List<String>) response.getResponse().get(COLLECTIONS_KEY);
                return collections != null ? collections : new ArrayList<>();
            } else {
                // For standalone Solr - use Core Admin API
                CoreAdminRequest coreAdminRequest = new CoreAdminRequest();
                coreAdminRequest.setAction(CoreAdminParams.CoreAdminAction.STATUS);
                CoreAdminResponse coreResponse = coreAdminRequest.process(solrClient);

                List<String> cores = new ArrayList<>();
                NamedList<NamedList<Object>> coreStatus = coreResponse.getCoreStatus();
                for (int i = 0; i < coreStatus.size(); i++) {
                    cores.add(coreStatus.getName(i));
                }
                return cores;
            }
        } catch (SolrServerException | IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Retrieves comprehensive performance metrics and statistics for a specified Solr collection.
     *
     * <p>This method aggregates metrics from multiple Solr endpoints to provide a complete
     * performance profile including index health, query performance, cache utilization, and request
     * handler statistics.
     *
     * <p><strong>Collected Metrics:</strong>
     *
     * <ul>
     *   <li><strong>Index Statistics</strong>: Document counts, segment information (via Luke
     *       handler)
     *   <li><strong>Query Performance</strong>: Response times, result counts, relevance scores
     *   <li><strong>Cache Utilization</strong>: Hit ratios, eviction rates for all cache types
     *   <li><strong>Handler Performance</strong>: Request volumes, error rates, throughput metrics
     * </ul>
     *
     * <p><strong>Collection Name Handling:</strong>
     *
     * <p>Supports both collection names and shard names. If a shard name like
     * "films_shard1_replica_n1" is provided, it will be automatically converted to the base
     * collection name "films" for API calls.
     *
     * <p><strong>Validation:</strong>
     *
     * <p>The method validates that the specified collection exists before attempting to collect
     * metrics. If the collection is not found, an {@code IllegalArgumentException} is thrown with a
     * descriptive error message.
     *
     * <p><strong>MCP Tool Usage:</strong>
     *
     * <p>Exposed as an MCP tool for natural language queries like "get metrics for my_collection"
     * or "show me performance stats for the search index".
     *
     * @param collection the name of the collection to analyze (supports both collection and shard
     *     names)
     * @return comprehensive metrics object containing all collected statistics
     * @throws IllegalArgumentException if the specified collection does not exist
     * @throws SolrServerException if there are errors communicating with Solr
     * @throws IOException if there are I/O errors during communication
     * @see SolrMetrics
     * @see LukeRequest
     * @see #extractCollectionName(String)
     */
    @McpTool(description = "Get stats/metrics on a Solr collection")
    public SolrMetrics getCollectionStats(
            @McpToolParam(description = "Solr collection to get stats/metrics for")
            String collection)
            throws SolrServerException, IOException {
        // Extract actual collection name from shard name if needed
        String actualCollection = extractCollectionName(collection);

        // Validate collection exists
        if (!validateCollectionExists(actualCollection)) {
            throw new IllegalArgumentException(COLLECTION_NOT_FOUND_ERROR + actualCollection);
        }

        // Index statistics using Luke
        LukeRequest lukeRequest = new LukeRequest();
        lukeRequest.setIncludeIndexFieldFlags(true);
        LukeResponse lukeResponse = lukeRequest.process(solrClient, actualCollection);

        // Query performance metrics
        QueryResponse statsResponse =
                solrClient.query(actualCollection, new SolrQuery(ALL_DOCUMENTS_QUERY).setRows(0));

        return new SolrMetrics(
                buildIndexStats(lukeResponse),
                buildQueryStats(statsResponse),
                getCacheMetrics(actualCollection),
                getHandlerMetrics(actualCollection),
                new Date());
    }

    /**
     * Builds an IndexStats object from a Solr Luke response containing index metadata.
     *
     * <p>The Luke handler provides low-level Lucene index information including document counts,
     * segment details, and field statistics. This method extracts the essential index health
     * metrics for monitoring and analysis.
     *
     * <p><strong>Extracted Metrics:</strong>
     *
     * <ul>
     *   <li><strong>numDocs</strong>: Total number of documents excluding deleted ones
     *   <li><strong>segmentCount</strong>: Number of Lucene segments (performance indicator)
     * </ul>
     *
     * <p><strong>Performance Implications:</strong>
     *
     * <p>High segment counts may indicate the need for index optimization to improve search
     * performance. The optimal segment count depends on index size and update frequency.
     *
     * @param lukeResponse the Luke response containing raw index information
     * @return IndexStats object with extracted and formatted metrics
     * @see IndexStats
     * @see LukeResponse
     */
    public IndexStats buildIndexStats(LukeResponse lukeResponse) {
        NamedList<Object> indexInfo = lukeResponse.getIndexInfo();

        // Extract index information using helper methods
        Integer segmentCount = getInteger(indexInfo, SEGMENT_COUNT_KEY);

        return new IndexStats(lukeResponse.getNumDocs(), segmentCount);
    }

    /**
     * Builds a QueryStats object from a Solr query response containing performance metrics.
     *
     * <p>Extracts key performance indicators from a query execution including timing, result
     * characteristics, and relevance scoring information. These metrics help identify query
     * performance patterns and optimization opportunities.
     *
     * <p><strong>Extracted Metrics:</strong>
     *
     * <ul>
     *   <li><strong>queryTime</strong>: Execution time in milliseconds
     *   <li><strong>totalResults</strong>: Total matching documents found
     *   <li><strong>start</strong>: Pagination offset (0-based)
     *   <li><strong>maxScore</strong>: Highest relevance score in results
     * </ul>
     *
     * <p><strong>Performance Analysis:</strong>
     *
     * <p>Query time metrics help identify slow queries that may need optimization, while result
     * counts and scores provide insight into search effectiveness.
     *
     * @param response the query response containing performance and result metadata
     * @return QueryStats object with extracted performance metrics
     * @see QueryStats
     * @see QueryResponse
     */
    public QueryStats buildQueryStats(QueryResponse response) {

        return new QueryStats(
                response.getQTime(),
                response.getResults().getNumFound(),
                response.getResults().getStart(),
                response.getResults().getMaxScore());
    }

    /**
     * Retrieves cache performance metrics for all cache types in a Solr collection.
     *
     * <p>Collects detailed cache utilization statistics from Solr's MBeans endpoint, providing
     * insights into cache effectiveness and memory usage patterns. Cache performance directly
     * impacts query response times and system efficiency.
     *
     * <p><strong>Monitored Cache Types:</strong>
     *
     * <ul>
     *   <li><strong>Query Result Cache</strong>: Caches complete query results for identical
     *       searches
     *   <li><strong>Document Cache</strong>: Caches retrieved document field data
     *   <li><strong>Filter Cache</strong>: Caches filter query results for faceting and filtering
     * </ul>
     *
     * <p><strong>Key Performance Indicators:</strong>
     *
     * <ul>
     *   <li><strong>Hit Ratio</strong>: Cache effectiveness (higher is better)
     *   <li><strong>Evictions</strong>: Memory pressure indicator
     *   <li><strong>Size</strong>: Current cache utilization
     * </ul>
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>Returns {@code null} if cache statistics cannot be retrieved or if all cache types are
     * empty/unavailable. This allows graceful degradation when cache monitoring is not available.
     *
     * @param collection the collection name to retrieve cache metrics for
     * @return CacheStats object with all cache performance metrics, or null if unavailable
     * @see CacheStats
     * @see CacheInfo
     * @see #extractCacheStats(NamedList)
     * @see #isCacheStatsEmpty(CacheStats)
     */
    public CacheStats getCacheMetrics(String collection) {
        try {
            // Get MBeans for cache information
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(STATS_PARAM, "true");
            params.set(CAT_PARAM, CACHE_CATEGORY);
            params.set(WT_PARAM, JSON_FORMAT);

            // Extract actual collection name from shard name if needed
            String actualCollection = extractCollectionName(collection);

            // Validate collection exists first
            if (!validateCollectionExists(actualCollection)) {
                return null; // Return null instead of empty object
            }

            String path = "/" + actualCollection + ADMIN_MBEANS_PATH;

            GenericSolrRequest request =
                    new GenericSolrRequest(SolrRequest.METHOD.GET, path, params);

            NamedList<Object> response = solrClient.request(request);
            CacheStats stats = extractCacheStats(response);

            // Return null if all cache stats are empty/null
            if (isCacheStatsEmpty(stats)) {
                return null;
            }

            return stats;
        } catch (SolrServerException | IOException e) {
            return null; // Return null instead of empty object
        }
    }

    /**
     * Checks if cache statistics are empty or contain no meaningful data.
     *
     * <p>Used to determine whether cache metrics are worth returning to clients. Empty cache stats
     * typically indicate that caches are not configured or not yet populated with data.
     *
     * @param stats the cache statistics to evaluate
     * @return true if the stats are null or all cache types are null
     */
    private boolean isCacheStatsEmpty(CacheStats stats) {
        return stats == null
                || (stats.queryResultCache() == null
                && stats.documentCache() == null
                && stats.filterCache() == null);
    }

    /**
     * Extracts cache performance statistics from Solr MBeans response data.
     *
     * <p>Parses the raw MBeans response to extract structured cache performance metrics for all
     * available cache types. Each cache type provides detailed statistics including hit ratios,
     * eviction rates, and current utilization.
     *
     * <p><strong>Parsed Cache Types:</strong>
     *
     * <ul>
     *   <li>queryResultCache - Complete query result caching
     *   <li>documentCache - Retrieved document data caching
     *   <li>filterCache - Filter query result caching
     * </ul>
     *
     * <p>For each cache type, the following metrics are extracted:
     *
     * <ul>
     *   <li>lookups, hits, hitratio - Performance effectiveness
     *   <li>inserts, evictions - Memory management patterns
     *   <li>size - Current utilization
     * </ul>
     *
     * @param mbeans the raw MBeans response from Solr admin endpoint
     * @return CacheStats object containing parsed metrics for all cache types
     * @see CacheStats
     * @see CacheInfo
     */
    private CacheStats extractCacheStats(NamedList<Object> mbeans) {
        CacheInfo queryResultCacheInfo = null;
        CacheInfo documentCacheInfo = null;
        CacheInfo filterCacheInfo = null;

        @SuppressWarnings("unchecked")
        NamedList<Object> caches = (NamedList<Object>) mbeans.get(CACHE_CATEGORY);

        if (caches != null) {
            // Query result cache
            @SuppressWarnings("unchecked")
            NamedList<Object> queryResultCache =
                    (NamedList<Object>) caches.get(QUERY_RESULT_CACHE_KEY);
            if (queryResultCache != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) queryResultCache.get(STATS_KEY);
                queryResultCacheInfo =
                        new CacheInfo(
                                getLong(stats, LOOKUPS_FIELD),
                                getLong(stats, HITS_FIELD),
                                getFloat(stats, HITRATIO_FIELD),
                                getLong(stats, INSERTS_FIELD),
                                getLong(stats, EVICTIONS_FIELD),
                                getLong(stats, SIZE_FIELD));
            }

            // Document cache
            @SuppressWarnings("unchecked")
            NamedList<Object> documentCache = (NamedList<Object>) caches.get(DOCUMENT_CACHE_KEY);
            if (documentCache != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) documentCache.get(STATS_KEY);
                documentCacheInfo =
                        new CacheInfo(
                                getLong(stats, LOOKUPS_FIELD),
                                getLong(stats, HITS_FIELD),
                                getFloat(stats, HITRATIO_FIELD),
                                getLong(stats, INSERTS_FIELD),
                                getLong(stats, EVICTIONS_FIELD),
                                getLong(stats, SIZE_FIELD));
            }

            // Filter cache
            @SuppressWarnings("unchecked")
            NamedList<Object> filterCache = (NamedList<Object>) caches.get(FILTER_CACHE_KEY);
            if (filterCache != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) filterCache.get(STATS_KEY);
                filterCacheInfo =
                        new CacheInfo(
                                getLong(stats, LOOKUPS_FIELD),
                                getLong(stats, HITS_FIELD),
                                getFloat(stats, HITRATIO_FIELD),
                                getLong(stats, INSERTS_FIELD),
                                getLong(stats, EVICTIONS_FIELD),
                                getLong(stats, SIZE_FIELD));
            }
        }

        return new CacheStats(queryResultCacheInfo, documentCacheInfo, filterCacheInfo);
    }

    /**
     * Retrieves request handler performance metrics for core Solr operations.
     *
     * <p>Collects detailed performance statistics for the primary request handlers that process
     * search and update operations. Handler metrics provide insights into system throughput, error
     * rates, and response time characteristics.
     *
     * <p><strong>Monitored Handlers:</strong>
     *
     * <ul>
     *   <li><strong>Select Handler ({@value #SELECT_HANDLER_PATH})</strong>: Processes search and
     *       query requests
     *   <li><strong>Update Handler ({@value #UPDATE_HANDLER_PATH})</strong>: Processes document
     *       indexing operations
     * </ul>
     *
     * <p><strong>Performance Metrics:</strong>
     *
     * <ul>
     *   <li><strong>Request Volume</strong>: Total requests processed
     *   <li><strong>Error Rates</strong>: Failed request counts and timeouts
     *   <li><strong>Performance</strong>: Average response times and throughput
     * </ul>
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>Returns {@code null} if handler statistics cannot be retrieved or if no meaningful handler
     * data is available. This allows graceful degradation when handler monitoring endpoints are not
     * accessible.
     *
     * @param collection the collection name to retrieve handler metrics for
     * @return HandlerStats object with performance metrics for all handlers, or null if unavailable
     * @see HandlerStats
     * @see HandlerInfo
     * @see #extractHandlerStats(NamedList)
     * @see #isHandlerStatsEmpty(HandlerStats)
     */
    public HandlerStats getHandlerMetrics(String collection) {
        try {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set(STATS_PARAM, "true");
            params.set(CAT_PARAM, HANDLER_CATEGORIES);
            params.set(WT_PARAM, JSON_FORMAT);

            // Extract actual collection name from shard name if needed
            String actualCollection = extractCollectionName(collection);

            // Validate collection exists first
            if (!validateCollectionExists(actualCollection)) {
                return null; // Return null instead of empty object
            }

            String path = "/" + actualCollection + ADMIN_MBEANS_PATH;

            GenericSolrRequest request =
                    new GenericSolrRequest(SolrRequest.METHOD.GET, path, params);

            NamedList<Object> response = solrClient.request(request);
            HandlerStats stats = extractHandlerStats(response);

            // Return null if all handler stats are empty/null
            if (isHandlerStatsEmpty(stats)) {
                return null;
            }

            return stats;
        } catch (SolrServerException | IOException e) {
            return null; // Return null instead of empty object
        }
    }

    /**
     * Checks if handler statistics are empty or contain no meaningful data.
     *
     * <p>Used to determine whether handler metrics are worth returning to clients. Empty handler
     * stats typically indicate that handlers haven't processed any requests yet or statistics
     * collection is not enabled.
     *
     * @param stats the handler statistics to evaluate
     * @return true if the stats are null or all handler types are null
     */
    private boolean isHandlerStatsEmpty(HandlerStats stats) {
        return stats == null || (stats.selectHandler() == null && stats.updateHandler() == null);
    }

    /**
     * Extracts request handler performance statistics from Solr MBeans response data.
     *
     * <p>Parses the raw MBeans response to extract structured handler performance metrics for query
     * and update operations. Each handler provides detailed statistics about request processing
     * including volume, errors, and timing.
     *
     * <p><strong>Parsed Handler Types:</strong>
     *
     * <ul>
     *   <li>/select - Search and query request handler
     *   <li>/update - Document indexing request handler
     * </ul>
     *
     * <p>For each handler type, the following metrics are extracted:
     *
     * <ul>
     *   <li>requests, errors, timeouts - Volume and reliability
     *   <li>totalTime, avgTimePerRequest - Performance characteristics
     *   <li>avgRequestsPerSecond - Throughput capacity
     * </ul>
     *
     * @param mbeans the raw MBeans response from Solr admin endpoint
     * @return HandlerStats object containing parsed metrics for all handler types
     * @see HandlerStats
     * @see HandlerInfo
     */
    private HandlerStats extractHandlerStats(NamedList<Object> mbeans) {
        HandlerInfo selectHandlerInfo = null;
        HandlerInfo updateHandlerInfo = null;

        @SuppressWarnings("unchecked")
        NamedList<Object> queryHandlers = (NamedList<Object>) mbeans.get(QUERY_HANDLER_CATEGORY);

        if (queryHandlers != null) {
            // Select handler
            @SuppressWarnings("unchecked")
            NamedList<Object> selectHandler =
                    (NamedList<Object>) queryHandlers.get(SELECT_HANDLER_PATH);
            if (selectHandler != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) selectHandler.get(STATS_KEY);
                selectHandlerInfo =
                        new HandlerInfo(
                                getLong(stats, REQUESTS_FIELD),
                                getLong(stats, ERRORS_FIELD),
                                getLong(stats, TIMEOUTS_FIELD),
                                getLong(stats, TOTAL_TIME_FIELD),
                                getFloat(stats, AVG_TIME_PER_REQUEST_FIELD),
                                getFloat(stats, AVG_REQUESTS_PER_SECOND_FIELD));
            }

            // Update handler
            @SuppressWarnings("unchecked")
            NamedList<Object> updateHandler =
                    (NamedList<Object>) queryHandlers.get(UPDATE_HANDLER_PATH);
            if (updateHandler != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) updateHandler.get(STATS_KEY);
                updateHandlerInfo =
                        new HandlerInfo(
                                getLong(stats, REQUESTS_FIELD),
                                getLong(stats, ERRORS_FIELD),
                                getLong(stats, TIMEOUTS_FIELD),
                                getLong(stats, TOTAL_TIME_FIELD),
                                getFloat(stats, AVG_TIME_PER_REQUEST_FIELD),
                                getFloat(stats, AVG_REQUESTS_PER_SECOND_FIELD));
            }
        }

        return new HandlerStats(selectHandlerInfo, updateHandlerInfo);
    }

    /**
     * Extracts the actual collection name from a shard name in SolrCloud environments.
     *
     * <p>In SolrCloud deployments, collection operations often return shard names that include
     * replica and shard identifiers (e.g., "films_shard1_replica_n1"). This method extracts the
     * base collection name ("films") for use in API calls that require the collection name.
     *
     * <p><strong>Extraction Logic:</strong>
     *
     * <ul>
     *   <li>Detects shard patterns containing the {@value #SHARD_SUFFIX} suffix
     *   <li>Returns the substring before the shard identifier
     *   <li>Returns the original string if no shard pattern is detected
     * </ul>
     *
     * <p><strong>Examples:</strong>
     *
     * <ul>
     *   <li>"films_shard1_replica_n1" → "films"
     *   <li>"products_shard2_replica_n3" → "products"
     *   <li>"simple_collection" → "simple_collection" (unchanged)
     * </ul>
     *
     * @param collectionOrShard the collection or shard name to parse
     * @return the extracted collection name, or the original string if no shard pattern found
     */
    String extractCollectionName(String collectionOrShard) {
        if (collectionOrShard == null || collectionOrShard.isEmpty()) {
            return collectionOrShard;
        }

        // Check if this looks like a shard name (contains "_shard" pattern)
        if (collectionOrShard.contains(SHARD_SUFFIX)) {
            // Extract collection name before "_shard"
            int shardIndex = collectionOrShard.indexOf(SHARD_SUFFIX);
            return collectionOrShard.substring(0, shardIndex);
        }

        // If it doesn't look like a shard name, return as-is
        return collectionOrShard;
    }

    /**
     * Validates that a specified collection exists in the Solr cluster.
     *
     * <p>Performs collection existence validation by checking against the list of available
     * collections. Supports both exact collection name matches and shard-based matching for
     * SolrCloud environments.
     *
     * <p><strong>Validation Strategy:</strong>
     *
     * <ol>
     *   <li><strong>Exact Match</strong>: Checks if the collection name exists exactly
     *   <li><strong>Shard Match</strong>: Checks if any shards start with "collection{@value
     *       #SHARD_SUFFIX}" pattern
     * </ol>
     *
     * <p>This dual approach ensures compatibility with both standalone Solr (which returns core
     * names directly) and SolrCloud (which may return shard names).
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>Returns {@code false} if validation fails due to communication errors, allowing calling
     * methods to handle missing collections appropriately.
     *
     * @param collection the collection name to validate
     * @return true if the collection exists (either exact or shard match), false otherwise
     * @see #listCollections()
     * @see #extractCollectionName(String)
     */
    private boolean validateCollectionExists(String collection) {
        try {
            List<String> collections = listCollections();

            // Check for exact match first
            if (collections.contains(collection)) {
                return true;
            }

            // Check if any of the returned collections start with the collection name (for shard
            // names)
            boolean shardMatch =
                    collections.stream().anyMatch(c -> c.startsWith(collection + SHARD_SUFFIX));

            return shardMatch;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Performs a comprehensive health check on a Solr collection.
     *
     * <p>Evaluates collection availability and performance by executing a ping operation and basic
     * query to gather health indicators. This method provides a quick way to determine if a
     * collection is operational and responding to requests.
     *
     * <p><strong>Health Check Components:</strong>
     *
     * <ul>
     *   <li><strong>Availability</strong>: Collection responds to ping requests
     *   <li><strong>Performance</strong>: Response time measurement
     *   <li><strong>Content</strong>: Document count verification using universal query ({@value
     *       #ALL_DOCUMENTS_QUERY})
     *   <li><strong>Timestamp</strong>: When the check was performed
     * </ul>
     *
     * <p><strong>Success Criteria:</strong>
     *
     * <p>A collection is considered healthy if both the ping operation and a basic query complete
     * successfully without exceptions. Performance metrics are collected during the health check
     * process.
     *
     * <p><strong>Failure Handling:</strong>
     *
     * <p>If the health check fails, a status object is returned with {@code isHealthy=false} and
     * the error message describing the failure reason. This allows monitoring systems to identify
     * specific issues.
     *
     * <p><strong>MCP Tool Usage:</strong>
     *
     * <p>Exposed as an MCP tool for natural language health queries like "check if my_collection is
     * healthy" or "is the search index working properly".
     *
     * @param collection the name of the collection to health check
     * @return SolrHealthStatus object containing health assessment results
     * @see SolrHealthStatus
     * @see SolrPingResponse
     */
    @McpTool(description = "Check health of a Solr collection")
    public SolrHealthStatus checkHealth(
            @McpToolParam(description = "Solr collection") String collection) {
        try {
            // Ping Solr
            SolrPingResponse pingResponse = solrClient.ping(collection);

            // Get basic stats
            QueryResponse statsResponse =
                    solrClient.query(collection, new SolrQuery(ALL_DOCUMENTS_QUERY).setRows(0));

            return new SolrHealthStatus(
                    true,
                    null,
                    pingResponse.getElapsedTime(),
                    statsResponse.getResults().getNumFound(),
                    new Date(),
                    null,
                    null,
                    null);

        } catch (Exception e) {
            return new SolrHealthStatus(
                    false, e.getMessage(), null, null, new Date(), null, null, null);
        }
    }
}
