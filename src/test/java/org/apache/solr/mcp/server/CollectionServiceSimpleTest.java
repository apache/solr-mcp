package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollectionServiceSimpleTest {

    @Mock
    private SolrClient solrClient;

    @Mock
    private SolrConfigurationProperties solrConfigurationProperties;

    @Mock
    private QueryResponse queryResponse;

    private CollectionService collectionService;

    @BeforeEach
    void setUp() {
        collectionService = new CollectionService(solrClient, solrConfigurationProperties);
    }

    @Test
    void testExtractCollectionName_WithShardPattern() {
        // Test extracting collection name from shard name
        String result = collectionService.extractCollectionName("films_shard1_replica_n1");
        assertEquals("films", result);
        
        result = collectionService.extractCollectionName("products_shard2_replica_n3");
        assertEquals("products", result);
        
        result = collectionService.extractCollectionName("my_collection_shard1_replica_n2");
        assertEquals("my_collection", result);
    }

    @Test
    void testExtractCollectionName_WithoutShardPattern() {
        // Test with regular collection names
        String result = collectionService.extractCollectionName("simple_collection");
        assertEquals("simple_collection", result);
        
        result = collectionService.extractCollectionName("test");
        assertEquals("test", result);
    }

    @Test
    void testExtractCollectionName_NullAndEmpty() {
        // Test with null and empty strings
        String result = collectionService.extractCollectionName(null);
        assertNull(result);
        
        result = collectionService.extractCollectionName("");
        assertEquals("", result);
    }

    @Test
    void testExtractCollectionName_EdgeCases() {
        // Test edge cases
        String result = collectionService.extractCollectionName("_shard1_replica_n1");
        assertEquals("", result);
        
        result = collectionService.extractCollectionName("collection_shard");
        assertEquals("collection", result);
        
        result = collectionService.extractCollectionName("collection_shard_something");
        assertEquals("collection", result);
    }

    // Note: Exception handling tests are covered by integration tests

    @Test
    void testBuildQueryStats() {
        // Test building query stats from query response
        SolrDocumentList results = new SolrDocumentList();
        results.setNumFound(1500L);
        results.setStart(0L);
        results.setMaxScore(0.95f);
        
        when(queryResponse.getQTime()).thenReturn(25);
        when(queryResponse.getResults()).thenReturn(results);
        
        QueryStats result = collectionService.buildQueryStats(queryResponse);
        
        assertNotNull(result);
        assertEquals(25, result.getQueryTime());
        assertEquals(1500L, result.getTotalResults());
        assertEquals(0L, result.getStart());
        assertEquals(0.95f, result.getMaxScore());
    }

    @Test
    void testBuildQueryStats_NullMaxScore() {
        // Test with null max score
        SolrDocumentList results = new SolrDocumentList();
        results.setNumFound(100L);
        results.setStart(10L);
        results.setMaxScore(null);
        
        when(queryResponse.getQTime()).thenReturn(15);
        when(queryResponse.getResults()).thenReturn(results);
        
        QueryStats result = collectionService.buildQueryStats(queryResponse);
        
        assertNotNull(result);
        assertEquals(15, result.getQueryTime());
        assertEquals(100L, result.getTotalResults());
        assertEquals(10L, result.getStart());
        assertNull(result.getMaxScore());
    }

    @Test
    void testValidateCollectionExists_ExactMatch() throws Exception {
        // Test exact collection match
        CollectionService spyService = spy(collectionService);
        List<String> collections = Arrays.asList("collection1", "collection2", "test_collection");
        doReturn(collections).when(spyService).listCollections();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(spyService, "test_collection");
        assertTrue(result);
    }

    @Test
    void testValidateCollectionExists_ShardMatch() throws Exception {
        // Test shard-based collection match
        CollectionService spyService = spy(collectionService);
        List<String> collections = Arrays.asList("films_shard1_replica_n1", "films_shard2_replica_n1", "other_collection");
        doReturn(collections).when(spyService).listCollections();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(spyService, "films");
        assertTrue(result);
    }

    @Test
    void testValidateCollectionExists_NotFound() throws Exception {
        // Test collection not found
        CollectionService spyService = spy(collectionService);
        List<String> collections = Arrays.asList("collection1", "collection2", "other_collection");
        doReturn(collections).when(spyService).listCollections();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(spyService, "non_existent_collection");
        assertFalse(result);
    }

    @Test
    void testValidateCollectionExists_Exception() throws Exception {
        // Test exception handling in validation
        CollectionService spyService = spy(collectionService);
        doThrow(new RuntimeException("List collections failed")).when(spyService).listCollections();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("validateCollectionExists", String.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(spyService, "test_collection");
        assertFalse(result);
    }

    @Test
    void testIsCacheStatsEmpty_AllNull() throws Exception {
        // Test with all null cache stats
        CacheStats nullStats = CacheStats.builder()
                .queryResultCache(null)
                .documentCache(null)
                .filterCache(null)
                .build();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("isCacheStatsEmpty", CacheStats.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(collectionService, nullStats);
        assertTrue(result);
    }

    @Test
    void testIsCacheStatsEmpty_WithData() throws Exception {
        // Test with some cache data
        CacheStats stats = CacheStats.builder()
                .queryResultCache(CacheInfo.builder()
                        .lookups(100L)
                        .hits(80L)
                        .build())
                .documentCache(null)
                .filterCache(null)
                .build();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("isCacheStatsEmpty", CacheStats.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(collectionService, stats);
        assertFalse(result);
    }

    @Test
    void testIsCacheStatsEmpty_NullStats() throws Exception {
        // Test with null stats
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("isCacheStatsEmpty", CacheStats.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(collectionService, (CacheStats) null);
        assertTrue(result);
    }

    @Test
    void testIsHandlerStatsEmpty_AllNull() throws Exception {
        // Test with all null handler stats
        HandlerStats nullStats = HandlerStats.builder()
                .selectHandler(null)
                .updateHandler(null)
                .build();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("isHandlerStatsEmpty", HandlerStats.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(collectionService, nullStats);
        assertTrue(result);
    }

    @Test
    void testIsHandlerStatsEmpty_WithData() throws Exception {
        // Test with some handler data
        HandlerStats stats = HandlerStats.builder()
                .selectHandler(HandlerInfo.builder()
                        .requests(100L)
                        .errors(5L)
                        .build())
                .updateHandler(null)
                .build();
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("isHandlerStatsEmpty", HandlerStats.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(collectionService, stats);
        assertFalse(result);
    }

    @Test
    void testIsHandlerStatsEmpty_NullStats() throws Exception {
        // Test with null stats
        
        // Use reflection to call private method
        java.lang.reflect.Method method = CollectionService.class.getDeclaredMethod("isHandlerStatsEmpty", HandlerStats.class);
        method.setAccessible(true);
        
        boolean result = (boolean) method.invoke(collectionService, (HandlerStats) null);
        assertTrue(result);
    }
}