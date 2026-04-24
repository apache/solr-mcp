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
package org.apache.solr.mcp.server.collection;

import static org.apache.solr.mcp.server.collection.CollectionUtils.getFloat;
import static org.apache.solr.mcp.server.collection.CollectionUtils.getInteger;
import static org.apache.solr.mcp.server.collection.CollectionUtils.getLong;
import static org.apache.solr.mcp.server.util.JsonUtils.toJson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.config.SolrConfigurationProperties;
import org.springaicommunity.mcp.annotation.McpComplete;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Spring Service providing comprehensive Solr collection management and
 * monitoring capabilities for Model Context Protocol (MCP) clients.
 *
 * <p>
 * This service acts as the primary interface for collection-level operations in
 * the Solr MCP Server, providing tools for collection discovery, metrics
 * gathering, health monitoring, and performance analysis. It bridges the gap
 * between MCP clients (like Claude Desktop) and Apache Solr through the SolrJ
 * client library.
 *
 * <p>
 * <strong>Core Capabilities:</strong>
 *
 * <ul>
 * <li><strong>Collection Discovery</strong>: Lists available collections using
 * the SolrCloud Collections API
 * <li><strong>Performance Monitoring</strong>: Comprehensive metrics collection
 * including index, query, cache, and handler statistics
 * <li><strong>Health Monitoring</strong>: Real-time health checks with
 * availability and performance indicators
 * <li><strong>Shard-Aware Operations</strong>: Intelligent handling of
 * SolrCloud shard names and collection name extraction
 * </ul>
 *
 * <p>
 * <strong>Implementation Details:</strong>
 *
 * <p>
 * This class uses extensively documented constants for all API parameters,
 * field names, and paths to ensure maintainability and reduce the risk of
 * typos. All string literals have been replaced with well-named constants that
 * are organized by category (API parameters, response parsing keys, handler
 * paths, statistics fields, etc.).
 *
 * <p>
 * <strong>MCP Tool Integration:</strong>
 *
 * <p>
 * Methods annotated with {@code @McpTool} are automatically exposed as MCP
 * tools that can be invoked by AI clients. These tools provide natural language
 * interfaces to Solr operations.
 *
 * <p>
 * <strong>Supported Solr Deployments:</strong>
 *
 * <ul>
 * <li><strong>SolrCloud</strong>: Distributed mode using Collections API
 * </ul>
 *
 * <p>
 * <strong>Error Handling:</strong>
 *
 * <p>
 * The service implements robust error handling with graceful degradation.
 * Failed operations return null values rather than throwing exceptions (except
 * where validation requires it), allowing partial metrics collection when some
 * endpoints are unavailable.
 *
 * <p>
 * <strong>Example Usage:</strong>
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
 * @see SolrMetrics
 * @see SolrHealthStatus
 * @see org.apache.solr.client.solrj.SolrClient
 */
@Service
@Observed
public class CollectionService {

	// ========================================
	// Constants for API Parameters and Paths
	// ========================================

	/** Universal Solr query pattern to match all documents in a collection */
	private static final String ALL_DOCUMENTS_QUERY = "*:*";

	/** Suffix pattern used to identify shard names in SolrCloud deployments */
	private static final String SHARD_SUFFIX = "_shard";

	/** Request parameter name for specifying response writer type */
	private static final String WT_PARAM = "wt";

	/** JSON format specification for response writer type */
	private static final String JSON_FORMAT = "json";

	/** URL path for Solr Metrics admin endpoint */
	private static final String ADMIN_METRICS_PATH = "/admin/metrics";

	/** Request parameter name for specifying the metrics group */
	private static final String GROUP_PARAM = "group";

	/** Request parameter name for filtering metrics by key prefix */
	private static final String PREFIX_PARAM = "prefix";

	/** Metrics group for core-level metrics */
	private static final String CORE_GROUP = "core";

	/** Prefix for cache metrics in the Metrics API */
	private static final String CACHE_METRIC_PREFIX = "CACHE.searcher";

