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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

    @BeforeEach
    void setUp() {
        collectionService = new CollectionService(solrClient);
    }

    // Collection name extraction tests
    @Test
    void testExtractCollectionName() {
        assertEquals("films", collectionService.extractCollectionName("films_shard1_replica_n1"));
        assertEquals("simple_collection", collectionService.extractCollectionName("simple_collection"));
        assertNull(collectionService.extractCollectionName(null));
        assertEquals("", collectionService.extractCollectionName(""));
        assertEquals("", collectionService.extractCollectionName("_shard1_replica_n1"));
    }

    // Query stats tests
    @Test
    void testBuildQueryStats() {
        SolrDocumentList results = new SolrDocumentList();
        results.setNumFound(1500L);
        results.setStart(0L);
        results.setMaxScore(0.95f);

        when(queryResponse.getQTime()).thenReturn(25);
        when(queryResponse.getResults()).thenReturn(results);

        QueryStats result = collectionService.buildQueryStats(queryResponse);

        assertEquals(25, result.queryTime());
        assertEquals(1500L, result.totalResults());
        assertEquals(0L, result.start());
        assertEquals(0.95f, result.maxScore());
    }

    // Index stats tests
    @Test
    void testBuildIndexStats() {
        NamedList<Object> indexInfo = new NamedList<>();
        indexInfo.add("segmentCount", 5);
        when(lukeResponse.getIndexInfo()).thenReturn(indexInfo);
        when(lukeResponse.getNumDocs()).thenReturn(1000);

        IndexStats result = collectionService.buildIndexStats(lukeResponse);

        assertEquals(1000, result.numDocs());
        assertEquals(5, result.segmentCount());
    }

    @Test
    void testBuildIndexStats_WithNullSegmentCount() {
        NamedList<Object> indexInfo = new NamedList<>();
        when(lukeResponse.getIndexInfo()).thenReturn(indexInfo);
        when(lukeResponse.getNumDocs()).thenReturn(1000);

        IndexStats result = collectionService.buildIndexStats(lukeResponse);

        assertEquals(1000, result.numDocs());
        assertNull(result.segmentCount());
    }

    // Health check tests
    @Test
    void testCheckHealth_Success() throws Exception {
        when(pingResponse.getElapsedTime()).thenReturn(50L);
        when(solrClient.ping("test_collection")).thenReturn(pingResponse);

        SolrDocumentList results = new SolrDocumentList();
        results.setNumFound(1000L);
        when(queryResponse.getResults()).thenReturn(results);
        when(solrClient.query(eq("test_collection"), any())).thenReturn(queryResponse);

        SolrHealthStatus result = collectionService.checkHealth("test_collection");

        assertTrue(result.isHealthy());
        assertEquals(50L, result.responseTime());
        assertEquals(1000L, result.totalDocuments());
        assertNull(result.errorMessage());
    }

    @Test
    void testCheckHealth_Failure() throws Exception {
        when(solrClient.ping("bad_collection"))
                .thenThrow(new SolrServerException("Collection not found"));

        SolrHealthStatus result = collectionService.checkHealth("bad_collection");

        assertFalse(result.isHealthy());
        assertTrue(result.errorMessage().contains("Collection not found"));
        assertNull(result.responseTime());
    }

    @Test
    void testCheckHealth_IOException() throws Exception {
        when(solrClient.ping("error_collection"))
                .thenThrow(new IOException("Network error"));

        SolrHealthStatus result = collectionService.checkHealth("error_collection");

        assertFalse(result.isHealthy());
        assertTrue(result.errorMessage().contains("Network error"));
        assertNull(result.responseTime());
    }

    // Collection validation tests
    @Test
    void testGetCollectionStats_NotFound() {
        CollectionService spyService = spy(collectionService);
        doReturn(Collections.emptyList()).when(spyService).listCollections();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> spyService.getCollectionStats("non_existent"));

        assertTrue(exception.getMessage().contains("Collection not found: non_existent"));
    }

    @Test
    void testValidateCollectionExists() throws Exception {
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
    void testValidateCollectionExists_WithException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Collections.emptyList()).when(spyService).listCollections();

        Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(spyService, "any_collection"));
    }

    // Cache stats tests
    @Test
    void testGetCacheMetrics_Success() throws Exception {
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
    void testGetCacheMetrics_CollectionNotFound() {
        CollectionService spyService = spy(collectionService);
        doReturn(Collections.emptyList()).when(spyService).listCollections();

        CacheStats result = spyService.getCacheMetrics("non_existent");

        assertNull(result);
    }

    @Test
    void testGetCacheMetrics_SolrServerException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new SolrServerException("Error"));

        CacheStats result = spyService.getCacheMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void testGetCacheMetrics_IOException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new IOException("IO Error"));

        CacheStats result = spyService.getCacheMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void testGetCacheMetrics_EmptyStats() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        NamedList<Object> mbeans = new NamedList<>();
        mbeans.add("CACHE", new NamedList<>());
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        CacheStats result = spyService.getCacheMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void testGetCacheMetrics_WithShardName() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

        NamedList<Object> mbeans = createMockCacheData();
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        CacheStats result = spyService.getCacheMetrics("films_shard1_replica_n1");

        assertNotNull(result);
    }

    @Test
    void testExtractCacheStats() throws Exception {
        NamedList<Object> mbeans = createMockCacheData();
        Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
        method.setAccessible(true);

        CacheStats result = (CacheStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.queryResultCache());
        assertEquals(100L, result.queryResultCache().lookups());
        assertEquals(80L, result.queryResultCache().hits());
    }

    @Test
    void testExtractCacheStats_AllCacheTypes() throws Exception {
        NamedList<Object> mbeans = createCompleteMockCacheData();
        Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
        method.setAccessible(true);

        CacheStats result = (CacheStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.queryResultCache());
        assertNotNull(result.documentCache());
        assertNotNull(result.filterCache());
    }

    @Test
    void testExtractCacheStats_NullCacheCategory() throws Exception {
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
    void testIsCacheStatsEmpty() throws Exception {
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

    // Handler stats tests
    @Test
    void testGetHandlerMetrics_Success() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        NamedList<Object> mbeans = createMockHandlerData();
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNotNull(result);
        assertNotNull(result.selectHandler());
    }

    @Test
    void testGetHandlerMetrics_CollectionNotFound() {
        CollectionService spyService = spy(collectionService);
        doReturn(Collections.emptyList()).when(spyService).listCollections();

        HandlerStats result = spyService.getHandlerMetrics("non_existent");

        assertNull(result);
    }

    @Test
    void testGetHandlerMetrics_SolrServerException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new SolrServerException("Error"));

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void testGetHandlerMetrics_IOException() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        when(solrClient.request(any(SolrRequest.class)))
                .thenThrow(new IOException("IO Error"));

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void testGetHandlerMetrics_EmptyStats() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("test_collection")).when(spyService).listCollections();

        NamedList<Object> mbeans = new NamedList<>();
        mbeans.add("QUERYHANDLER", new NamedList<>());
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        HandlerStats result = spyService.getHandlerMetrics("test_collection");

        assertNull(result);
    }

    @Test
    void testGetHandlerMetrics_WithShardName() throws Exception {
        CollectionService spyService = spy(collectionService);
        doReturn(Arrays.asList("films_shard1_replica_n1")).when(spyService).listCollections();

        NamedList<Object> mbeans = createMockHandlerData();
        when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

        HandlerStats result = spyService.getHandlerMetrics("films_shard1_replica_n1");

        assertNotNull(result);
    }

    @Test
    void testExtractHandlerStats() throws Exception {
        NamedList<Object> mbeans = createMockHandlerData();
        Method method = CollectionService.class.getDeclaredMethod("extractHandlerStats", NamedList.class);
        method.setAccessible(true);

        HandlerStats result = (HandlerStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.selectHandler());
        assertEquals(500L, result.selectHandler().requests());
    }

    @Test
    void testExtractHandlerStats_BothHandlers() throws Exception {
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
    void testExtractHandlerStats_NullHandlerCategory() throws Exception {
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
    void testIsHandlerStatsEmpty() throws Exception {
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
    void testListCollections_CloudClient_Success() throws Exception {
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
    void testListCollections_CloudClient_NullCollections() throws Exception {
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
    void testListCollections_CloudClient_Error() throws Exception {
        CloudSolrClient cloudClient = mock(CloudSolrClient.class);
        when(cloudClient.request(any(), any())).thenThrow(new SolrServerException("Connection error"));

        CollectionService service = new CollectionService(cloudClient);
        List<String> result = service.listCollections();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testListCollections_NonCloudClient_Success() throws Exception {
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
    void testListCollections_NonCloudClient_Error() throws Exception {
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
