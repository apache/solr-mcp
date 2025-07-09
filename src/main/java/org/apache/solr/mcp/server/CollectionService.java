package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.response.*;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.apache.solr.client.solrj.SolrServerException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.apache.solr.mcp.server.Utils.*;

/**
 * Spring Service providing comprehensive Solr collection management and monitoring capabilities
 * for Model Context Protocol (MCP) clients.
 * 
 * <p>This service acts as the primary interface for collection-level operations in the Solr MCP Server,
 * providing tools for collection discovery, metrics gathering, health monitoring, and performance analysis.
 * It bridges the gap between MCP clients (like Claude Desktop) and Apache Solr through the SolrJ client library.</p>
 * 
 * <p><strong>Core Capabilities:</strong></p>
 * <ul>
 *   <li><strong>Collection Discovery</strong>: Lists available collections/cores with automatic SolrCloud vs standalone detection</li>
 *   <li><strong>Performance Monitoring</strong>: Comprehensive metrics collection including index, query, cache, and handler statistics</li>
 *   <li><strong>Health Monitoring</strong>: Real-time health checks with availability and performance indicators</li>
 *   <li><strong>Shard-Aware Operations</strong>: Intelligent handling of SolrCloud shard names and collection name extraction</li>
 * </ul>
 * 
 * <p><strong>MCP Tool Integration:</strong></p>
 * <p>Methods annotated with {@code @Tool} are automatically exposed as MCP tools that can be invoked
 * by AI clients. These tools provide natural language interfaces to Solr operations.</p>
 * 
 * <p><strong>Supported Solr Deployments:</strong></p>
 * <ul>
 *   <li><strong>SolrCloud</strong>: Distributed mode using Collections API</li>
 *   <li><strong>Standalone</strong>: Single-node mode using Core Admin API</li>
 * </ul>
 * 
 * <p><strong>Error Handling:</strong></p>
 * <p>The service implements robust error handling with graceful degradation. Failed operations
 * return null values rather than throwing exceptions (except where validation requires it),
 * allowing partial metrics collection when some endpoints are unavailable.</p>
 * 
 * <p><strong>Example Usage:</strong></p>
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
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 * 
 * @see SolrMetrics
 * @see SolrHealthStatus
 * @see org.apache.solr.client.solrj.SolrClient
 */
@Service
public class CollectionService {

    private static final String CACHE_CATEGORY = "CACHE";
    private static final String QUERY_HANDLER_CATEGORY = "QUERYHANDLER";
    private static final String UPDATE_HANDLER_CATEGORY = "UPDATEHANDLER";
    private static final String HANDLER_CATEGORIES = "QUERYHANDLER,UPDATEHANDLER";

    /** SolrJ client for communicating with Solr server */
    private final SolrClient solrClient;

    /** Solr configuration properties */
    private final SolrConfigurationProperties solrConfigurationProperties;

    /**
     * Constructs a new CollectionService with the required dependencies.
     * 
     * <p>This constructor is automatically called by Spring's dependency injection
     * framework during application startup.</p>
     * 
     * @param solrClient the SolrJ client instance for communicating with Solr
     * @param solrConfigurationProperties configuration properties for Solr connection
     * 
     * @see SolrClient
     * @see SolrConfigurationProperties
     */
    public CollectionService(SolrClient solrClient,
                             SolrConfigurationProperties solrConfigurationProperties) {
        this.solrClient = solrClient;
        this.solrConfigurationProperties = solrConfigurationProperties;
    }