	/** Prefix for select handler metrics in the Metrics API */
	private static final String SELECT_HANDLER_METRIC_PREFIX = "QUERY./select";

	/** Prefix for update handler metrics in the Metrics API */
	private static final String UPDATE_HANDLER_METRIC_PREFIX = "UPDATE./update";

	// ========================================
	// Constants for Response Parsing
	// ========================================

	/** Key name for collections list in Collections API responses */
	private static final String COLLECTIONS_KEY = "collections";

	/** Key name for segment count information in Luke response */
	private static final String SEGMENT_COUNT_KEY = "segmentCount";

	/** Top-level key in Metrics API responses */
	private static final String METRICS_KEY = "metrics";

	/** Metrics API key for query result cache */
	private static final String QUERY_RESULT_CACHE_KEY = "CACHE.searcher.queryResultCache";

	/** Metrics API key for document cache */
	private static final String DOCUMENT_CACHE_KEY = "CACHE.searcher.documentCache";

	/** Metrics API key for filter cache */
	private static final String FILTER_CACHE_KEY = "CACHE.searcher.filterCache";

	/** Flat metric key prefix for select handler stats */
	private static final String SELECT_HANDLER_KEY = "QUERY./select.";

	/** Flat metric key prefix for update handler stats */
	private static final String UPDATE_HANDLER_KEY = "UPDATE./update.";

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

	// ========================================
	// Constants for Error Messages
	// ========================================

	/** Error message prefix for collection not found exceptions */
	private static final String COLLECTION_NOT_FOUND_ERROR = "Collection not found: ";

	/** Default configset name used when none is specified */
	private static final String DEFAULT_CONFIGSET = "_default";

	/** Default number of shards for new collections */
	private static final int DEFAULT_NUM_SHARDS = 1;

	/** Default replication factor for new collections */
	private static final int DEFAULT_REPLICATION_FACTOR = 1;

	/** Error message for blank collection name validation */
	private static final String BLANK_COLLECTION_NAME_ERROR = "Collection name must not be blank";

	/** SolrJ client for communicating with Solr server */
	private final SolrClient solrClient;

	private final ObjectMapper objectMapper;

