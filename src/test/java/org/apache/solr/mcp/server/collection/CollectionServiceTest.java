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
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class CollectionServiceTest {

	@Mock
	private SolrClient solrClient;

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

	// Constructor tests
	@Test
	void constructor_ShouldInitializeWithSolrClient() {
		assertNotNull(collectionService);
	}

	@Test
	void listCollections_WhenExceptionOccurs_ShouldReturnEmptyList() throws Exception {
		// Given - mock throws exception
		when(solrClient.request(any(), any())).thenThrow(new SolrServerException("Connection error"));

		// When
		List<String> result = collectionService.listCollections();

		// Then
		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	// Collection name extraction tests
	@Test
	void extractCollectionName_WithShardName_ShouldExtractCollectionName() {
		// Given
		String shardName = "films_shard1_replica_n1";

		// When
		String result = collectionService.extractCollectionName(shardName);

		// Then
		assertEquals("films", result);
	}

	@Test
	void extractCollectionName_WithMultipleShards_ShouldExtractCorrectly() {
		// Given & When & Then
		assertEquals("products", collectionService.extractCollectionName("products_shard2_replica_n3"));
		assertEquals("users", collectionService.extractCollectionName("users_shard5_replica_n10"));
	}

	@Test
	void extractCollectionName_WithSimpleCollectionName_ShouldReturnUnchanged() {
		// Given
		String simpleName = "simple_collection";

		// When
		String result = collectionService.extractCollectionName(simpleName);

		// Then
		assertEquals("simple_collection", result);
	}

	@Test
	void extractCollectionName_WithNullInput_ShouldReturnNull() {
		// When
		String result = collectionService.extractCollectionName(null);

		// Then
		assertNull(result);
	}

	@Test
	void extractCollectionName_WithEmptyString_ShouldReturnEmptyString() {
		// When
		String result = collectionService.extractCollectionName("");

		// Then
		assertEquals("", result);
	}

	@Test
	void extractCollectionName_WithCollectionNameContainingUnderscore_ShouldOnlyExtractBeforeShard() {
		// Given - collection name itself contains underscore
		String complexName = "my_complex_collection_shard1_replica_n1";

		// When
		String result = collectionService.extractCollectionName(complexName);

		// Then
		assertEquals("my_complex_collection", result);
	}

	@Test
	void extractCollectionName_EdgeCases_ShouldHandleCorrectly() {
		// Test various edge cases
		assertEquals("a", collectionService.extractCollectionName("a_shard1"));
		assertEquals("collection", collectionService.extractCollectionName("collection_shard"));
		assertEquals("test_name", collectionService.extractCollectionName("test_name"));
		assertEquals("", collectionService.extractCollectionName("_shard1"));
	}

	@Test
	void extractCollectionName_WithShardInMiddleOfName_ShouldExtractCorrectly() {
		// Given - "shard" appears in collection name but not as suffix pattern
		String name = "resharding_tasks";

		// When
		String result = collectionService.extractCollectionName(name);

		// Then
		assertEquals("resharding_tasks", result, "Should not extract when '_shard' is not followed by number");
	}

	@Test
	void extractCollectionName_WithMultipleOccurrencesOfShard_ShouldUseFirst() {
		// Given
		String name = "data_shard1_shard2_replica_n1";

		// When
		String result = collectionService.extractCollectionName(name);

		// Then
		assertEquals("data", result, "Should use first occurrence of '_shard'");
	}

	// Health check tests
	@Test
	void checkHealth_WithHealthyCollection_ShouldReturnHealthyStatus() throws Exception {
		// Given
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(100);

		when(solrClient.ping("test_collection")).thenReturn(pingResponse);
		when(pingResponse.getElapsedTime()).thenReturn(10L);
		when(solrClient.query(eq("test_collection"), any())).thenReturn(queryResponse);
		when(queryResponse.getResults()).thenReturn(docList);

		// When
		SolrHealthStatus result = collectionService.checkHealth("test_collection");

		// Then
		assertNotNull(result);
		assertTrue(result.isHealthy());
		assertNull(result.errorMessage());
		assertEquals(10L, result.responseTime());
		assertEquals(100L, result.totalDocuments());
		assertEquals("test_collection", result.collection());
	}

	@Test
	void checkHealth_WithUnhealthyCollection_ShouldReturnUnhealthyStatus() throws Exception {
		// Given
		when(solrClient.ping("unhealthy_collection")).thenThrow(new SolrServerException("Connection failed"));

		// When
		SolrHealthStatus result = collectionService.checkHealth("unhealthy_collection");

		// Then
		assertNotNull(result);
		assertFalse(result.isHealthy());
		assertNotNull(result.errorMessage());
		assertTrue(result.errorMessage().contains("Connection failed"));
		assertNull(result.responseTime());
		assertNull(result.totalDocuments());
		assertEquals("unhealthy_collection", result.collection());
	}

	@Test
	void checkHealth_WhenPingSucceedsButQueryFails_ShouldReturnUnhealthyStatus() throws Exception {
		// Given
		when(solrClient.ping("test_collection")).thenReturn(pingResponse);
		when(solrClient.query(eq("test_collection"), any())).thenThrow(new IOException("Query failed"));

		// When
		SolrHealthStatus result = collectionService.checkHealth("test_collection");

		// Then
		assertNotNull(result);
		assertFalse(result.isHealthy());
		assertNotNull(result.errorMessage());
		assertTrue(result.errorMessage().contains("Query failed"));
	}

	@Test
	void checkHealth_WithEmptyCollection_ShouldReturnHealthyWithZeroDocuments() throws Exception {
		// Given
		SolrDocumentList emptyDocList = new SolrDocumentList();
		emptyDocList.setNumFound(0);

		when(solrClient.ping("empty_collection")).thenReturn(pingResponse);
		when(pingResponse.getElapsedTime()).thenReturn(5L);
		when(solrClient.query(eq("empty_collection"), any())).thenReturn(queryResponse);
		when(queryResponse.getResults()).thenReturn(emptyDocList);

		// When
		SolrHealthStatus result = collectionService.checkHealth("empty_collection");

		// Then
		assertNotNull(result);
		assertTrue(result.isHealthy());
		assertEquals(0L, result.totalDocuments());
		assertEquals(5, result.responseTime());
	}

	@Test
	void checkHealth_WithSlowResponse_ShouldCaptureResponseTime() throws Exception {
		// Given
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(1000);

		when(solrClient.ping("slow_collection")).thenReturn(pingResponse);
		when(pingResponse.getElapsedTime()).thenReturn(5000L); // 5 seconds
		when(solrClient.query(eq("slow_collection"), any())).thenReturn(queryResponse);
		when(queryResponse.getResults()).thenReturn(docList);

		// When
		SolrHealthStatus result = collectionService.checkHealth("slow_collection");

		// Then
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
		// Given
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(250);
		docList.setStart(0);
		docList.setMaxScore(1.5f);

		when(queryResponse.getQTime()).thenReturn(25);
		when(queryResponse.getResults()).thenReturn(docList);

		// When
		QueryStats result = collectionService.buildQueryStats(queryResponse);

		// Then
		assertNotNull(result);
		assertEquals(25, result.queryTime());
		assertEquals(250, result.totalResults());
		assertEquals(0, result.start());
		assertEquals(1.5f, result.maxScore());
	}

	@Test
	void buildQueryStats_WithNullMaxScore_ShouldHandleGracefully() {
		// Given
		SolrDocumentList docList = new SolrDocumentList();
		docList.setNumFound(100);
		docList.setStart(10);
		docList.setMaxScore(null);

		when(queryResponse.getQTime()).thenReturn(15);
		when(queryResponse.getResults()).thenReturn(docList);

		// When
		QueryStats result = collectionService.buildQueryStats(queryResponse);

		// Then
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

	// Cache metrics tests
	@Test
	void getCacheMetrics_WithNonExistentCollection_ShouldReturnNull() {
		// Given - listCollections returns empty (collection not found)
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		// When
		CacheStats result = spyService.getCacheMetrics("nonexistent");

		// Then
		assertNull(result);
	}

	@Test
	void getCacheMetrics_Success() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		NamedList<Object> mbeans = createMockCacheData();
		when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

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
	void getCacheMetrics_EmptyStats() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		// Metrics response with core metrics that contain no cache keys
		NamedList<Object> response = wrapInMetricsResponse(new NamedList<>());
		when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

		CacheStats result = spyService.getCacheMetrics("test_collection");

		assertNull(result);
	}

	@Test
	void getCacheMetrics_WithShardName() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

		NamedList<Object> response = wrapInMetricsResponse(createCacheCoreMetrics(), "films");
		when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

		CacheStats result = spyService.getCacheMetrics("films_shard1_replica_n1");

		assertNotNull(result);
	}

	@Test
	void extractCacheStats() throws Exception {
		NamedList<Object> coreMetrics = createCacheCoreMetrics();
		Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
		method.setAccessible(true);

		CacheStats result = (CacheStats) method.invoke(collectionService, coreMetrics);

		assertNotNull(result.queryResultCache());
		assertEquals(100L, result.queryResultCache().lookups());
		assertEquals(80L, result.queryResultCache().hits());
	}

	@Test
	void extractCacheStats_AllCacheTypes() throws Exception {
		NamedList<Object> coreMetrics = createCompleteCacheCoreMetrics();
		Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
		method.setAccessible(true);

		CacheStats result = (CacheStats) method.invoke(collectionService, coreMetrics);

		assertNotNull(result.queryResultCache());
		assertNotNull(result.documentCache());
		assertNotNull(result.filterCache());
	}

	@Test
	void extractCacheStats_NoCacheKeys() throws Exception {
		// Core metrics with no cache keys at all
		NamedList<Object> coreMetrics = new NamedList<>();

		Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
		method.setAccessible(true);

		CacheStats result = (CacheStats) method.invoke(collectionService, coreMetrics);

		assertNotNull(result);
		assertNull(result.queryResultCache());
		assertNull(result.documentCache());
		assertNull(result.filterCache());
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

	// Handler metrics tests
	@Test
	void getHandlerMetrics_WithNonExistentCollection_ShouldReturnNull() {
		// Given - listCollections returns empty (collection not found)
		CollectionService spyService = spy(collectionService);
		doReturn(Collections.emptyList()).when(spyService).listCollections();

		// When
		HandlerStats result = spyService.getHandlerMetrics("nonexistent");

		// Then
		assertNull(result);
	}

	@Test
	void getHandlerMetrics_Success() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		// getHandlerMetrics makes two fetchMetrics calls (select then update);
		// return select handler data for both calls (second has no update keys -> null)
		when(solrClient.request(any(SolrRequest.class))).thenReturn(createMockSelectHandlerData());

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
	void getHandlerMetrics_EmptyStats() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

		// Metrics response with core metrics that contain no handler keys
		NamedList<Object> response = wrapInMetricsResponse(new NamedList<>());
		when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

		HandlerStats result = spyService.getHandlerMetrics("test_collection");

		assertNull(result);
	}

	@Test
	void getHandlerMetrics_WithShardName() throws Exception {
		CollectionService spyService = spy(collectionService);
		doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

		when(solrClient.request(any(SolrRequest.class))).thenReturn(createMockSelectHandlerData("films"));

		HandlerStats result = spyService.getHandlerMetrics("films_shard1_replica_n1");

		assertNotNull(result);
	}

	@Test
	void extractFlatHandlerInfo_SelectHandler() throws Exception {
		NamedList<Object> coreMetrics = createSelectHandlerCoreMetrics();
		Method method = CollectionService.class.getDeclaredMethod("extractFlatHandlerInfo", NamedList.class,
				String.class);
		method.setAccessible(true);

		HandlerInfo result = (HandlerInfo) method.invoke(collectionService, coreMetrics, "QUERY./select.");

		assertNotNull(result);
		assertEquals(500L, result.requests());
		assertEquals(5L, result.errors());
		assertEquals(2L, result.timeouts());
		assertEquals(10000L, result.totalTime());
		// avgTimePerRequest computed: 10000/500 = 20.0
		assertEquals(20.0f, result.avgTimePerRequest());
	}

	@Test
	void extractFlatHandlerInfo_UpdateHandler() throws Exception {
		NamedList<Object> coreMetrics = createUpdateHandlerCoreMetrics();
		Method method = CollectionService.class.getDeclaredMethod("extractFlatHandlerInfo", NamedList.class,
				String.class);
		method.setAccessible(true);

		HandlerInfo result = (HandlerInfo) method.invoke(collectionService, coreMetrics, "UPDATE./update.");

		assertNotNull(result);
		assertEquals(250L, result.requests());
		assertEquals(2L, result.errors());
	}

	@Test
	void extractFlatHandlerInfo_NoHandlerKeys() throws Exception {
		NamedList<Object> coreMetrics = new NamedList<>();
		Method method = CollectionService.class.getDeclaredMethod("extractFlatHandlerInfo", NamedList.class,
				String.class);
		method.setAccessible(true);

		HandlerInfo result = (HandlerInfo) method.invoke(collectionService, coreMetrics, "QUERY./select.");

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
	void listCollections_Success() throws Exception {
		NamedList<Object> response = new NamedList<>();
		response.add("collections", Arrays.asList("collection1", "collection2"));

		when(solrClient.request(any(), any())).thenReturn(response);

		List<String> result = collectionService.listCollections();

		assertNotNull(result);
		assertEquals(2, result.size());
		assertTrue(result.contains("collection1"));
		assertTrue(result.contains("collection2"));
	}

	@Test
	void listCollections_NullCollections() throws Exception {
		NamedList<Object> response = new NamedList<>();
		response.add("collections", null);

		when(solrClient.request(any(), any())).thenReturn(response);

		List<String> result = collectionService.listCollections();

		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	@Test
	void listCollections_Error() throws Exception {
		when(solrClient.request(any(), any())).thenThrow(new SolrServerException("Connection error"));

		List<String> result = collectionService.listCollections();

		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	@Test
	void listCollections_IOError() throws Exception {
		when(solrClient.request(any(), any())).thenThrow(new IOException("IO error"));

		List<String> result = collectionService.listCollections();

		assertNotNull(result);
		assertTrue(result.isEmpty());
	}

	// Helper methods — mock the Solr Metrics API response format:
	// response -> "metrics" -> "solr.core.<name>" -> "CACHE.searcher.xxx" /
	// "HANDLER./xxx"

	// Core metrics builders (unwrapped — used by reflection tests for extract*
	// methods)
	private NamedList<Object> createCacheCoreMetrics() {
		NamedList<Object> coreMetrics = new NamedList<>();

		NamedList<Object> queryResultCache = new NamedList<>();
		queryResultCache.add("lookups", 100L);
		queryResultCache.add("hits", 80L);
		queryResultCache.add("hitratio", 0.8f);
		queryResultCache.add("inserts", 20L);
		queryResultCache.add("evictions", 5L);
		queryResultCache.add("size", 100L);
		coreMetrics.add("CACHE.searcher.queryResultCache", queryResultCache);

		return coreMetrics;
	}

	private NamedList<Object> createCompleteCacheCoreMetrics() {
		NamedList<Object> coreMetrics = createCacheCoreMetrics();

		NamedList<Object> documentCache = new NamedList<>();
		documentCache.add("lookups", 200L);
		documentCache.add("hits", 150L);
		documentCache.add("hitratio", 0.75f);
		documentCache.add("inserts", 50L);
		documentCache.add("evictions", 10L);
		documentCache.add("size", 180L);
		coreMetrics.add("CACHE.searcher.documentCache", documentCache);

		NamedList<Object> filterCache = new NamedList<>();
		filterCache.add("lookups", 150L);
		filterCache.add("hits", 120L);
		filterCache.add("hitratio", 0.8f);
		filterCache.add("inserts", 30L);
		filterCache.add("evictions", 8L);
		filterCache.add("size", 140L);
		coreMetrics.add("CACHE.searcher.filterCache", filterCache);

		return coreMetrics;
	}

	// Handler metrics use flat keys in the Metrics API (e.g.
	// QUERY./select.requests)
	private NamedList<Object> createSelectHandlerCoreMetrics() {
		NamedList<Object> coreMetrics = new NamedList<>();
		coreMetrics.add("QUERY./select.requests", 500L);
		coreMetrics.add("QUERY./select.errors", 5L);
		coreMetrics.add("QUERY./select.timeouts", 2L);
		coreMetrics.add("QUERY./select.totalTime", 10000L);
		return coreMetrics;
	}

	private NamedList<Object> createUpdateHandlerCoreMetrics() {
		NamedList<Object> coreMetrics = new NamedList<>();
		coreMetrics.add("UPDATE./update.requests", 250L);
		coreMetrics.add("UPDATE./update.errors", 2L);
		coreMetrics.add("UPDATE./update.timeouts", 1L);
		coreMetrics.add("UPDATE./update.totalTime", 5000L);
		return coreMetrics;
	}

	// Wrapped response builders (used by tests that go through
	// getCacheMetrics/getHandlerMetrics)
	private NamedList<Object> createMockCacheData() {
		return wrapInMetricsResponse(createCacheCoreMetrics());
	}

	private NamedList<Object> createMockSelectHandlerData() {
		return wrapInMetricsResponse(createSelectHandlerCoreMetrics());
	}

	private NamedList<Object> createMockSelectHandlerData(String collection) {
		return wrapInMetricsResponse(createSelectHandlerCoreMetrics(), collection);
	}

	private NamedList<Object> createMockUpdateHandlerData() {
		return wrapInMetricsResponse(createUpdateHandlerCoreMetrics());
	}

	private NamedList<Object> wrapInMetricsResponse(NamedList<Object> coreMetrics) {
		return wrapInMetricsResponse(coreMetrics, "test_collection");
	}

	private NamedList<Object> wrapInMetricsResponse(NamedList<Object> coreMetrics, String collection) {
		NamedList<Object> metrics = new NamedList<>();
		metrics.add("solr.core." + collection + ".shard1.replica_n1", coreMetrics);
		NamedList<Object> response = new NamedList<>();
		response.add("metrics", metrics);
		return response;
	}

	// createCollection tests
	@Test
	void createCollection_success() throws Exception {
		when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

		CollectionCreationResult result = collectionService.createCollection("new_collection", "_default", 1, 1);

		assertNotNull(result);
		assertTrue(result.success());
		assertEquals("new_collection", result.name());
		assertNotNull(result.createdAt());
	}

	@Test
	void createCollection_defaultsApplied() throws Exception {
		when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

		CollectionCreationResult result = collectionService.createCollection("defaults_collection", null, null, null);

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
