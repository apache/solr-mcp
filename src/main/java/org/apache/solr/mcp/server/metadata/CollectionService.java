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

import static org.apache.solr.mcp.server.metadata.CollectionUtils.getFloat;
import static org.apache.solr.mcp.server.metadata.CollectionUtils.getInteger;
import static org.apache.solr.mcp.server.metadata.CollectionUtils.getLong;
import static org.apache.solr.mcp.server.util.JsonUtils.toJson;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpComplete;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
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
 * <li><strong>Collection Discovery</strong>: Lists available collections/cores
 * with automatic SolrCloud vs standalone detection
 * <li><strong>Performance Monitoring</strong>: Comprehensive metrics collection
 * including index, query, cache, and handler statistics
 * <li><strong>Health Monitoring</strong>: Real-time health checks with
 * availability and performance indicators
 * <li><strong>Shard-Aware Operations</strong>: Intelligent handling of
 * SolrCloud shard names and collection name extraction
 * </ul>
 *
 * <p>
 * <strong>Metrics Collection:</strong>
 *
 * <p>
 * Uses the {@code /admin/metrics} API (available since Solr 7.1+) for cache and
 * handler metrics, ensuring compatibility with both Solr 9 and 10. The Metrics
 * API replaces the deprecated {@code /admin/mbeans} endpoint which was removed
 * in Solr 10.
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
 * <li><strong>Standalone</strong>: Single-node mode using Core Admin API
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
public class CollectionService {

	private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

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

	// ========================================
	// Constants for Response Parsing
	// ========================================

	/** Key name for collections list in Collections API responses */
	private static final String COLLECTIONS_KEY = "collections";

	/** Key name for segment count information in Luke response */
	private static final String SEGMENT_COUNT_KEY = "segmentCount";

	/** Key name for the metrics section in Metrics API responses */
	private static final String METRICS_KEY = "metrics";

	/** URL path for Solr Metrics admin endpoint */
	private static final String ADMIN_METRICS_PATH = "/admin/metrics";

	/** Request parameter name for specifying metric group in Metrics API */
	private static final String GROUP_PARAM = "group";

	/** Request parameter name for specifying metric key prefix in Metrics API */
	private static final String PREFIX_PARAM = "prefix";

	/** Metric group value for core-level metrics */
	private static final String CORE_GROUP = "core";

	/** Metric key prefix for searcher cache metrics */
	private static final String CACHE_METRICS_PREFIX = "CACHE.searcher";

	/** Key name for query result cache in Metrics API responses */
	private static final String QUERY_RESULT_CACHE_KEY = "queryResultCache";

	/** Key name for document cache in Metrics API responses */
	private static final String DOCUMENT_CACHE_KEY = "documentCache";

	/** Key name for filter cache in Metrics API responses */
	private static final String FILTER_CACHE_KEY = "filterCache";

	/** Metric key prefix for query handler (/select) metrics */
	private static final String QUERY_HANDLER_METRICS_PREFIX = "QUERY./select";