	/**
	 * Constructs a new CollectionService with the required dependencies.
	 *
	 * <p>
	 * This constructor is automatically called by Spring's dependency injection
	 * framework during application startup.
	 *
	 * @param solrClient
	 *            the SolrJ client instance for communicating with Solr
	 * @param objectMapper
	 *            the Jackson ObjectMapper for JSON serialization
	 * @see SolrClient
	 * @see SolrConfigurationProperties
	 */
	public CollectionService(SolrClient solrClient, ObjectMapper objectMapper) {
		this.solrClient = solrClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * MCP Resource endpoint that returns a list of all available Solr collections.
	 *
	 * <p>
	 * This resource provides a simple way for MCP clients to discover what
	 * collections are available in the Solr cluster. The returned JSON contains an
	 * array of collection names.
	 *
	 * @return JSON string containing the list of collections
	 */
	@McpResource(uri = "solr://collections", name = "solr-collections", description = "List of all Solr collections available in the cluster", mimeType = "application/json")
	public String getCollectionsResource() {
		return toJson(objectMapper, listCollections());
	}

	/**
	 * MCP Completion endpoint for collection name autocompletion.
	 *
	 * <p>
	 * Provides autocompletion support for the collection parameter in the schema
	 * resource URI template. Returns all available collection names that MCP
	 * clients can use to complete the {collection} placeholder.
	 *
	 * @return list of available collection names for autocompletion
	 */
	@McpComplete(uri = "solr://{collection}/schema")
	public List<String> completeCollectionForSchema() {
		return listCollections();
	}

	/**
	 * Lists all available Solr collections in the SolrCloud cluster.
	 *
	 * <p>
	 * This method uses the SolrCloud Collections API to retrieve the list of
	 * available collections. Standalone Solr instances are not supported.
	 *
	 * <p>
	 * The returned names may include shard identifiers (e.g.,
	 * "films_shard1_replica_n1"). Use {@link #extractCollectionName(String)} to get
	 * the base collection name if needed.
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * If the operation fails due to connectivity issues or API errors, an empty
	 * list is returned rather than throwing an exception, allowing the application
	 * to continue functioning with degraded capabilities.
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * This method is exposed as an MCP tool and can be invoked by AI clients with
	 * natural language requests like "list all collections" or "show me available
	 * databases".
	 *
	 * @return a list of collection names, or an empty list if unable to retrieve
	 *         them
	 * @see CollectionAdminRequest.List
	 */
	@McpTool(name = "list-collections", description = "List solr collections")
	public List<String> listCollections() {
		try {
			CollectionAdminRequest.List request = new CollectionAdminRequest.List();
			CollectionAdminResponse response = request.process(solrClient);

			@SuppressWarnings("unchecked")
			List<String> collections = (List<String>) response.getResponse().get(COLLECTIONS_KEY);
			return collections != null ? collections : new ArrayList<>();
		} catch (SolrServerException | IOException _) {
			return new ArrayList<>();
		}
	}

	/**
	 * Retrieves comprehensive performance metrics and statistics for a specified
	 * Solr collection.
	 *
	 * <p>
	 * This method aggregates metrics from multiple Solr endpoints to provide a
	 * complete performance profile including index health, query performance, cache
	 * utilization, and request handler statistics.
	 *
	 * <p>
	 * <strong>Collected Metrics:</strong>
	 *
	 * <ul>
	 * <li><strong>Index Statistics</strong>: Document counts, segment information
	 * (via Luke handler)
	 * <li><strong>Query Performance</strong>: Response times, result counts,
	 * relevance scores
	 * <li><strong>Cache Utilization</strong>: Hit ratios, eviction rates for all
	 * cache types
	 * <li><strong>Handler Performance</strong>: Request volumes, error rates,
	 * throughput metrics
	 * </ul>
	 *
	 * <p>
	 * <strong>Collection Name Handling:</strong>
	 *
	 * <p>
	 * Supports both collection names and shard names. If a shard name like
	 * "films_shard1_replica_n1" is provided, it will be automatically converted to
	 * the base collection name "films" for API calls.
	 *
	 * <p>
	 * <strong>Validation:</strong>
	 *
	 * <p>
	 * The method validates that the specified collection exists before attempting
	 * to collect metrics. If the collection is not found, an
	 * {@code IllegalArgumentException} is thrown with a descriptive error message.
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * Exposed as an MCP tool for natural language queries like "get metrics for
	 * my_collection" or "show me performance stats for the search index".
	 *
	 * @param collection
	 *            the name of the collection to analyze (supports both collection
	 *            and shard names)
	 * @return comprehensive metrics object containing all collected statistics
	 * @throws IllegalArgumentException
	 *             if the specified collection does not exist
	 * @throws SolrServerException
	 *             if there are errors communicating with Solr
	 * @throws IOException
	 *             if there are I/O errors during communication
	 * @see SolrMetrics
	 * @see LukeRequest
	 * @see #extractCollectionName(String)
	 */
	@McpTool(name = "get-collection-stats", description = "Get stats/metrics on a Solr collection")
	public SolrMetrics getCollectionStats(
			@McpToolParam(description = "Solr collection to get stats/metrics for") String collection)
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
		QueryResponse statsResponse = solrClient.query(actualCollection, new SolrQuery(ALL_DOCUMENTS_QUERY).setRows(0));

		return new SolrMetrics(buildIndexStats(lukeResponse), buildQueryStats(statsResponse),
				fetchCacheMetrics(actualCollection), fetchHandlerMetrics(actualCollection), new Date());
	}