    /**
     * Lists all available Solr collections or cores in the cluster.
     * 
     * <p>This method automatically detects the Solr deployment type and uses the appropriate API:</p>
     * <ul>
     *   <li><strong>SolrCloud</strong>: Uses Collections API to list distributed collections</li>
     *   <li><strong>Standalone</strong>: Uses Core Admin API to list individual cores</li>
     * </ul>
     * 
     * <p>In SolrCloud environments, the returned names may include shard identifiers
     * (e.g., "films_shard1_replica_n1"). Use {@link #extractCollectionName(String)}
     * to get the base collection name if needed.</p>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>If the operation fails due to connectivity issues or API errors, an empty list
     * is returned rather than throwing an exception, allowing the application to continue
     * functioning with degraded capabilities.</p>
     * 
     * <p><strong>MCP Tool Usage:</strong></p>
     * <p>This method is exposed as an MCP tool and can be invoked by AI clients with
     * natural language requests like "list all collections" or "show me available databases".</p>
     * 
     * @return a list of collection/core names, or an empty list if unable to retrieve them
     * 
     * @see CollectionAdminRequest.List
     * @see CoreAdminRequest
     */
    @Tool(description = "List solr collections")
    public List<String> listCollections() {
        try {
            if (solrClient instanceof CloudSolrClient) {
                // For SolrCloud - use Collections API
                CollectionAdminRequest.List request = new CollectionAdminRequest.List();
                CollectionAdminResponse response = request.process(solrClient);

                @SuppressWarnings("unchecked")
                List<String> collections = (List<String>) response.getResponse().get("collections");
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
     * performance profile including index health, query performance, cache utilization,
     * and request handler statistics.</p>
     * 
     * <p><strong>Collected Metrics:</strong></p>
     * <ul>
     *   <li><strong>Index Statistics</strong>: Document counts, segment information (via Luke handler)</li>
     *   <li><strong>Query Performance</strong>: Response times, result counts, relevance scores</li>
     *   <li><strong>Cache Utilization</strong>: Hit ratios, eviction rates for all cache types</li>
     *   <li><strong>Handler Performance</strong>: Request volumes, error rates, throughput metrics</li>
     * </ul>
     * 
     * <p><strong>Collection Name Handling:</strong></p>
     * <p>Supports both collection names and shard names. If a shard name like
     * "films_shard1_replica_n1" is provided, it will be automatically converted
     * to the base collection name "films" for API calls.</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <p>The method validates that the specified collection exists before attempting
     * to collect metrics. If the collection is not found, an {@code IllegalArgumentException}
     * is thrown with a descriptive error message.</p>
     * 
     * <p><strong>MCP Tool Usage:</strong></p>
     * <p>Exposed as an MCP tool for natural language queries like "get metrics for my_collection"
     * or "show me performance stats for the search index".</p>
     * 
     * @param collection the name of the collection to analyze (supports both collection and shard names)
     * @return comprehensive metrics object containing all collected statistics
     * 
     * @throws IllegalArgumentException if the specified collection does not exist
     * @throws Exception if there are errors communicating with Solr or processing responses
     * 
     * @see SolrMetrics
     * @see LukeRequest
     * @see #extractCollectionName(String)
     * @see #validateCollectionExists(String)
     */
    @Tool(description = "Get stats/metrics on a Solr collection")
    public SolrMetrics getCollectionStats(String collection) throws Exception {
        // Extract actual collection name from shard name if needed
        String actualCollection = extractCollectionName(collection);

        // Validate collection exists
        if (!validateCollectionExists(actualCollection)) {
            throw new IllegalArgumentException("Collection not found: " + actualCollection);
        }

        // Index statistics using Luke
        LukeRequest lukeRequest = new LukeRequest();
        lukeRequest.setIncludeIndexFieldFlags(true);
        LukeResponse lukeResponse = lukeRequest.process(solrClient, actualCollection);

        // Query performance metrics
        QueryResponse statsResponse = solrClient.query(actualCollection,
                new SolrQuery("*:*").setRows(0));

        return SolrMetrics.builder()
                .indexStats(buildIndexStats(lukeResponse))
                .queryStats(buildQueryStats(statsResponse))
                .cacheStats(getCacheMetrics(actualCollection))
                .handlerStats(getHandlerMetrics(actualCollection))
                .timestamp(new Date())
                .build();
    }

    /**
     * Builds an IndexStats object from a Solr Luke response containing index metadata.
     * 
     * <p>The Luke handler provides low-level Lucene index information including document
     * counts, segment details, and field statistics. This method extracts the essential
     * index health metrics for monitoring and analysis.</p>
     * 
     * <p><strong>Extracted Metrics:</strong></p>
     * <ul>
     *   <li><strong>numDocs</strong>: Total number of documents excluding deleted ones</li>
     *   <li><strong>segmentCount</strong>: Number of Lucene segments (performance indicator)</li>
     * </ul>
     * 
     * <p><strong>Performance Implications:</strong></p>
     * <p>High segment counts may indicate the need for index optimization to improve
     * search performance. The optimal segment count depends on index size and update frequency.</p>
     * 
     * @param lukeResponse the Luke response containing raw index information
     * @return IndexStats object with extracted and formatted metrics
     * 
     * @see IndexStats
     * @see LukeResponse
     */
    public IndexStats buildIndexStats(LukeResponse lukeResponse) {
        NamedList<Object> indexInfo = lukeResponse.getIndexInfo();

        // Extract index information using helper methods
        Integer segmentCount = getInteger(indexInfo, "segmentCount");

        return IndexStats.builder()
                .numDocs(lukeResponse.getNumDocs())
                .segmentCount(segmentCount)
                .build();
    }

    /**
     * Builds a QueryStats object from a Solr query response containing performance metrics.
     * 
     * <p>Extracts key performance indicators from a query execution including timing,
     * result characteristics, and relevance scoring information. These metrics help
     * identify query performance patterns and optimization opportunities.</p>
     * 
     * <p><strong>Extracted Metrics:</strong></p>
     * <ul>
     *   <li><strong>queryTime</strong>: Execution time in milliseconds</li>
     *   <li><strong>totalResults</strong>: Total matching documents found</li>
     *   <li><strong>start</strong>: Pagination offset (0-based)</li>
     *   <li><strong>maxScore</strong>: Highest relevance score in results</li>
     * </ul>
     * 
     * <p><strong>Performance Analysis:</strong></p>
     * <p>Query time metrics help identify slow queries that may need optimization,
     * while result counts and scores provide insight into search effectiveness.</p>
     * 
     * @param response the query response containing performance and result metadata
     * @return QueryStats object with extracted performance metrics
     * 
     * @see QueryStats
     * @see QueryResponse
     */
    public QueryStats buildQueryStats(QueryResponse response) {

        return QueryStats.builder()
                .queryTime(response.getQTime())
                .totalResults(response.getResults().getNumFound())
                .start(response.getResults().getStart())
                .maxScore(response.getResults().getMaxScore())
                .build();
    }

    /**
     * Retrieves cache performance metrics for all cache types in a Solr collection.
     * 
     * <p>Collects detailed cache utilization statistics from Solr's MBeans endpoint,
     * providing insights into cache effectiveness and memory usage patterns. Cache
     * performance directly impacts query response times and system efficiency.</p>
     * 
     * <p><strong>Monitored Cache Types:</strong></p>
     * <ul>
     *   <li><strong>Query Result Cache</strong>: Caches complete query results for identical searches</li>
     *   <li><strong>Document Cache</strong>: Caches retrieved document field data</li>
     *   <li><strong>Filter Cache</strong>: Caches filter query results for faceting and filtering</li>
     * </ul>
     * 
     * <p><strong>Key Performance Indicators:</strong></p>
     * <ul>
     *   <li><strong>Hit Ratio</strong>: Cache effectiveness (higher is better)</li>
     *   <li><strong>Evictions</strong>: Memory pressure indicator</li>
     *   <li><strong>Size</strong>: Current cache utilization</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Returns {@code null} if cache statistics cannot be retrieved or if all
     * cache types are empty/unavailable. This allows graceful degradation when
     * cache monitoring is not available.</p>
     * 
     * @param collection the collection name to retrieve cache metrics for
     * @return CacheStats object with all cache performance metrics, or null if unavailable
     * 
     * @throws Exception if there are communication errors with Solr (handled gracefully)
     * 
     * @see CacheStats
     * @see CacheInfo
     * @see #extractCacheStats(NamedList)
     * @see #isCacheStatsEmpty(CacheStats)
     */
    public CacheStats getCacheMetrics(String collection) throws Exception {
        try {
            // Get MBeans for cache information
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("stats", "true");
            params.set("cat", CACHE_CATEGORY);
            params.set("wt", "json");

            // Extract actual collection name from shard name if needed
            String actualCollection = extractCollectionName(collection);

            // Validate collection exists first
            if (!validateCollectionExists(actualCollection)) {
                return null; // Return null instead of empty object
            }

            String path = "/" + actualCollection + "/admin/mbeans";

            GenericSolrRequest request = new GenericSolrRequest(
                    SolrRequest.METHOD.GET,
                    path,
                    params
            );

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
     * <p>Used to determine whether cache metrics are worth returning to clients.
     * Empty cache stats typically indicate that caches are not configured or
     * not yet populated with data.</p>
     * 
     * @param stats the cache statistics to evaluate
     * @return true if the stats are null or all cache types are null
     */
    private boolean isCacheStatsEmpty(CacheStats stats) {
        return stats == null || 
               (stats.getQueryResultCache() == null && 
                stats.getDocumentCache() == null && 
                stats.getFilterCache() == null);
    }

    /**
     * Extracts cache performance statistics from Solr MBeans response data.
     * 
     * <p>Parses the raw MBeans response to extract structured cache performance
     * metrics for all available cache types. Each cache type provides detailed
     * statistics including hit ratios, eviction rates, and current utilization.</p>
     * 
     * <p><strong>Parsed Cache Types:</strong></p>
     * <ul>
     *   <li>queryResultCache - Complete query result caching</li>
     *   <li>documentCache - Retrieved document data caching</li>
     *   <li>filterCache - Filter query result caching</li>
     * </ul>
     * 
     * <p>For each cache type, the following metrics are extracted:</p>
     * <ul>
     *   <li>lookups, hits, hitratio - Performance effectiveness</li>
     *   <li>inserts, evictions - Memory management patterns</li>
     *   <li>size - Current utilization</li>
     * </ul>
     * 
     * @param mbeans the raw MBeans response from Solr admin endpoint
     * @return CacheStats object containing parsed metrics for all cache types
     * 
     * @see CacheStats
     * @see CacheInfo
     */
    private CacheStats extractCacheStats(NamedList<Object> mbeans) {
        CacheStats.CacheStatsBuilder builder = CacheStats.builder();

        @SuppressWarnings("unchecked")
        NamedList<Object> caches = (NamedList<Object>) mbeans.get(CACHE_CATEGORY);

        if (caches != null) {
            // Query result cache
            @SuppressWarnings("unchecked")
            NamedList<Object> queryResultCache = (NamedList<Object>) caches.get("queryResultCache");
            if (queryResultCache != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) queryResultCache.get("stats");
                builder.queryResultCache(CacheInfo.builder()
                        .lookups(getLong(stats, "lookups"))
                        .hits(getLong(stats, "hits"))
                        .hitratio(getFloat(stats, "hitratio"))
                        .inserts(getLong(stats, "inserts"))
                        .evictions(getLong(stats, "evictions"))
                        .size(getLong(stats, "size"))
                        .build());
            }

            // Document cache
            @SuppressWarnings("unchecked")
            NamedList<Object> documentCache = (NamedList<Object>) caches.get("documentCache");
            if (documentCache != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) documentCache.get("stats");
                builder.documentCache(CacheInfo.builder()
                        .lookups(getLong(stats, "lookups"))
                        .hits(getLong(stats, "hits"))
                        .hitratio(getFloat(stats, "hitratio"))
                        .inserts(getLong(stats, "inserts"))
                        .evictions(getLong(stats, "evictions"))
                        .size(getLong(stats, "size"))
                        .build());
            }

            // Filter cache
            @SuppressWarnings("unchecked")
            NamedList<Object> filterCache = (NamedList<Object>) caches.get("filterCache");
            if (filterCache != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) filterCache.get("stats");
                builder.filterCache(CacheInfo.builder()
                        .lookups(getLong(stats, "lookups"))
                        .hits(getLong(stats, "hits"))
                        .hitratio(getFloat(stats, "hitratio"))
                        .inserts(getLong(stats, "inserts"))
                        .evictions(getLong(stats, "evictions"))
                        .size(getLong(stats, "size"))
                        .build());
            }
        }

        return builder.build();
    }

    /**
     * Retrieves request handler performance metrics for core Solr operations.
     * 
     * <p>Collects detailed performance statistics for the primary request handlers
     * that process search and update operations. Handler metrics provide insights
     * into system throughput, error rates, and response time characteristics.</p>
     * 
     * <p><strong>Monitored Handlers:</strong></p>
     * <ul>
     *   <li><strong>Select Handler (/select)</strong>: Processes search and query requests</li>
     *   <li><strong>Update Handler (/update)</strong>: Processes document indexing operations</li>
     * </ul>
     * 
     * <p><strong>Performance Metrics:</strong></p>
     * <ul>
     *   <li><strong>Request Volume</strong>: Total requests processed</li>
     *   <li><strong>Error Rates</strong>: Failed request counts and timeouts</li>
     *   <li><strong>Performance</strong>: Average response times and throughput</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Returns {@code null} if handler statistics cannot be retrieved or if
     * no meaningful handler data is available. This allows graceful degradation
     * when handler monitoring endpoints are not accessible.</p>
     * 
     * @param collection the collection name to retrieve handler metrics for
     * @return HandlerStats object with performance metrics for all handlers, or null if unavailable
     * 
     * @throws Exception if there are communication errors with Solr (handled gracefully)
     * 
     * @see HandlerStats
     * @see HandlerInfo
     * @see #extractHandlerStats(NamedList)
     * @see #isHandlerStatsEmpty(HandlerStats)
     */
    public HandlerStats getHandlerMetrics(String collection) throws Exception {
        try {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("stats", "true");
            params.set("cat", HANDLER_CATEGORIES);
            params.set("wt", "json");

            // Extract actual collection name from shard name if needed
            String actualCollection = extractCollectionName(collection);

            // Validate collection exists first
            if (!validateCollectionExists(actualCollection)) {
                return null; // Return null instead of empty object
            }

            String path = "/" + actualCollection + "/admin/mbeans";

            GenericSolrRequest request = new GenericSolrRequest(
                    SolrRequest.METHOD.GET,
                    path,
                    params
            );

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
     * <p>Used to determine whether handler metrics are worth returning to clients.
     * Empty handler stats typically indicate that handlers haven't processed any
     * requests yet or statistics collection is not enabled.</p>
     * 
     * @param stats the handler statistics to evaluate
     * @return true if the stats are null or all handler types are null
     */
    private boolean isHandlerStatsEmpty(HandlerStats stats) {
        return stats == null || 
               (stats.getSelectHandler() == null && stats.getUpdateHandler() == null);
    }

    /**
     * Extracts request handler performance statistics from Solr MBeans response data.
     * 
     * <p>Parses the raw MBeans response to extract structured handler performance
     * metrics for query and update operations. Each handler provides detailed
     * statistics about request processing including volume, errors, and timing.</p>
     * 
     * <p><strong>Parsed Handler Types:</strong></p>
     * <ul>
     *   <li>/select - Search and query request handler</li>
     *   <li>/update - Document indexing request handler</li>
     * </ul>
     * 
     * <p>For each handler type, the following metrics are extracted:</p>
     * <ul>
     *   <li>requests, errors, timeouts - Volume and reliability</li>
     *   <li>totalTime, avgTimePerRequest - Performance characteristics</li>
     *   <li>avgRequestsPerSecond - Throughput capacity</li>
     * </ul>
     * 
     * @param mbeans the raw MBeans response from Solr admin endpoint
     * @return HandlerStats object containing parsed metrics for all handler types
     * 
     * @see HandlerStats
     * @see HandlerInfo
     */
    private HandlerStats extractHandlerStats(NamedList<Object> mbeans) {
        HandlerStats.HandlerStatsBuilder builder = HandlerStats.builder();

        @SuppressWarnings("unchecked")
        NamedList<Object> queryHandlers = (NamedList<Object>) mbeans.get(QUERY_HANDLER_CATEGORY);

        if (queryHandlers != null) {
            // Select handler
            @SuppressWarnings("unchecked")
            NamedList<Object> selectHandler = (NamedList<Object>) queryHandlers.get("/select");
            if (selectHandler != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) selectHandler.get("stats");
                builder.selectHandler(HandlerInfo.builder()
                        .requests(getLong(stats, "requests"))
                        .errors(getLong(stats, "errors"))
                        .timeouts(getLong(stats, "timeouts"))
                        .totalTime(getLong(stats, "totalTime"))
                        .avgTimePerRequest(getFloat(stats, "avgTimePerRequest"))
                        .avgRequestsPerSecond(getFloat(stats, "avgRequestsPerSecond"))
                        .build());
            }

            // Update handler
            @SuppressWarnings("unchecked")
            NamedList<Object> updateHandler = (NamedList<Object>) queryHandlers.get("/update");
            if (updateHandler != null) {
                @SuppressWarnings("unchecked")
                NamedList<Object> stats = (NamedList<Object>) updateHandler.get("stats");
                builder.updateHandler(HandlerInfo.builder()
                        .requests(getLong(stats, "requests"))
                        .errors(getLong(stats, "errors"))
                        .timeouts(getLong(stats, "timeouts"))
                        .totalTime(getLong(stats, "totalTime"))
                        .avgTimePerRequest(getFloat(stats, "avgTimePerRequest"))
                        .avgRequestsPerSecond(getFloat(stats, "avgRequestsPerSecond"))
                        .build());
            }
        }

        return builder.build();
    }


    /**
     * Extracts the actual collection name from a shard name in SolrCloud environments.
     * 
     * <p>In SolrCloud deployments, collection operations often return shard names that include
     * replica and shard identifiers (e.g., "films_shard1_replica_n1"). This method extracts
     * the base collection name ("films") for use in API calls that require the collection name.</p>
     * 
     * <p><strong>Extraction Logic:</strong></p>
     * <ul>
     *   <li>Detects shard patterns containing "_shard" suffix</li>
     *   <li>Returns the substring before the "_shard" identifier</li>
     *   <li>Returns the original string if no shard pattern is detected</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>"films_shard1_replica_n1" → "films"</li>
     *   <li>"products_shard2_replica_n3" → "products"</li>
     *   <li>"simple_collection" → "simple_collection" (unchanged)</li>
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
        if (collectionOrShard.contains("_shard")) {
            // Extract collection name before "_shard"
            int shardIndex = collectionOrShard.indexOf("_shard");
            return collectionOrShard.substring(0, shardIndex);
        }

        // If it doesn't look like a shard name, return as-is
        return collectionOrShard;
    }

    /**
     * Validates that a specified collection exists in the Solr cluster.
     * 
     * <p>Performs collection existence validation by checking against the list of
     * available collections. Supports both exact collection name matches and
     * shard-based matching for SolrCloud environments.</p>
     * 
     * <p><strong>Validation Strategy:</strong></p>
     * <ol>
     *   <li><strong>Exact Match</strong>: Checks if the collection name exists exactly</li>
     *   <li><strong>Shard Match</strong>: Checks if any shards start with "collection_shard" pattern</li>
     * </ol>
     * 
     * <p>This dual approach ensures compatibility with both standalone Solr
     * (which returns core names directly) and SolrCloud (which may return shard names).</p>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Returns {@code false} if validation fails due to communication errors,
     * allowing calling methods to handle missing collections appropriately.</p>
     * 
     * @param collection the collection name to validate
     * @return true if the collection exists (either exact or shard match), false otherwise
     * 
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

            // Check if any of the returned collections start with the collection name (for shard names)
            boolean shardMatch = collections.stream()
                    .anyMatch(c -> c.startsWith(collection + "_shard"));

            return shardMatch;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Performs a comprehensive health check on a Solr collection.
     * 
     * <p>Evaluates collection availability and performance by executing a ping operation
     * and basic query to gather health indicators. This method provides a quick way to
     * determine if a collection is operational and responding to requests.</p>
     * 
     * <p><strong>Health Check Components:</strong></p>
     * <ul>
     *   <li><strong>Availability</strong>: Collection responds to ping requests</li>
     *   <li><strong>Performance</strong>: Response time measurement</li>
     *   <li><strong>Content</strong>: Document count verification</li>
     *   <li><strong>Timestamp</strong>: When the check was performed</li>
     * </ul>
     * 
     * <p><strong>Success Criteria:</strong></p>
     * <p>A collection is considered healthy if both the ping operation and a basic
     * query complete successfully without exceptions. Performance metrics are collected
     * during the health check process.</p>
     * 
     * <p><strong>Failure Handling:</strong></p>
     * <p>If the health check fails, a status object is returned with {@code isHealthy=false}
     * and the error message describing the failure reason. This allows monitoring
     * systems to identify specific issues.</p>
     * 
     * <p><strong>MCP Tool Usage:</strong></p>
     * <p>Exposed as an MCP tool for natural language health queries like
     * "check if my_collection is healthy" or "is the search index working properly".</p>
     * 
     * @param collection the name of the collection to health check
     * @return SolrHealthStatus object containing health assessment results
     * 
     * @see SolrHealthStatus
     * @see SolrPingResponse
     */
    @Tool(description = "Check health of a Solr collection")
    public SolrHealthStatus checkHealth(String collection) {
        try {
            // Ping Solr
            SolrPingResponse pingResponse = solrClient.ping(collection);

            // Get basic stats
            QueryResponse statsResponse = solrClient.query(collection,
                    new SolrQuery("*:*").setRows(0));

            return SolrHealthStatus.builder()
                    .isHealthy(true)
                    .responseTime(pingResponse.getElapsedTime())
                    .totalDocuments(statsResponse.getResults().getNumFound())
                    .lastChecked(new Date())
                    .build();

        } catch (Exception e) {
            return SolrHealthStatus.builder()
                    .isHealthy(false)
                    .errorMessage(e.getMessage())
                    .lastChecked(new Date())
                    .build();
        }
    }

}
