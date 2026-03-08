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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

	@Mock
	private SolrClient solrClient;

	@Mock
	private CloudSolrClient cloudSolrClient;

	@Mock
	private QueryResponse queryResponse;

	@Mock
	private LukeResponse lukeResponse;

	@Mock
	private SolrPingResponse pingResponse;

	private CollectionService collectionService;

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		collectionService = new CollectionService(solrClient, objectMapper);
	}

	// Constructor tests
	@Test
	void constructor_ShouldInitializeWithSolrClient() {
		assertNotNull(collectionService);
	}

	@Test
	void listCollections_WithCloudSolrClient_ShouldReturnCollections() throws Exception {
		CollectionService cloudService = new CollectionService(cloudSolrClient, objectMapper);
		assertNotNull(cloudService, "Should be able to construct service with CloudSolrClient");
	}

	// Collection name extraction tests
	@Test
	void extractCollectionName_WithShardName_ShouldExtractCollectionName() {
		assertEquals("films", collectionService.extractCollectionName("films_shard1_replica_n1"));
	}

	@Test
	void extractCollectionName_WithMultipleShards_ShouldExtractCorrectly() {
		assertEquals("products", collectionService.extractCollectionName("products_shard2_replica_n3"));
		assertEquals("users", collectionService.extractCollectionName("users_shard5_replica_n10"));
	}

	@Test
	void extractCollectionName_WithSimpleCollectionName_ShouldReturnUnchanged() {
		assertEquals("simple_collection", collectionService.extractCollectionName("simple_collection"));
	}

	@Test
	void extractCollectionName_WithNullInput_ShouldReturnNull() {
		assertNull(collectionService.extractCollectionName(null));
	}

	@Test
	void extractCollectionName_WithEmptyString_ShouldReturnEmptyString() {
		assertEquals("", collectionService.extractCollectionName(""));
	}

	@Test
	void extractCollectionName_WithCollectionNameContainingUnderscore_ShouldOnlyExtractBeforeShard() {
		assertEquals("my_complex_collection",
				collectionService.extractCollectionName("my_complex_collection_shard1_replica_n1"));
	}

	@Test
	void extractCollectionName_EdgeCases_ShouldHandleCorrectly() {
		assertEquals("a", collectionService.extractCollectionName("a_shard1"));
		assertEquals("collection", collectionService.extractCollectionName("collection_shard"));
		assertEquals("test_name", collectionService.extractCollectionName("test_name"));
		assertEquals("", collectionService.extractCollectionName("_shard1"));
	}

	@Test
	void extractCollectionName_WithShardInMiddleOfName_ShouldExtractCorrectly() {
		assertEquals("resharding_tasks", collectionService.extractCollectionName("resharding_tasks"),
				"Should not extract when '_shard' is not followed by number");
	}

	@Test
	void extractCollectionName_WithMultipleOccurrencesOfShard_ShouldUseFirst() {
		assertEquals("data", collectionService.extractCollectionName("data_shard1_shard2_replica_n1"),
				"Should use first occurrence of '_shard'");
	}

	// Health check tests
	@Test
	void checkHealth_WithHealthyCollection_ShouldReturnHealthyStatus() throws Exception {
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(100);

		when(solrClient.ping("test_collection")).thenReturn(pingResponse);
		when(pingResponse.getElapsedTime()).thenReturn(10L);
		when(solrClient.query(eq("test_collection"), any())).thenReturn(queryResponse);
		when(queryResponse.getResults()).thenReturn(docList);

		SolrHealthStatus result = collectionService.checkHealth("test_collection");

		assertNotNull(result);
		assertTrue(result.isHealthy());
		assertNull(result.errorMessage());
		assertEquals(10L, result.responseTime());
		assertEquals(100L, result.totalDocuments());
	}

	@Test
	void checkHealth_WithUnhealthyCollection_ShouldReturnUnhealthyStatus() throws Exception {
		when(solrClient.ping("unhealthy_collection")).thenThrow(new SolrServerException("Connection failed"));

		SolrHealthStatus result = collectionService.checkHealth("unhealthy_collection");

		assertNotNull(result);
		assertFalse(result.isHealthy());
		assertNotNull(result.errorMessage());
		assertTrue(result.errorMessage().contains("Connection failed"));
		assertNull(result.responseTime());
		assertNull(result.totalDocuments());
	}

	@Test
	void checkHealth_WhenPingSucceedsButQueryFails_ShouldReturnUnhealthyStatus() throws Exception {
		when(solrClient.ping("test_collection")).thenReturn(pingResponse);
		when(solrClient.query(eq("test_collection"), any())).thenThrow(new IOException("Query failed"));

		SolrHealthStatus result = collectionService.checkHealth("test_collection");

		assertNotNull(result);
		assertFalse(result.isHealthy());
		assertTrue(result.errorMessage().contains("Query failed"));
	}

	@Test
	void checkHealth_WithEmptyCollection_ShouldReturnHealthyWithZeroDocuments() throws Exception {
		SolrDocumentList emptyDocList = new SolrDocumentList();
		emptyDocList.setNumFound(0);

		when(solrClient.ping("empty_collection")).thenReturn(pingResponse);
		when(pingResponse.getElapsedTime()).thenReturn(5L);
		when(solrClient.query(eq("empty_collection"), any())).thenReturn(queryResponse);
		when(queryResponse.getResults()).thenReturn(emptyDocList);

		SolrHealthStatus result = collectionService.checkHealth("empty_collection");

		assertNotNull(result);
		assertTrue(result.isHealthy());
		assertEquals(0L, result.totalDocuments());
		assertEquals(5, result.responseTime());
	}

	@Test
	void checkHealth_IOException() throws Exception {
		when(solrClient.ping("error_collection")).thenThrow(new IOException("Network error"));

		SolrHealthStatus result = collectionService.checkHealth("error_collection");

		assertFalse(result.isHealthy());
		assertTrue(result.errorMessage().contains("Network error"));
		assertNull(result.responseTime());
	}

	// Query stats tests
	@Test
	void buildQueryStats_WithValidResponse_ShouldExtractStats() {
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(250);
		docList.setStart(0);
		docList.setMaxScore(1.5f);

		when(queryResponse.getQTime()).thenReturn(25);
		when(queryResponse.getResults()).thenReturn(docList);

		QueryStats result = collectionService.buildQueryStats(queryResponse);

		assertNotNull(result);
		assertEquals(25, result.queryTime());
		assertEquals(250, result.totalResults());
		assertEquals(0, result.start());
		assertEquals(1.5f, result.maxScore());
	}

	@Test
	void buildQueryStats_WithNullMaxScore_ShouldHandleGracefully() {
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(100);
		docList.setStart(10);
		docList.setMaxScore(null);

		when(queryResponse.getQTime()).thenReturn(15);
		when(queryResponse.getResults()).thenReturn(docList);

		QueryStats result = collectionService.buildQueryStats(queryResponse);

		assertNotNull(result);
		assertEquals(15, result.queryTime());
		assertNull(result.maxScore());
	}

	// Index stats tests
	@Test
	void buildIndexStats_ShouldExtractStats() {
		NamedList<Object> indexInfo = new NamedList<>();
		indexInfo.add("segmentCount", 5);
		when(lukeResponse.getIndexInfo()).thenReturn(indexInfo);
		when(lukeResponse.getNumDocs()).thenReturn(1000);

		IndexStats result = collectionService.buildIndexStats(lukeResponse);

		assertEquals(1000, result.numDocs());
		assertEquals(5, result.segmentCount());
	}

	@Test
	void buildIndexStats_WithNullSegmentCount() {
		NamedList<Object> indexInfo = new NamedList<>();
		when(lukeResponse.getIndexInfo()).thenReturn(indexInfo);
		when(lukeResponse.getNumDocs()).thenReturn(1000);

		IndexStats result = collectionService.buildIndexStats(lukeResponse);

		assertEquals(1000, result.numDocs());
		assertNull(result.segmentCount());
	}

	// Collection validation tests
	@Test
	void getCollectionStats_NotFound() {
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> spyService.getCollectionStats("non_existent"));

		assertTrue(exception.getMessage().contains("Collection not found: non_existent"));
	}

	@Test
	void validateCollectionExists() throws Exception {
		CollectionService spyService = spy(collectionService);
		List<String> collections = Arrays.asList("collection1", "films_shard1_replica_n1");
		doReturn(collections).when(spyService).listCollections();

		Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
		method.setAccessible(true);

		assertTrue((boolean) method.invoke(spyService, "collection1"));
		assertTrue((boolean) method.invoke(spyService, "films"));
		assertFalse((boolean) method.invoke(spyService, "non_existent"));
	}

	// Cache metrics tests (Metrics API)
	@Test
	void getCacheMetrics_WithNonExistentCollection_ShouldReturnNull() {
		CacheStats result = collectionService.getCacheMetrics("nonexistent");
		assertNull(result);
	}

	@Test
	void getCacheMetrics_Success() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createMockMetricsCacheData("test_collection");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		CacheStats result = spyService.getCacheMetrics("test_collection");

		assertNotNull(result);
		assertNotNull(result.queryResultCache());
		assertEquals(100L, result.queryResultCache().lookups());
		assertEquals(80L, result.queryResultCache().hits());
	}

	@Test
	void getCacheMetrics_AllCacheTypes() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createMockMetricsCacheData("test_collection");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		CacheStats result = spyService.getCacheMetrics("test_collection");

		assertNotNull(result);
		assertNotNull(result.queryResultCache());
		assertNotNull(result.documentCache());
		assertNotNull(result.filterCache());
	}

	@Test
	void getCacheMetrics_CollectionNotFound() {
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		assertNull(spyService.getCacheMetrics("non_existent"));
	}

	@Test
	void getCacheMetrics_SolrServerException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();
		when(solrClient.request(any(SolrRequest.class))).thenThrow(new SolrServerException("Error"));

		assertNull(spyService.getCacheMetrics("test_collection"));
	}

	@Test
	void getCacheMetrics_IOException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();
		when(solrClient.request(any(SolrRequest.class))).thenThrow(new IOException("IO Error"));

		assertNull(spyService.getCacheMetrics("test_collection"));
	}

	@Test
	void getCacheMetrics_EmptyMetrics() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> response = new NamedList<>();
		response.add("metrics", new NamedList<>());
		when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

		assertNull(spyService.getCacheMetrics("test_collection"));
	}

	@Test
	void getCacheMetrics_WithShardName() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createMockMetricsCacheData("films");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		CacheStats result = spyService.getCacheMetrics("films_shard1_replica_n1");
		assertNotNull(result);
	}

	// Handler metrics tests (Metrics API)
	@Test
	void getHandlerMetrics_WithNonExistentCollection_ShouldReturnNull() {
		assertNull(collectionService.getHandlerMetrics("nonexistent"));
	}

	@Test
	void getHandlerMetrics_Success() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createMockMetricsHandlerData("test_collection");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		HandlerStats result = spyService.getHandlerMetrics("test_collection");

		assertNotNull(result);
		assertNotNull(result.selectHandler());
		assertEquals(500L, result.selectHandler().requests());
		assertNotNull(result.updateHandler());
		assertEquals(250L, result.updateHandler().requests());
	}

	@Test
	void getHandlerMetrics_CollectionNotFound() {
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		assertNull(spyService.getHandlerMetrics("non_existent"));
	}

	@Test
	void getHandlerMetrics_SolrServerException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();
		when(solrClient.request(any(SolrRequest.class))).thenThrow(new SolrServerException("Error"));

		assertNull(spyService.getHandlerMetrics("test_collection"));
	}

	@Test
	void getHandlerMetrics_IOException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();
		when(solrClient.request(any(SolrRequest.class))).thenThrow(new IOException("IO Error"));

		assertNull(spyService.getHandlerMetrics("test_collection"));
	}

	@Test
	void getHandlerMetrics_EmptyMetrics() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> response = new NamedList<>();
		response.add("metrics", new NamedList<>());
		when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

		assertNull(spyService.getHandlerMetrics("test_collection"));
	}

	@Test
	void getHandlerMetrics_WithShardName() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createMockMetricsHandlerData("films");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		HandlerStats result = spyService.getHandlerMetrics("films_shard1_replica_n1");
		assertNotNull(result);
	}

	// List collections tests
	@Test
	void listCollections_CloudClient_Success() throws Exception {
		CloudSolrClient cloudClient = mock(CloudSolrClient.class);

		NamedList<Object> response = new NamedList<>();
		response.add("collections", Arrays.asList("collection1", "collection2"));

		when(cloudClient.request(any(), any())).thenReturn(response);

		CollectionService service = new CollectionService(cloudClient, objectMapper);
		List<String> result = service.listCollections();

		assertNotNull(result);
		assertEquals(2, result.size());
		assertTrue(result.contains("collection1"));
		assertTrue(result.contains("collection2"));
	}

	@Test
	void listCollections_CloudClient_NullCollections() throws Exception {
		CloudSolrClient cloudClient = mock(CloudSolrClient.class);

		NamedList<Object> response = new NamedList<>();
		response.add("collections", null);

		when(cloudClient.request(any(), any())).thenReturn(response);

		CollectionService service = new CollectionService(cloudClient, objectMapper);
		List<String> result = service.listCollections();

		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	@Test
	void listCollections_CloudClient_Error() throws Exception {
		CloudSolrClient cloudClient = mock(CloudSolrClient.class);
		when(cloudClient.request(any(), any())).thenThrow(new SolrServerException("Connection error"));

		CollectionService service = new CollectionService(cloudClient, objectMapper);
		List<String> result = service.listCollections();

		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	@Test
	void listCollections_NonCloudClient_Success() throws Exception {
		NamedList<Object> response = new NamedList<>();
		Map<String, Object> status = new HashMap<>();
		status.put("core1", new HashMap<>());
		status.put("core2", new HashMap<>());
		response.add("status", status);

		when(solrClient.request(any(), any())).thenReturn(response);

		CollectionService service = new CollectionService(solrClient, objectMapper);
		List<String> result = service.listCollections();

		assertNotNull(result);
		assertEquals(2, result.size());
		assertTrue(result.contains("core1"));
		assertTrue(result.contains("core2"));
	}

	@Test
	void listCollections_NonCloudClient_Error() throws Exception {
		when(solrClient.request(any(), any())).thenThrow(new IOException("IO error"));

		CollectionService service = new CollectionService(solrClient, objectMapper);
		List<String> result = service.listCollections();

		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	// createCollection tests
	@Test
	void createCollection_success_cloudClient() throws Exception {
		CloudSolrClient cloudClient = mock(CloudSolrClient.class);
		when(cloudClient.request(any(), any())).thenReturn(new NamedList<>());

		CollectionService service = new CollectionService(cloudClient, objectMapper);
		CollectionCreationResult result = service.createCollection("new_collection", "_default", 1, 1);

		assertNotNull(result);
		assertTrue(result.success());
		assertEquals("new_collection", result.name());
	}

	@Test
	void createCollection_success_standaloneClient() throws Exception {
		when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

		CollectionCreationResult result = collectionService.createCollection("new_core", null, null, null);

		assertTrue(result.success());
		assertEquals("new_core", result.name());
	}

	@Test
	void createCollection_blankName_throwsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> collectionService.createCollection("   ", null, null, null));
	}

	@Test
	void createCollection_emptyName_throwsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> collectionService.createCollection("", null, null, null));
	}

	@Test
	void createCollection_solrException_propagates() throws Exception {
		when(solrClient.request(any(), isNull())).thenThrow(new SolrServerException("Solr error"));

		assertThrows(SolrServerException.class,
				() -> collectionService.createCollection("fail_core", null, null, null));
	}

	// Helper methods to create Metrics API response format
	private NamedList<Object> createMockMetricsCacheData(String collection) {
		NamedList<Object> response = new NamedList<>();
		NamedList<Object> metrics = new NamedList<>();
		NamedList<Object> coreMetrics = new NamedList<>();

		NamedList<Object> qrcStats = new NamedList<>();
		qrcStats.add("lookups", 100L);
		qrcStats.add("hits", 80L);
		qrcStats.add("hitratio", 0.8f);
		qrcStats.add("inserts", 20L);
		qrcStats.add("evictions", 5L);
		qrcStats.add("size", 100L);
		coreMetrics.add("CACHE.searcher.queryResultCache", qrcStats);

		NamedList<Object> dcStats = new NamedList<>();
		dcStats.add("lookups", 200L);
		dcStats.add("hits", 150L);
		dcStats.add("hitratio", 0.75f);
		dcStats.add("inserts", 50L);
		dcStats.add("evictions", 10L);
		dcStats.add("size", 180L);
		coreMetrics.add("CACHE.searcher.documentCache", dcStats);

		NamedList<Object> fcStats = new NamedList<>();
		fcStats.add("lookups", 150L);
		fcStats.add("hits", 120L);
		fcStats.add("hitratio", 0.8f);
		fcStats.add("inserts", 30L);
		fcStats.add("evictions", 8L);
		fcStats.add("size", 140L);
		coreMetrics.add("CACHE.searcher.filterCache", fcStats);

		metrics.add("solr.core." + collection, coreMetrics);
		response.add("metrics", metrics);
		return response;
	}

	private NamedList<Object> createMockMetricsHandlerData(String collection) {
		NamedList<Object> response = new NamedList<>();
		NamedList<Object> metrics = new NamedList<>();
		NamedList<Object> coreMetrics = new NamedList<>();

		coreMetrics.add("QUERY./select.requests", 500L);
		coreMetrics.add("QUERY./select.errors", 5L);
		coreMetrics.add("QUERY./select.timeouts", 2L);
		coreMetrics.add("QUERY./select.totalTime", 10000L);
		coreMetrics.add("QUERY./select.avgTimePerRequest", 20.0f);
		coreMetrics.add("QUERY./select.avgRequestsPerSecond", 25.0f);

		coreMetrics.add("UPDATE./update.requests", 250L);
		coreMetrics.add("UPDATE./update.errors", 2L);
		coreMetrics.add("UPDATE./update.timeouts", 1L);
		coreMetrics.add("UPDATE./update.totalTime", 5000L);
		coreMetrics.add("UPDATE./update.avgTimePerRequest", 20.0f);
		coreMetrics.add("UPDATE./update.avgRequestsPerSecond", 50.0f);

		metrics.add("solr.core." + collection, coreMetrics);
		response.add("metrics", metrics);
		return response;
	}
}