	/**
	 * Builds an IndexStats object from a Solr Luke response containing index
	 * metadata.
	 *
	 * <p>
	 * The Luke handler provides low-level Lucene index information including
	 * document counts, segment details, and field statistics. This method extracts
	 * the essential index health metrics for monitoring and analysis.
	 *
	 * <p>
	 * <strong>Extracted Metrics:</strong>
	 *
	 * <ul>
	 * <li><strong>numDocs</strong>: Total number of documents excluding deleted
	 * ones
	 * <li><strong>segmentCount</strong>: Number of Lucene segments (performance
	 * indicator)
	 * </ul>
	 *
	 * <p>
	 * <strong>Performance Implications:</strong>
	 *
	 * <p>
	 * High segment counts may indicate the need for index optimization to improve
	 * search performance. The optimal segment count depends on index size and
	 * update frequency.
	 *
	 * @param lukeResponse
	 *            the Luke response containing raw index information
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
	 * Builds a QueryStats object from a Solr query response containing performance
	 * metrics.
	 *
	 * <p>
	 * Extracts key performance indicators from a query execution including timing,
	 * result characteristics, and relevance scoring information. These metrics help
	 * identify query performance patterns and optimization opportunities.
	 *
	 * <p>
	 * <strong>Extracted Metrics:</strong>
	 *
	 * <ul>
	 * <li><strong>queryTime</strong>: Execution time in milliseconds
	 * <li><strong>totalResults</strong>: Total matching documents found
	 * <li><strong>start</strong>: Pagination offset (0-based)
	 * <li><strong>maxScore</strong>: Highest relevance score in results
	 * </ul>
	 *
	 * <p>
	 * <strong>Performance Analysis:</strong>
	 *
	 * <p>
	 * Query time metrics help identify slow queries that may need optimization,
	 * while result counts and scores provide insight into search effectiveness.
	 *
	 * @param response
	 *            the query response containing performance and result metadata
	 * @return QueryStats object with extracted performance metrics
	 * @see QueryStats
	 * @see QueryResponse
	 */
	public QueryStats buildQueryStats(QueryResponse response) {

		return new QueryStats(response.getQTime(), response.getResults().getNumFound(),
				response.getResults().getStart(), response.getResults().getMaxScore());
	}

	/**
	 * Retrieves cache performance metrics for all cache types in a Solr collection.
	 *
	 * <p>
	 * Collects detailed cache utilization statistics from Solr's Metrics API,
	 * providing insights into cache effectiveness and memory usage patterns. Cache
	 * performance directly impacts query response times and system efficiency.
	 *
	 * <p>
	 * <strong>Monitored Cache Types:</strong>
	 *
	 * <ul>
	 * <li><strong>Query Result Cache</strong>: Caches complete query results for
	 * identical searches
	 * <li><strong>Document Cache</strong>: Caches retrieved document field data
	 * <li><strong>Filter Cache</strong>: Caches filter query results for faceting
	 * and filtering
	 * </ul>
	 *
	 * <p>
	 * <strong>Key Performance Indicators:</strong>
	 *
	 * <ul>
	 * <li><strong>Hit Ratio</strong>: Cache effectiveness (higher is better)
	 * <li><strong>Evictions</strong>: Memory pressure indicator
	 * <li><strong>Size</strong>: Current cache utilization
	 * </ul>
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * Returns {@code null} if cache statistics cannot be retrieved or if all cache
	 * types are empty/unavailable. This allows graceful degradation when cache
	 * monitoring is not available.
	 *
	 * @param collection
	 *            the collection name to retrieve cache metrics for
	 * @return CacheStats object with all cache performance metrics, or null if
	 *         unavailable
	 * @see CacheStats
	 * @see CacheInfo
	 * @see #extractCacheStats(NamedList)
	 * @see #isCacheStatsEmpty(CacheStats)
	 */
	public CacheStats getCacheMetrics(String collection) {
		String actualCollection = extractCollectionName(collection);

		if (!validateCollectionExists(actualCollection)) {
			return null;
		}

		return fetchCacheMetrics(actualCollection);
	}

	/**
	 * Internal cache metrics fetch that assumes the collection has already been
	 * validated and the name has been extracted from any shard identifier.
	 */
	private CacheStats fetchCacheMetrics(String collection) {
		try {
			NamedList<Object> coreMetrics = fetchMetrics(collection, CACHE_METRIC_PREFIX);
			if (coreMetrics == null) {
				return null;
			}

			CacheStats stats = extractCacheStats(coreMetrics);
			return isCacheStatsEmpty(stats) ? null : stats;
		} catch (SolrServerException | IOException | RuntimeException _) {
			return null;
		}
	}