	/** Metric key prefix for update handler (/update) metrics */
	private static final String UPDATE_HANDLER_METRICS_PREFIX = "UPDATE./update";

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
	 * Lists all available Solr collections or cores in the cluster.
	 *
	 * <p>
	 * This method automatically detects the Solr deployment type and uses the
	 * appropriate API:
	 *
	 * <ul>
	 * <li><strong>SolrCloud</strong>: Uses Collections API to list distributed
	 * collections
	 * <li><strong>Standalone</strong>: Uses Core Admin API to list individual cores
	 * </ul>
	 *
	 * <p>
	 * In SolrCloud environments, the returned names may include shard identifiers
	 * (e.g., "films_shard1_replica_n1"). Use {@link #extractCollectionName(String)}
	 * to get the base collection name if needed.
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * If the operation fails due to connectivity issues or API errors, an empty
	 * list is returned rather than throwing an exception, allowing the application
	 * to continue functioning with degraded capabilities.
	 *
	 * @return a list of collection/core names, or an empty list if unable to
	 *         retrieve them
	 * @see CollectionAdminRequest.List
	 * @see CoreAdminRequest
	 */
	@McpTool(name = "list-collections", description = "List solr collections")
	public List<String> listCollections() {
		try {
			if (solrClient instanceof CloudSolrClient) {
				CollectionAdminRequest.List request = new CollectionAdminRequest.List();
				CollectionAdminResponse response = request.process(solrClient);

				@SuppressWarnings("unchecked")
				List<String> collections = (List<String>) response.getResponse().get(COLLECTIONS_KEY);
				return collections != null ? collections : new ArrayList<>();
			} else {
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
			log.warn("Failed to list collections: {}", e.getMessage());
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
	 * cache types (via {@code /admin/metrics} API)
	 * <li><strong>Handler Performance</strong>: Request volumes, error rates,
	 * throughput metrics (via {@code /admin/metrics} API)
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
		String actualCollection = extractCollectionName(collection);

		if (!validateCollectionExists(actualCollection)) {
			throw new IllegalArgumentException(COLLECTION_NOT_FOUND_ERROR + actualCollection);
		}

		LukeRequest lukeRequest = new LukeRequest();
		lukeRequest.setIncludeIndexFieldFlags(true);
		LukeResponse lukeResponse = lukeRequest.process(solrClient, actualCollection);

		QueryResponse statsResponse = solrClient.query(actualCollection, new SolrQuery(ALL_DOCUMENTS_QUERY).setRows(0));

		return new SolrMetrics(buildIndexStats(lukeResponse), buildQueryStats(statsResponse),
				getCacheMetrics(actualCollection), getHandlerMetrics(actualCollection), new Date());
	}

	/**
	 * Builds an IndexStats object from a Solr Luke response containing index
	 * metadata.
	 *
	 * <p>
	 * The Luke handler provides low-level Lucene index information including
	 * document counts, segment details, and field statistics.
	 *
	 * @param lukeResponse
	 *            the Luke response containing raw index information
	 * @return IndexStats object with extracted and formatted metrics
	 * @see IndexStats
	 * @see LukeResponse
	 */
	public IndexStats buildIndexStats(LukeResponse lukeResponse) {
		NamedList<Object> indexInfo = lukeResponse.getIndexInfo();
		Integer segmentCount = getInteger(indexInfo, SEGMENT_COUNT_KEY);
		return new IndexStats(lukeResponse.getNumDocs(), segmentCount);
	}

	/**
	 * Builds a QueryStats object from a Solr query response containing performance
	 * metrics.
	 *
	 * <p>
	 * Extracts key performance indicators from a query execution including timing,
	 * result characteristics, and relevance scoring information.
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
	 * Collects detailed cache utilization statistics from Solr's
	 * {@code /admin/metrics} endpoint (available since Solr 7.1+), providing
	 * insights into cache effectiveness and memory usage patterns.
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
	 */
	public CacheStats getCacheMetrics(String collection) {
		String actualCollection = extractCollectionName(collection);

		if (!validateCollectionExists(actualCollection)) {
			log.debug("Collection '{}' not found, skipping cache metrics", actualCollection);
			return null;
		}

		try {
			ModifiableSolrParams params = new ModifiableSolrParams();
			params.set(GROUP_PARAM, CORE_GROUP);
			params.set(PREFIX_PARAM, CACHE_METRICS_PREFIX);
			params.set(WT_PARAM, JSON_FORMAT);

			GenericSolrRequest request = new GenericSolrRequest(SolrRequest.METHOD.GET, ADMIN_METRICS_PATH, params);
			NamedList<Object> response = solrClient.request(request);

			CacheStats stats = extractCacheStats(response, actualCollection);
			if (isCacheStatsEmpty(stats)) {
				log.debug("No cache metrics available for collection '{}'", actualCollection);
				return null;
			}

			return stats;
		} catch (SolrServerException | IOException | RuntimeException e) {
			log.warn("Failed to retrieve cache metrics for collection '{}': {}", actualCollection, e.getMessage());
			return null;
		}
	}

	/**
	 * Checks if cache statistics are empty or contain no meaningful data.
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
	 * Extracts cache performance statistics from a Metrics API response.
	 *
	 * <p>
	 * Parses the {@code /admin/metrics} response to extract structured cache
	 * performance metrics. Locates the core registry matching the collection name
	 * and extracts cache metrics using the {@code CACHE.searcher.*} prefix.
	 *
	 * @param metricsResponse
	 *            the raw Metrics API response
	 * @param collection
	 *            the collection name to find in the core registry
	 * @return CacheStats object containing parsed metrics for all cache types
	 * @see CacheStats
	 * @see CacheInfo
	 */
	@SuppressWarnings("unchecked")
	private CacheStats extractCacheStats(NamedList<Object> metricsResponse, String collection) {
		NamedList<Object> metrics = (NamedList<Object>) metricsResponse.get(METRICS_KEY);
		if (metrics == null) {
			return null;
		}

		NamedList<Object> coreMetrics = findCoreRegistry(metrics, collection);
		if (coreMetrics == null) {
			return null;
		}

		return new CacheStats(extractCacheInfo(coreMetrics, QUERY_RESULT_CACHE_KEY),
				extractCacheInfo(coreMetrics, DOCUMENT_CACHE_KEY), extractCacheInfo(coreMetrics, FILTER_CACHE_KEY));
	}

	/**
	 * Finds the core registry entry matching the given collection name in the
	 * Metrics API response.
	 *
	 * <p>
	 * The Metrics API organizes metrics by registry name (e.g.,
	 * {@code solr.core.my_collection.shard1.replica_n1}). This method searches for
	 * a registry that starts with {@code solr.core.} and contains the collection
	 * name.
	 *
	 * @param metrics
	 *            the metrics section from the Metrics API response
	 * @param collection
	 *            the collection name to search for
	 * @return the core registry NamedList, or null if not found
	 */
	@SuppressWarnings("unchecked")
	private NamedList<Object> findCoreRegistry(NamedList<Object> metrics, String collection) {
		for (int i = 0; i < metrics.size(); i++) {
			String registryName = metrics.getName(i);
			if (registryName != null && registryName.startsWith("solr.core.") && registryName.contains(collection)) {
				Object value = metrics.getVal(i);
				if (value instanceof NamedList) {
					return (NamedList<Object>) value;
				}
			}
		}
		return null;
	}

	/**
	 * Extracts individual cache information from core metrics.
	 *
	 * <p>
	 * Handles both {@code NamedList} and {@code Map} (LinkedHashMap) response
	 * types, as the Metrics API may return either depending on the Solr version and
	 * response format.
	 *
	 * @param coreMetrics
	 *            the core registry metrics containing cache data
	 * @param cacheName
	 *            the cache name (e.g., "queryResultCache")
	 * @return CacheInfo with extracted metrics, or null if the cache data is not
	 *         found
	 * @see CacheInfo
	 */
	@SuppressWarnings("unchecked")
	private CacheInfo extractCacheInfo(NamedList<Object> coreMetrics, String cacheName) {
		Object cacheData = coreMetrics.get(CACHE_METRICS_PREFIX + "." + cacheName);
		if (cacheData instanceof NamedList) {
			NamedList<Object> stats = (NamedList<Object>) cacheData;
			return new CacheInfo(getLong(stats, LOOKUPS_FIELD), getLong(stats, HITS_FIELD),
					getFloat(stats, HITRATIO_FIELD), getLong(stats, INSERTS_FIELD), getLong(stats, EVICTIONS_FIELD),
					getLong(stats, SIZE_FIELD));
		}
		if (cacheData instanceof Map) {
			Map<String, Object> stats = (Map<String, Object>) cacheData;
			return new CacheInfo(numberToLong(stats.get(LOOKUPS_FIELD)), numberToLong(stats.get(HITS_FIELD)),
					numberToFloat(stats.get(HITRATIO_FIELD)), numberToLong(stats.get(INSERTS_FIELD)),
					numberToLong(stats.get(EVICTIONS_FIELD)), numberToLong(stats.get(SIZE_FIELD)));
		}
		return null;
	}

	private Long numberToLong(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

	private Float numberToFloat(Object value) {
		return value instanceof Number number ? number.floatValue() : 0.0f;
	}

	/**
	 * Retrieves request handler performance metrics for core Solr operations.
	 *
	 * <p>
	 * Collects detailed performance statistics from the {@code /admin/metrics}
	 * endpoint for the primary request handlers that process search and update
	 * operations.
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
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * Returns {@code null} if handler statistics cannot be retrieved or if no
	 * meaningful handler data is available.
	 *
	 * @param collection
	 *            the collection name to retrieve handler metrics for
	 * @return HandlerStats object with performance metrics for all handlers, or
	 *         null if unavailable
	 * @see HandlerStats
	 * @see HandlerInfo
	 */
	public HandlerStats getHandlerMetrics(String collection) {
		String actualCollection = extractCollectionName(collection);

		if (!validateCollectionExists(actualCollection)) {
			log.debug("Collection '{}' not found, skipping handler metrics", actualCollection);
			return null;
		}

		try {
			ModifiableSolrParams params = new ModifiableSolrParams();
			params.set(GROUP_PARAM, CORE_GROUP);
			params.set(PREFIX_PARAM, QUERY_HANDLER_METRICS_PREFIX + "," + UPDATE_HANDLER_METRICS_PREFIX);
			params.set(WT_PARAM, JSON_FORMAT);

			GenericSolrRequest request = new GenericSolrRequest(SolrRequest.METHOD.GET, ADMIN_METRICS_PATH, params);
			NamedList<Object> response = solrClient.request(request);

			HandlerStats stats = extractHandlerStats(response, actualCollection);
			if (isHandlerStatsEmpty(stats)) {
				log.debug("No handler metrics available for collection '{}'", actualCollection);
				return null;
			}

			return stats;
		} catch (SolrServerException | IOException | RuntimeException e) {
			log.warn("Failed to retrieve handler metrics for collection '{}': {}", actualCollection, e.getMessage());
			return null;
		}
	}

	/**
	 * Checks if handler statistics are empty or contain no meaningful data.
	 *
	 * @param stats
	 *            the handler statistics to evaluate
	 * @return true if the stats are null or all handler types are null
	 */
	private boolean isHandlerStatsEmpty(HandlerStats stats) {
		return stats == null || (stats.selectHandler() == null && stats.updateHandler() == null);
	}

	/**
	 * Extracts request handler performance statistics from a Metrics API response.
	 *
	 * <p>
	 * Parses the {@code /admin/metrics} response to extract structured handler
	 * performance metrics. Handler metrics are stored as flat key-value pairs in
	 * the core registry (e.g., {@code QUERY./select.requests},
	 * {@code UPDATE./update.errors}).
	 *
	 * @param metricsResponse
	 *            the raw Metrics API response
	 * @param collection
	 *            the collection name to find in the core registry
	 * @return HandlerStats object containing parsed metrics for all handler types
	 * @see HandlerStats
	 * @see HandlerInfo
	 */
	@SuppressWarnings("unchecked")
	private HandlerStats extractHandlerStats(NamedList<Object> metricsResponse, String collection) {
		NamedList<Object> metrics = (NamedList<Object>) metricsResponse.get(METRICS_KEY);
		if (metrics == null) {
			return null;
		}

		NamedList<Object> coreMetrics = findCoreRegistry(metrics, collection);
		if (coreMetrics == null) {
			return null;
		}

		return new HandlerStats(extractHandlerInfo(coreMetrics, QUERY_HANDLER_METRICS_PREFIX),
				extractHandlerInfo(coreMetrics, UPDATE_HANDLER_METRICS_PREFIX));
	}

	/**
	 * Extracts individual handler performance information from core metrics.
	 *
	 * <p>
	 * Handler metrics in the Metrics API are stored as flat key-value pairs with
	 * the handler prefix (e.g., {@code QUERY./select.requests = 500}).
	 *
	 * @param coreMetrics
	 *            the core registry metrics containing handler data
	 * @param handlerPrefix
	 *            the handler metric key prefix (e.g., "QUERY./select")
	 * @return HandlerInfo with extracted metrics, or null if no data is found
	 * @see HandlerInfo
	 */
	private HandlerInfo extractHandlerInfo(NamedList<Object> coreMetrics, String handlerPrefix) {
		Long requests = getLong(coreMetrics, handlerPrefix + "." + REQUESTS_FIELD);
		Long errors = getLong(coreMetrics, handlerPrefix + "." + ERRORS_FIELD);
		Long timeouts = getLong(coreMetrics, handlerPrefix + "." + TIMEOUTS_FIELD);
		Long totalTime = getLong(coreMetrics, handlerPrefix + "." + TOTAL_TIME_FIELD);
		Float avgTimePerRequest = getFloat(coreMetrics, handlerPrefix + "." + AVG_TIME_PER_REQUEST_FIELD);
		Float avgRequestsPerSecond = getFloat(coreMetrics, handlerPrefix + "." + AVG_REQUESTS_PER_SECOND_FIELD);

		if (requests == null && errors == null && timeouts == null) {
			return null;
		}

		return new HandlerInfo(requests, errors, timeouts, totalTime, avgTimePerRequest, avgRequestsPerSecond);
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
	 * <strong>Examples:</strong>
	 *
	 * <ul>
	 * <li>"films_shard1_replica_n1" &rarr; "films"
	 * <li>"products_shard2_replica_n3" &rarr; "products"
	 * <li>"simple_collection" &rarr; "simple_collection" (unchanged)
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

		if (collectionOrShard.contains(SHARD_SUFFIX)) {
			int shardIndex = collectionOrShard.indexOf(SHARD_SUFFIX);
			return collectionOrShard.substring(0, shardIndex);
		}

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

			if (collections.contains(collection)) {
				return true;
			}

			return collections.stream().anyMatch(c -> c.startsWith(collection + SHARD_SUFFIX));
		} catch (Exception e) {
			log.warn("Failed to validate collection '{}': {}", collection, e.getMessage());
			return false;
		}
	}

	/**
	 * Performs a comprehensive health check on a Solr collection.
	 *
	 * <p>
	 * Evaluates collection availability and performance by executing a ping
	 * operation and basic query to gather health indicators.
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
	 * <strong>Failure Handling:</strong>
	 *
	 * <p>
	 * If the health check fails, a status object is returned with
	 * {@code isHealthy=false} and the error message describing the failure reason.
	 *
	 * @param collection
	 *            the name of the collection to health check
	 * @return SolrHealthStatus object containing health assessment results
	 * @see SolrHealthStatus
	 * @see SolrPingResponse
	 */
	@McpTool(name = "check-health", description = "Check health of a Solr collection")
	public SolrHealthStatus checkHealth(@McpToolParam(description = "Solr collection") String collection) {
		try {
			SolrPingResponse pingResponse = solrClient.ping(collection);
			QueryResponse statsResponse = solrClient.query(collection, new SolrQuery(ALL_DOCUMENTS_QUERY).setRows(0));

			return new SolrHealthStatus(true, null, pingResponse.getElapsedTime(),
					statsResponse.getResults().getNumFound(), new Date(), null, null, null);

		} catch (Exception e) {
			log.warn("Health check failed for collection '{}': {}", collection, e.getMessage());
			return new SolrHealthStatus(false, e.getMessage(), null, null, new Date(), null, null, null);
		}
	}
}
