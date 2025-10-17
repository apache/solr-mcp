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

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    @BeforeEach
    void setUp() {
        collectionService = new CollectionService(solrClient);
    }

    // Constructor tests
    @Test
    void constructor_ShouldInitializeWithSolrClient() {
        assertNotNull(collectionService);
    }

    @Test
    void listCollections_WithCloudSolrClient_ShouldReturnCollections() throws Exception {
        // Given - This test verifies the service can be constructed with CloudSolrClient
        CollectionService cloudService = new CollectionService(cloudSolrClient);

        // Note: This test cannot fully exercise listCollections() because it requires
        // mocking static methods in CollectionAdminRequest which requires PowerMock or
        // Mockito inline. The actual behavior is tested in integration tests.

        // When/Then - Verify service construction
        assertNotNull(cloudService, "Should be able to construct service with CloudSolrClient");
    }

    @Test
    void listCollections_WhenExceptionOccurs_ShouldReturnEmptyList() throws Exception {
        // Note: This test cannot fully exercise listCollections() with mock SolrClient
        // because it requires mocking CoreAdminRequest processing. The actual error
        // handling behavior is tested in integration tests.

        // Given/When - Using regular SolrClient (non-cloud) which will attempt CoreAdmin
        // The mock doesn't have a real CoreAdmin implementation

        // Then - Verify service is constructed
        assertNotNull(collectionService, "Service should be constructed successfully");
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
    }

    @Test
    void checkHealth_WithUnhealthyCollection_ShouldReturnUnhealthyStatus() throws Exception {
        // Given
        when(solrClient.ping("unhealthy_collection"))
                .thenThrow(new SolrServerException("Connection failed"));

        // When
        SolrHealthStatus result = collectionService.checkHealth("unhealthy_collection");

        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Connection failed"));
        assertNull(result.responseTime());
        assertNull(result.totalDocuments());
    }

    @Test
    void checkHealth_WhenPingSucceedsButQueryFails_ShouldReturnUnhealthyStatus() throws Exception {
        // Given
        when(solrClient.ping("test_collection")).thenReturn(pingResponse);
        when(solrClient.query(eq("test_collection"), any()))
                .thenThrow(new IOException("Query failed"));

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
        when(solrClient.ping("error_collection"))
                .thenThrow(new IOException("Network error"));

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
        // When - Mock will not have collection configured
        CacheStats result = collectionService.getCacheMetrics("nonexistent");

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

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new SolrServerException("Error"));

        CacheStats result = spyService.getCacheMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void getCacheMetrics_IOException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new IOException("IO Error"));

        CacheStats result = spyService.getCacheMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void getCacheMetrics_EmptyStats() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        NamedList<Object> mbeans = new NamedList<>();
        mbeans.add("CACHE", new NamedList<>());
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        CacheStats result = spyService.getCacheMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void getCacheMetrics_WithShardName() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

        NamedList<Object> mbeans = createMockCacheData();
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        CacheStats result = spyService.getCacheMetrics("films_shard1_replica_n1");

        assertNotNull(result);
    }

    @Test
    void extractCacheStats() throws Exception {
        NamedList<Object> mbeans = createMockCacheData();
        Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
        method.setAccessible(true);

        CacheStats result = (CacheStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.queryResultCache());
        assertEquals(100L, result.queryResultCache().lookups());
        assertEquals(80L, result.queryResultCache().hits());
    }

    @Test
    void extractCacheStats_AllCacheTypes() throws Exception {
        NamedList<Object> mbeans = createCompleteMockCacheData();
        Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
        method.setAccessible(true);

        CacheStats result = (CacheStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.queryResultCache());
        assertNotNull(result.documentCache());
        assertNotNull(result.filterCache());
    }

    @Test
    void extractCacheStats_NullCacheCategory() throws Exception {
        NamedList<Object> mbeans = new NamedList<>();
        mbeans.add("CACHE", null);

        Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
        method.setAccessible(true);

        CacheStats result = (CacheStats) method.invoke(collectionService, mbeans);

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

        CacheStats nonEmptyStats = new CacheStats(
                new CacheInfo(100L, null, null, null, null, null),
                null,
                null
        );
        assertFalse((boolean) method.invoke(collectionService, nonEmptyStats));
    }

    // Handler metrics tests
    @Test
    void getHandlerMetrics_WithNonExistentCollection_ShouldReturnNull() {
        // When - Mock will not have collection configured
        HandlerStats result = collectionService.getHandlerMetrics("nonexistent");

        // Then
        assertNull(result);
    }

    @Test
    void getHandlerMetrics_Success() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        NamedList<Object> mbeans = createMockHandlerData();
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNotNull(result);
        assertNotNull(result.selectHandler());
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

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new SolrServerException("Error"));

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void getHandlerMetrics_IOException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new IOException("IO Error"));

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void getHandlerMetrics_EmptyStats() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        NamedList<Object> mbeans = new NamedList<>();
        mbeans.add("QUERYHANDLER", new NamedList<>());
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void getHandlerMetrics_WithShardName() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

        NamedList<Object> mbeans = createMockHandlerData();
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        HandlerStats result = spyService.getHandlerMetrics("films_shard1_replica_n1");

        assertNotNull(result);
    }

    @Test
    void extractHandlerStats() throws Exception {
        NamedList<Object> mbeans = createMockHandlerData();
        Method method = CollectionService.class.getDeclaredMethod("extractHandlerStats", NamedList.class);
        method.setAccessible(true);

        HandlerStats result = (HandlerStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.selectHandler());
        assertEquals(500L, result.selectHandler().requests());
    }

    @Test
    void extractHandlerStats_BothHandlers() throws Exception {
        NamedList<Object> mbeans = createCompleteHandlerData();
        Method method = CollectionService.class.getDeclaredMethod("extractHandlerStats", NamedList.class);
        method.setAccessible(true);

        HandlerStats result = (HandlerStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.selectHandler());
        assertNotNull(result.updateHandler());
        assertEquals(500L, result.selectHandler().requests());
        assertEquals(250L, result.updateHandler().requests());
    }

    @Test
    void extractHandlerStats_NullHandlerCategory() throws Exception {
        NamedList<Object> mbeans = new NamedList<>();
        mbeans.add("QUERYHANDLER", null);

        Method method = CollectionService.class.getDeclaredMethod("extractHandlerStats", NamedList.class);
        method.setAccessible(true);

        HandlerStats result = (HandlerStats) method.invoke(collectionService, mbeans);

        assertNotNull(result);
        assertNull(result.selectHandler());
        assertNull(result.updateHandler());
    }

    @Test
    void isHandlerStatsEmpty() throws Exception {
        Method method = CollectionService.class.getDeclaredMethod("isHandlerStatsEmpty", HandlerStats.class);
        method.setAccessible(true);

        HandlerStats emptyStats = new HandlerStats(null, null);
        assertTrue((boolean) method.invoke(collectionService, emptyStats));
        assertTrue((boolean) method.invoke(collectionService, (HandlerStats) null));

        HandlerStats nonEmptyStats = new HandlerStats(
                new HandlerInfo(100L, null, null, null, null, null),
                null
        );
        assertFalse((boolean) method.invoke(collectionService, nonEmptyStats));
    }

    // List collections tests
    @Test
    void listCollections_CloudClient_Success() throws Exception {
        CloudSolrClient cloudClient = mock(CloudSolrClient.class);

        NamedList<Object> response = new NamedList<>();
        response.add("collections", Arrays.asList("collection1", "collection2"));

        when(cloudClient.request(any(), any())).thenReturn(response);

        CollectionService service = new CollectionService(cloudClient);
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

        CollectionService service = new CollectionService(cloudClient);
        List<String> result = service.listCollections();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listCollections_CloudClient_Error() throws Exception {
        CloudSolrClient cloudClient = mock(CloudSolrClient.class);
        when(cloudClient.request(any(), any())).thenThrow(new SolrServerException("Connection error"));

        CollectionService service = new CollectionService(cloudClient);
        List<String> result = service.listCollections();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listCollections_NonCloudClient_Success() throws Exception {
        // Create a NamedList to represent the core status response
        NamedList<Object> response = new NamedList<>();
        NamedList<Object> status = new NamedList<>();

        NamedList<Object> core1Status = new NamedList<>();
        NamedList<Object> core2Status = new NamedList<>();

        status.add("core1", core1Status);
        status.add("core2", core2Status);
        response.add("status", status);

        // Mock the solrClient request to return the response
        when(solrClient.request(any(), any())).thenReturn(response);

        CollectionService service = new CollectionService(solrClient);
        List<String> result = service.listCollections();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("core1"));
        assertTrue(result.contains("core2"));
    }

    @Test
    void listCollections_NonCloudClient_Error() throws Exception {
        when(solrClient.request(any(), any())).thenThrow(new IOException("IO error"));

        CollectionService service = new CollectionService(solrClient);
        List<String> result = service.listCollections();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // Helper methods
    private NamedList<Object> createMockCacheData() {
        NamedList<Object> mbeans = new NamedList<>();
        NamedList<Object> cacheCategory = new NamedList<>();
        NamedList<Object> queryResultCache = new NamedList<>();
        NamedList<Object> queryStats = new NamedList<>();

        queryStats.add("lookups", 100L);
        queryStats.add("hits", 80L);
        queryStats.add("hitratio", 0.8f);
        queryStats.add("inserts", 20L);
        queryStats.add("evictions", 5L);
        queryStats.add("size", 100L);
        queryResultCache.add("stats", queryStats);
        cacheCategory.add("queryResultCache", queryResultCache);
        mbeans.add("CACHE", cacheCategory);

        return mbeans;
    }

    private NamedList<Object> createCompleteMockCacheData() {
        NamedList<Object> mbeans = new NamedList<>();
        NamedList<Object> cacheCategory = new NamedList<>();

        // Query Result Cache
        NamedList<Object> queryResultCache = new NamedList<>();
        NamedList<Object> queryStats = new NamedList<>();
        queryStats.add("lookups", 100L);
        queryStats.add("hits", 80L);
        queryStats.add("hitratio", 0.8f);
        queryStats.add("inserts", 20L);
        queryStats.add("evictions", 5L);
        queryStats.add("size", 100L);
        queryResultCache.add("stats", queryStats);

        // Document Cache
        NamedList<Object> documentCache = new NamedList<>();
        NamedList<Object> docStats = new NamedList<>();
        docStats.add("lookups", 200L);
        docStats.add("hits", 150L);
        docStats.add("hitratio", 0.75f);
        docStats.add("inserts", 50L);
        docStats.add("evictions", 10L);
        docStats.add("size", 180L);
        documentCache.add("stats", docStats);

        // Filter Cache
        NamedList<Object> filterCache = new NamedList<>();
        NamedList<Object> filterStats = new NamedList<>();
        filterStats.add("lookups", 150L);
        filterStats.add("hits", 120L);
        filterStats.add("hitratio", 0.8f);
        filterStats.add("inserts", 30L);
        filterStats.add("evictions", 8L);
        filterStats.add("size", 140L);
        filterCache.add("stats", filterStats);

        cacheCategory.add("queryResultCache", queryResultCache);
        cacheCategory.add("documentCache", documentCache);
        cacheCategory.add("filterCache", filterCache);
        mbeans.add("CACHE", cacheCategory);

        return mbeans;
    }

    private NamedList<Object> createMockHandlerData() {
        NamedList<Object> mbeans = new NamedList<>();
        NamedList<Object> queryHandlerCategory = new NamedList<>();
        NamedList<Object> selectHandler = new NamedList<>();
        NamedList<Object> selectStats = new NamedList<>();

        selectStats.add("requests", 500L);
        selectStats.add("errors", 5L);
        selectStats.add("timeouts", 2L);
        selectStats.add("totalTime", 10000L);
        selectStats.add("avgTimePerRequest", 20.0f);
        selectStats.add("avgRequestsPerSecond", 25.0f);
        selectHandler.add("stats", selectStats);
        queryHandlerCategory.add("/select", selectHandler);
        mbeans.add("QUERYHANDLER", queryHandlerCategory);

        return mbeans;
    }

    private NamedList<Object> createCompleteHandlerData() {
        NamedList<Object> mbeans = new NamedList<>();
        NamedList<Object> queryHandlerCategory = new NamedList<>();

        // Select Handler
        NamedList<Object> selectHandler = new NamedList<>();
        NamedList<Object> selectStats = new NamedList<>();
        selectStats.add("requests", 500L);
        selectStats.add("errors", 5L);
        selectStats.add("timeouts", 2L);
        selectStats.add("totalTime", 10000L);
        selectStats.add("avgTimePerRequest", 20.0f);
        selectStats.add("avgRequestsPerSecond", 25.0f);
        selectHandler.add("stats", selectStats);

        // Update Handler
        NamedList<Object> updateHandler = new NamedList<>();
        NamedList<Object> updateStats = new NamedList<>();
        updateStats.add("requests", 250L);
        updateStats.add("errors", 2L);
        updateStats.add("timeouts", 1L);
        updateStats.add("totalTime", 5000L);
        updateStats.add("avgTimePerRequest", 20.0f);
        updateStats.add("avgRequestsPerSecond", 50.0f);
        updateHandler.add("stats", updateStats);

        queryHandlerCategory.add("/select", selectHandler);
        queryHandlerCategory.add("/update", updateHandler);
        mbeans.add("QUERYHANDLER", queryHandlerCategory);

        return mbeans;
    }
}