	/**
	 * Checks if cache statistics are empty or contain no meaningful data.
	 *
	 * <p>
	 * Used to determine whether cache metrics are worth returning to clients. Empty
	 * cache stats typically indicate that caches are not configured or not yet
	 * populated with data.
	 *
	 * @param stats
	 *            the cache statistics to evaluate
	 * @return true if the stats are null or all cache types are null
	 */
	private boolean isCacheStatsEmpty(CacheStats stats) {
		return stats == null
				|| (stats.queryResultCache() == null && stats.documentCache() == null && stats.filterCache() == null);
	}

	/**
	 * Extracts cache performance statistics from Solr Metrics API response data.
	 *
	 * @param coreMetrics
	 *            the core metrics from the Solr Metrics API
	 * @return CacheStats object containing parsed metrics for all cache types
	 */
	private CacheStats extractCacheStats(NamedList<Object> coreMetrics) {
		return new CacheStats(extractSingleCacheInfo(coreMetrics, QUERY_RESULT_CACHE_KEY),
				extractSingleCacheInfo(coreMetrics, DOCUMENT_CACHE_KEY),
				extractSingleCacheInfo(coreMetrics, FILTER_CACHE_KEY));
	}

	@SuppressWarnings("unchecked")
	private CacheInfo extractSingleCacheInfo(NamedList<Object> coreMetrics, String key) {
		NamedList<Object> cache = (NamedList<Object>) coreMetrics.get(key);
		if (cache == null) {
			return null;
		}
		return new CacheInfo(getLong(cache, LOOKUPS_FIELD), getLong(cache, HITS_FIELD), getFloat(cache, HITRATIO_FIELD),
				getLong(cache, INSERTS_FIELD), getLong(cache, EVICTIONS_FIELD), getLong(cache, SIZE_FIELD));
	}

	/**
	 * Retrieves request handler performance metrics for core Solr operations.
	 *
	 * <p>
	 * Collects detailed performance statistics for the primary request handlers
	 * that process search and update operations. Handler metrics provide insights
	 * into system throughput, error rates, and response time characteristics.
	 *
	 * <p>
	 * <strong>Monitored Handlers:</strong>
	 *
	 * <ul>
	 * <li><strong>Select Handler (/select)</strong>: Processes search and query
	 * requests
	 * <li><strong>Update Handler (/update)</strong>: Processes document indexing
	 * operations
	 * </ul>
	 *
	 * <p>
	 * <strong>Performance Metrics:</strong>
	 *
	 * <ul>
	 * <li><strong>Request Volume</strong>: Total requests processed
	 * <li><strong>Error Rates</strong>: Failed request counts and timeouts
	 * <li><strong>Performance</strong>: Average response times and throughput
	 * </ul>
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * Returns {@code null} if handler statistics cannot be retrieved or if no
	 * meaningful handler data is available. This allows graceful degradation when
	 * handler monitoring endpoints are not accessible.
	 *
	 * @param collection
	 *            the collection name to retrieve handler metrics for
	 * @return HandlerStats object with performance metrics for all handlers, or
	 *         null if unavailable
	 * @see HandlerStats
	 * @see HandlerInfo
	 * @see #fetchFlatHandlerInfo(String, String, String)
	 * @see #isHandlerStatsEmpty(HandlerStats)
	 */
	public HandlerStats getHandlerMetrics(String collection) {
		String actualCollection = extractCollectionName(collection);

		if (!validateCollectionExists(actualCollection)) {
			return null;
		}

		return fetchHandlerMetrics(actualCollection);
	}

