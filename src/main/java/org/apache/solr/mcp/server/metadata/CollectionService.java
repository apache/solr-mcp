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
 * Service providing Solr collection management and monitoring via MCP.
 *
 * <p>
 * Uses the {@code /admin/metrics} API (available since Solr 7.1+) for cache and
 * handler metrics, ensuring compatibility with both Solr 9 and 10.
 *
 * @see SolrMetrics
 * @see SolrHealthStatus
 */
@Service
public class CollectionService {

	private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

	private static final String ALL_DOCUMENTS_QUERY = "*:*";
	private static final String SHARD_SUFFIX = "_shard";
	private static final String WT_PARAM = "wt";
	private static final String JSON_FORMAT = "json";
	private static final String COLLECTIONS_KEY = "collections";
	private static final String SEGMENT_COUNT_KEY = "segmentCount";
	private static final String METRICS_KEY = "metrics";
	private static final String ADMIN_METRICS_PATH = "/admin/metrics";
	private static final String GROUP_PARAM = "group";
	private static final String PREFIX_PARAM = "prefix";
	private static final String CORE_GROUP = "core";
	private static final String CACHE_METRICS_PREFIX = "CACHE.searcher";
	private static final String QUERY_RESULT_CACHE_KEY = "queryResultCache";
	private static final String DOCUMENT_CACHE_KEY = "documentCache";
	private static final String FILTER_CACHE_KEY = "filterCache";
	private static final String QUERY_HANDLER_METRICS_PREFIX = "QUERY./select";
	private static final String UPDATE_HANDLER_METRICS_PREFIX = "UPDATE./update";
	private static final String LOOKUPS_FIELD = "lookups";
	private static final String HITS_FIELD = "hits";
	private static final String HITRATIO_FIELD = "hitratio";
	private static final String INSERTS_FIELD = "inserts";
	private static final String EVICTIONS_FIELD = "evictions";
	private static final String SIZE_FIELD = "size";
	private static final String REQUESTS_FIELD = "requests";
	private static final String ERRORS_FIELD = "errors";
	private static final String TIMEOUTS_FIELD = "timeouts";
	private static final String TOTAL_TIME_FIELD = "totalTime";
	private static final String AVG_TIME_PER_REQUEST_FIELD = "avgTimePerRequest";
	private static final String AVG_REQUESTS_PER_SECOND_FIELD = "avgRequestsPerSecond";
	private static final String COLLECTION_NOT_FOUND_ERROR = "Collection not found: ";

	private final SolrClient solrClient;
	private final ObjectMapper objectMapper;

	/**
	 * @param solrClient
	 *            the SolrJ client
	 * @param objectMapper
	 *            the Jackson ObjectMapper
	 * @see SolrClient
	 * @see SolrConfigurationProperties
	 */
	public CollectionService(SolrClient solrClient, ObjectMapper objectMapper) {
		this.solrClient = solrClient;
		this.objectMapper = objectMapper;
	}

	@McpResource(uri = "solr://collections", name = "solr-collections", description = "List of all Solr collections available in the cluster", mimeType = "application/json")
	public String getCollectionsResource() {
		return toJson(objectMapper, listCollections());
	}

	@McpComplete(uri = "solr://{collection}/schema")
	public List<String> completeCollectionForSchema() {
		return listCollections();
	}

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

	public IndexStats buildIndexStats(LukeResponse lukeResponse) {
		NamedList<Object> indexInfo = lukeResponse.getIndexInfo();
		Integer segmentCount = getInteger(indexInfo, SEGMENT_COUNT_KEY);
		return new IndexStats(lukeResponse.getNumDocs(), segmentCount);
	}

	public QueryStats buildQueryStats(QueryResponse response) {
		return new QueryStats(response.getQTime(), response.getResults().getNumFound(),
				response.getResults().getStart(), response.getResults().getMaxScore());
	}

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

	private boolean isCacheStatsEmpty(CacheStats stats) {
		return stats == null
				|| (stats.queryResultCache() == null && stats.documentCache() == null && stats.filterCache() == null);
	}

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

	private boolean isHandlerStatsEmpty(HandlerStats stats) {
		return stats == null || (stats.selectHandler() == null && stats.updateHandler() == null);
	}

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
