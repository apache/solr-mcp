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
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		collectionService = new CollectionService(solrClient, objectMapper);
	}

	@Test
	void constructor_ShouldInitializeWithSolrClient() {
		assertNotNull(collectionService);
	}

	@Test
	void listCollections_WithCloudSolrClient_ShouldReturnCollections() throws Exception {
		CollectionService cloudService = new CollectionService(cloudSolrClient, objectMapper);
		assertNotNull(cloudService, "Should be able to construct service with CloudSolrClient");
	}

	@Test
	void listCollections_WhenExceptionOccurs_ShouldReturnEmptyList() throws Exception {
		assertNotNull(collectionService, "Service should be constructed successfully");
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
		assertNotNull(result.errorMessage());
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
	void checkHealth_WithSlowResponse_ShouldCaptureResponseTime() throws Exception {
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(1000);

		when(solrClient.ping("slow_collection")).thenReturn(pingResponse);
		when(pingResponse.getElapsedTime()).thenReturn(5000L);
		when(solrClient.query(eq("slow_collection"), any())).thenReturn(queryResponse);
		when(queryResponse.getResults()).thenReturn(docList);

		SolrHealthStatus result = collectionService.checkHealth("slow_collection");

		assertNotNull(result);
		assertTrue(result.isHealthy());
		assertEquals(5000, result.responseTime());
		assertTrue(result.responseTime() > 1000, "Should capture slow response time");
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
		assertEquals(100, result.totalResults());
		assertEquals(10, result.start());
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

	@Test
	void validateCollectionExists_WithException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
		method.setAccessible(true);

		assertFalse((boolean) method.invoke(spyService, "any_collection"));
	}

	// Cache metrics tests using Metrics API format
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
	}

	@Test
	void getCacheMetrics_CollectionNotFound() {
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		CacheStats result = spyService.getCacheMetrics("non_existent");
		assertNull(result);
	}

	@Test
	void getCacheMetrics_SolrServerException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		when(solrClient.request(any(SolrRequest.class))).thenThrow(new SolrServerException("Error"));

		CacheStats result = spyService.getCacheMetrics("test_collection");
		assertNull(result);
	}

	@Test
	void getCacheMetrics_IOException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		when(solrClient.request(any(SolrRequest.class))).thenThrow(new IOException("IO Error"));

		CacheStats result = spyService.getCacheMetrics("test_collection");
		assertNull(result);
	}

	@Test
	void getCacheMetrics_EmptyMetrics() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = new NamedList<>();
		metricsResponse.add("metrics", new NamedList<>());
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		CacheStats result = spyService.getCacheMetrics("test_collection");
		assertNull(result);
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

	@Test
	void getCacheMetrics_AllCacheTypes() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createCompleteMockMetricsCacheData("test_collection");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		CacheStats result = spyService.getCacheMetrics("test_collection");

		assertNotNull(result);
		assertNotNull(result.queryResultCache());
		assertNotNull(result.documentCache());
		assertNotNull(result.filterCache());
	}

	@Test
	void getCacheMetrics_NullMetricsKey() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = new NamedList<>();
		metricsResponse.add("metrics", null);
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		CacheStats result = spyService.getCacheMetrics("test_collection");
		assertNull(result);
	}

	@Test
	void isCacheStatsEmpty() throws Exception {
		Method method = CollectionService.class.getDeclaredMethod("isCacheStatsEmpty", CacheStats.class);
		method.setAccessible(true);

		CacheStats emptyStats = new CacheStats(null, null, null);
		assertTrue((boolean) method.invoke(collectionService, emptyStats));
		assertTrue((boolean) method.invoke(collectionService, (CacheStats) null));

		CacheStats nonEmptyStats = new CacheStats(new CacheInfo(100L, null, null, null, null, null), null, null);
		assertFalse((boolean) method.invoke(collectionService, nonEmptyStats));
	}

	// Handler metrics tests using Metrics API format
	@Test
	void getHandlerMetrics_WithNonExistentCollection_ShouldReturnNull() {
		HandlerStats result = collectionService.getHandlerMetrics("nonexistent");
		assertNull(result);
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
	}

	@Test
	void getHandlerMetrics_CollectionNotFound() {
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		HandlerStats result = spyService.getHandlerMetrics("non_existent");
		assertNull(result);
	}

	@Test
	void getHandlerMetrics_SolrServerException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		when(solrClient.request(any(SolrRequest.class))).thenThrow(new SolrServerException("Error"));

		HandlerStats result = spyService.getHandlerMetrics("test_collection");
		assertNull(result);
	}

	@Test
	void getHandlerMetrics_IOException() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		when(solrClient.request(any(SolrRequest.class))).thenThrow(new IOException("IO Error"));

		HandlerStats result = spyService.getHandlerMetrics("test_collection");
		assertNull(result);
	}

	@Test
	void getHandlerMetrics_EmptyMetrics() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = new NamedList<>();
		metricsResponse.add("metrics", new NamedList<>());
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		HandlerStats result = spyService.getHandlerMetrics("test_collection");
		assertNull(result);
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

	@Test
	void getHandlerMetrics_BothHandlers() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = createCompleteMetricsHandlerData("test_collection");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		HandlerStats result = spyService.getHandlerMetrics("test_collection");

		assertNotNull(result);
		assertNotNull(result.selectHandler());
		assertNotNull(result.updateHandler());
		assertEquals(500L, result.selectHandler().requests());
		assertEquals(250L, result.updateHandler().requests());
	}

	@Test
	void getHandlerMetrics_NullMetricsKey() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> metricsResponse = new NamedList<>();
		metricsResponse.add("metrics", null);
		when(solrClient.request(any(SolrRequest.class))).thenReturn(metricsResponse);

		HandlerStats result = spyService.getHandlerMetrics("test_collection");
		assertNull(result);
	}

	@Test
	void isHandlerStatsEmpty() throws Exception {
		Method method = CollectionService.class.getDeclaredMethod("isHandlerStatsEmpty", HandlerStats.class);
		method.setAccessible(true);

		HandlerStats emptyStats = new HandlerStats(null, null);
		assertTrue((boolean) method.invoke(collectionService, emptyStats));
		assertTrue((boolean) method.invoke(collectionService, (HandlerStats) null));

		HandlerStats nonEmptyStats = new HandlerStats(new HandlerInfo(100L, null, null, null, null, null), null);
		assertFalse((boolean) method.invoke(collectionService, nonEmptyStats));
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
		NamedList<Object> status = new NamedList<>();

		NamedList<Object> core1Status = new NamedList<>();
		NamedList<Object> core2Status = new NamedList<>();

		status.add("core1", core1Status);
		status.add("core2", core2Status);
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

	// Helper methods for Metrics API response format
	private NamedList<Object> createMockMetricsCacheData(String collection) {
		NamedList<Object> metricsResponse = new NamedList<>();
		NamedList<Object> metrics = new NamedList<>();
		NamedList<Object> coreMetrics = new NamedList<>();

		coreMetrics.add("CACHE.searcher.queryResultCache", createCacheInfoNamedList(100L, 80L, 0.8f, 20L, 5L, 100L));

		metrics.add("solr.core." + collection, coreMetrics);
		metricsResponse.add("metrics", metrics);

		return metricsResponse;
	}

	private NamedList<Object> createCompleteMockMetricsCacheData(String collection) {
		NamedList<Object> metricsResponse = new NamedList<>();
		NamedList<Object> metrics = new NamedList<>();
		NamedList<Object> coreMetrics = new NamedList<>();

		coreMetrics.add("CACHE.searcher.queryResultCache", createCacheInfoNamedList(100L, 80L, 0.8f, 20L, 5L, 100L));
		coreMetrics.add("CACHE.searcher.documentCache", createCacheInfoNamedList(200L, 150L, 0.75f, 50L, 10L, 180L));
		coreMetrics.add("CACHE.searcher.filterCache", createCacheInfoNamedList(150L, 120L, 0.8f, 30L, 8L, 140L));

		metrics.add("solr.core." + collection, coreMetrics);
		metricsResponse.add("metrics", metrics);

		return metricsResponse;
	}

	private NamedList<Object> createCacheInfoNamedList(Long lookups, Long hits, Float hitratio, Long inserts,
			Long evictions, Long size) {
		NamedList<Object> cache = new NamedList<>();
		cache.add("lookups", lookups);
		cache.add("hits", hits);
		cache.add("hitratio", hitratio);
		cache.add("inserts", inserts);
		cache.add("evictions", evictions);
		cache.add("size", size);
		return cache;
	}

	private NamedList<Object> createMockMetricsHandlerData(String collection) {
		NamedList<Object> metricsResponse = new NamedList<>();
		NamedList<Object> metrics = new NamedList<>();
		NamedList<Object> coreMetrics = new NamedList<>();

		coreMetrics.add("QUERY./select.requests", 500L);
		coreMetrics.add("QUERY./select.errors", 5L);
		coreMetrics.add("QUERY./select.timeouts", 2L);
		coreMetrics.add("QUERY./select.totalTime", 10000L);
		coreMetrics.add("QUERY./select.avgTimePerRequest", 20.0f);
		coreMetrics.add("QUERY./select.avgRequestsPerSecond", 25.0f);

		metrics.add("solr.core." + collection, coreMetrics);
		metricsResponse.add("metrics", metrics);

		return metricsResponse;
	}

	private NamedList<Object> createCompleteMetricsHandlerData(String collection) {
		NamedList<Object> metricsResponse = new NamedList<>();
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
		metricsResponse.add("metrics", metrics);

		return metricsResponse;
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
		assertNotNull(result.createdAt());
	}

	@Test
	void createCollection_success_standaloneClient() throws Exception {
		when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

		CollectionCreationResult result = collectionService.createCollection("new_core", null, null, null);

		assertNotNull(result);
		assertTrue(result.success());
		assertEquals("new_core", result.name());
		assertNotNull(result.createdAt());
	}

	@Test
	void createCollection_defaultsApplied() throws Exception {
		CloudSolrClient cloudClient = mock(CloudSolrClient.class);
		when(cloudClient.request(any(), any())).thenReturn(new NamedList<>());

		CollectionService service = new CollectionService(cloudClient, objectMapper);
		CollectionCreationResult result = service.createCollection("defaults_collection", null, null, null);

		assertTrue(result.success());
		assertEquals("defaults_collection", result.name());
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
}