	/**
	 * Internal handler metrics fetch that assumes the collection has already been
	 * validated and the name has been extracted from any shard identifier.
	 */
	private HandlerStats fetchHandlerMetrics(String collection) {
		try {
			// Handler metrics are flat keys (e.g. QUERY./select.requests) so we
			// fetch each handler prefix separately and reconstruct HandlerInfo
			HandlerInfo selectHandler = fetchFlatHandlerInfo(collection, SELECT_HANDLER_METRIC_PREFIX,
					SELECT_HANDLER_KEY);
			HandlerInfo updateHandler = fetchFlatHandlerInfo(collection, UPDATE_HANDLER_METRIC_PREFIX,
					UPDATE_HANDLER_KEY);

			HandlerStats stats = new HandlerStats(selectHandler, updateHandler);
			return isHandlerStatsEmpty(stats) ? null : stats;
		} catch (SolrServerException | IOException | RuntimeException _) {
			return null;
		}
	}

	/**
	 * Checks if handler statistics are empty or contain no meaningful data.
	 *
	 * <p>
	 * Used to determine whether handler metrics are worth returning to clients.
	 * Empty handler stats typically indicate that handlers haven't processed any
	 * requests yet or statistics collection is not enabled.
	 *
	 * @param stats
	 *            the handler statistics to evaluate
	 * @return true if the stats are null or all handler types are null
	 */
	private boolean isHandlerStatsEmpty(HandlerStats stats) {
		return stats == null || (stats.selectHandler() == null && stats.updateHandler() == null);
	}

	/**
	 * Fetches metrics from the Solr Metrics API for a given collection and prefix.
	 *
	 * @param collection
	 *            the collection name
	 * @param prefix
	 *            the metric key prefix to filter (e.g. "CACHE.searcher", "HANDLER")
	 * @return the core-level metrics NamedList, or null if unavailable
	 */
	@SuppressWarnings("unchecked")
	private NamedList<Object> fetchMetrics(String collection, String prefix) throws SolrServerException, IOException {
		ModifiableSolrParams params = new ModifiableSolrParams();
		params.set(GROUP_PARAM, CORE_GROUP);
		params.set(PREFIX_PARAM, prefix);
		params.set(WT_PARAM, JSON_FORMAT);

		// Metrics API is a node-level endpoint, not per-collection
		GenericSolrRequest request = new GenericSolrRequest(SolrRequest.METHOD.GET, ADMIN_METRICS_PATH, params);

		NamedList<Object> response = solrClient.request(request);
		NamedList<Object> metrics = (NamedList<Object>) response.get(METRICS_KEY);
		if (metrics == null || metrics.size() == 0) {
			return null;
		}

		// Find the core registry matching the requested collection
		// Keys are like "solr.core.<collection>.<shard>.<replica>"
		String corePrefix = "solr.core." + collection + ".";
		for (int i = 0; i < metrics.size(); i++) {
			String key = metrics.getName(i);
			if (key != null && key.startsWith(corePrefix)) {
				return (NamedList<Object>) metrics.getVal(i);
			}
		}
		return null;
	}

	/**
	 * Fetches and extracts handler metrics from flat Solr Metrics API keys.
	 *
	 * <p>
	 * Handler metrics in Solr are stored as flat keys (e.g.
	 * {@code QUERY./select.requests}) rather than nested objects. This method
	 * fetches core metrics filtered by the handler prefix and reconstructs a
	 * {@link HandlerInfo} from the individual flat keys.
	 *
	 * @param collection
	 *            the collection name
	 * @param metricPrefix
	 *            the prefix for the Metrics API filter (e.g. {@code QUERY./select})
	 * @param keyPrefix
	 *            the flat key prefix including trailing dot (e.g.
	 *            {@code QUERY./select.})
	 * @return HandlerInfo with stats, or null if unavailable
	 */
	private HandlerInfo fetchFlatHandlerInfo(String collection, String metricPrefix, String keyPrefix)
			throws SolrServerException, IOException {
		NamedList<Object> coreMetrics = fetchMetrics(collection, metricPrefix);
		if (coreMetrics == null) {
			return null;
		}
		return extractFlatHandlerInfo(coreMetrics, keyPrefix);
	}

