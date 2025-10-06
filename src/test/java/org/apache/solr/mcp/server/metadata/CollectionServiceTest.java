package org.apache.solr.mcp.server.metadata;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
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

    // Cache stats tests
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
    void testExtractHandlerStats() throws Exception {
        NamedList<Object> mbeans = createMockHandlerData();
        Method method = CollectionService.class.getDeclaredMethod("extractHandlerStats", NamedList.class);
        method.setAccessible(true);

        HandlerStats result = (HandlerStats) method.invoke(collectionService, mbeans);

        assertNotNull(result.selectHandler());
        assertEquals(500L, result.selectHandler().requests());
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
        queryResultCache.add("stats", queryStats);
        cacheCategory.add("queryResultCache", queryResultCache);
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
        selectHandler.add("stats", selectStats);
        queryHandlerCategory.add("/select", selectHandler);
        mbeans.add("QUERYHANDLER", queryHandlerCategory);

        return mbeans;
    }
}