	/**
	 * Extracts a {@link HandlerInfo} from flat metric keys in core metrics.
	 *
	 * @param coreMetrics
	 *            the core metrics NamedList with flat keys
	 * @param keyPrefix
	 *            the flat key prefix including trailing dot (e.g.
	 *            {@code QUERY./select.})
	 * @return HandlerInfo reconstructed from flat keys, or null if no requests key
	 *         found
	 */
	private HandlerInfo extractFlatHandlerInfo(NamedList<Object> coreMetrics, String keyPrefix) {
		Long requests = getLong(coreMetrics, keyPrefix + REQUESTS_FIELD);
		if (requests == null) {
			return null;
		}
		Long errors = getLong(coreMetrics, keyPrefix + ERRORS_FIELD);
		Long timeouts = getLong(coreMetrics, keyPrefix + TIMEOUTS_FIELD);
		Long totalTime = getLong(coreMetrics, keyPrefix + TOTAL_TIME_FIELD);
		// avgTimePerRequest and avgRequestsPerSecond are not available as flat metrics;
		// compute avgTimePerRequest from totalTime/requests when possible
		Float avgTimePerRequest = (requests > 0 && totalTime != null) ? (float) totalTime / requests : null;
		return new HandlerInfo(requests, errors, timeouts, totalTime, avgTimePerRequest, null);
	}

	/**
	 * Extracts the actual collection name from a shard name in SolrCloud
	 * environments.
	 *
	 * <p>
	 * In SolrCloud deployments, collection operations often return shard names that
	 * include replica and shard identifiers (e.g., "films_shard1_replica_n1"). This
	 * method extracts the base collection name ("films") for use in API calls that
	 * require the collection name.
	 *
	 * <p>
	 * <strong>Extraction Logic:</strong>
	 *
	 * <ul>
	 * <li>Detects shard patterns containing the {@value #SHARD_SUFFIX} suffix
	 * <li>Returns the substring before the shard identifier
	 * <li>Returns the original string if no shard pattern is detected
	 * </ul>
	 *
	 * <p>
	 * <strong>Examples:</strong>
	 *
	 * <ul>
	 * <li>"films_shard1_replica_n1" → "films"
	 * <li>"products_shard2_replica_n3" → "products"
	 * <li>"simple_collection" → "simple_collection" (unchanged)
	 * </ul>
	 *
	 * @param collectionOrShard
	 *            the collection or shard name to parse
	 * @return the extracted collection name, or the original string if no shard
	 *         pattern found
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
	 * <p>
	 * Performs collection existence validation by checking against the list of
	 * available collections. Supports both exact collection name matches and
	 * shard-based matching for SolrCloud environments.
	 *
	 * <p>
	 * <strong>Validation Strategy:</strong>
	 *
	 * <ol>
	 * <li><strong>Exact Match</strong>: Checks if the collection name exists
	 * exactly
	 * <li><strong>Shard Match</strong>: Checks if any shards start with
	 * "collection{@value #SHARD_SUFFIX}" pattern
	 * </ol>
	 *
	 * <p>
	 * This dual approach ensures compatibility with SolrCloud environments where
	 * shard names may be returned alongside collection names.
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * Returns {@code false} if validation fails due to communication errors,
	 * allowing calling methods to handle missing collections appropriately.
	 *
	 * @param collection
	 *            the collection name to validate
	 * @return true if the collection exists (either exact or shard match), false
	 *         otherwise
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

			// Check if any of the returned collections start with the collection name (for
			// shard
			// names)
			return collections.stream().anyMatch(c -> c.startsWith(collection + SHARD_SUFFIX));
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Performs a comprehensive health check on a Solr collection.
	 *
	 * <p>
	 * Evaluates collection availability and performance by executing a ping
	 * operation and basic query to gather health indicators. This method provides a
	 * quick way to determine if a collection is operational and responding to
	 * requests.
	 *
	 * <p>
	 * <strong>Health Check Components:</strong>
	 *
	 * <ul>
	 * <li><strong>Availability</strong>: Collection responds to ping requests
	 * <li><strong>Performance</strong>: Response time measurement
	 * <li><strong>Content</strong>: Document count verification using universal
	 * query ({@value #ALL_DOCUMENTS_QUERY})
	 * <li><strong>Timestamp</strong>: When the check was performed
	 * </ul>
	 *
	 * <p>
	 * <strong>Success Criteria:</strong>
	 *
	 * <p>
	 * A collection is considered healthy if both the ping operation and a basic
	 * query complete successfully without exceptions. Performance metrics are
	 * collected during the health check process.
	 *
	 * <p>
	 * <strong>Failure Handling:</strong>
	 *
	 * <p>
	 * If the health check fails, a status object is returned with
	 * {@code isHealthy=false} and the error message describing the failure reason.
	 * This allows monitoring systems to identify specific issues.
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * Exposed as an MCP tool for natural language health queries like "check if
	 * my_collection is healthy" or "is the search index working properly".
	 *
	 * @param collection
	 *            the name of the collection to health check
	 * @return SolrHealthStatus object containing health assessment results
	 * @see SolrHealthStatus
	 * @see SolrPingResponse
	 */
	@McpTool(name = "check-health", description = "Check health of a Solr collection")
	public SolrHealthStatus checkHealth(@McpToolParam(description = "Solr collection") String collection) {
		String actualCollection = extractCollectionName(collection);
		try {
			// Ping Solr
			SolrPingResponse pingResponse = solrClient.ping(actualCollection);

			// Get basic stats
			QueryResponse statsResponse = solrClient.query(actualCollection,
					new SolrQuery(ALL_DOCUMENTS_QUERY).setRows(0));

			return new SolrHealthStatus(true, null, pingResponse.getElapsedTime(),
					statsResponse.getResults().getNumFound(), new Date(), actualCollection);

		} catch (Exception e) {
			return new SolrHealthStatus(false, e.getMessage(), null, null, new Date(), actualCollection);
		}
	}

	/**
	 * Creates a new Solr collection (SolrCloud) or core (standalone Solr).
	 *
	 * <p>
	 * Automatically detects the deployment type and uses the appropriate API:
	 *
	 * <p>
	 * Uses the Collections API, which works with any SolrClient pointing to a
	 * SolrCloud deployment.
	 *
	 * <p>
	 * Optional parameters default to sensible values when not provided by the MCP
	 * client: configSet defaults to {@value #DEFAULT_CONFIGSET}, numShards and
	 * replicationFactor both default to 1.
	 *
	 * @param name
	 *            the name of the collection to create (must not be blank)
	 * @param configSet
	 *            the configset name (optional, defaults to
	 *            {@value #DEFAULT_CONFIGSET})
	 * @param numShards
	 *            number of shards (optional, defaults to 1)
	 * @param replicationFactor
	 *            replication factor (optional, defaults to 1)
	 * @return result describing the outcome of the creation operation
	 * @throws IllegalArgumentException
	 *             if the collection name is blank
	 * @throws SolrServerException
	 *             if Solr returns an error
	 * @throws IOException
	 *             if there are I/O errors during communication
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "create-collection", description = "Create a new Solr collection. "
			+ "configSet defaults to _default, numShards and replicationFactor default to 1.")
	public CollectionCreationResult createCollection(
			@McpToolParam(description = "Name of the collection to create") String name,
			@McpToolParam(description = "Configset name. Defaults to _default.", required = false) String configSet,
			@McpToolParam(description = "Number of shards (SolrCloud only). Defaults to 1.", required = false) Integer numShards,
			@McpToolParam(description = "Replication factor (SolrCloud only). Defaults to 1.", required = false) Integer replicationFactor)
			throws SolrServerException, IOException {

		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException(BLANK_COLLECTION_NAME_ERROR);
		}

		String effectiveConfigSet = configSet != null ? configSet : DEFAULT_CONFIGSET;
		int effectiveShards = numShards != null ? numShards : DEFAULT_NUM_SHARDS;
		int effectiveRf = replicationFactor != null ? replicationFactor : DEFAULT_REPLICATION_FACTOR;

		CollectionAdminRequest.createCollection(name, effectiveConfigSet, effectiveShards, effectiveRf)
				.process(solrClient);

		return new CollectionCreationResult(name, true, "Collection created successfully", new Date());
	}
}
