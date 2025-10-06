package org.apache.solr.mcp.server.metadata;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CollectionServiceIntegrationTest {

    private static final String TEST_COLLECTION = "test_collection";
    @Autowired
    private CollectionService collectionService;
    @Autowired
    private SolrClient solrClient;
    private static boolean initialized = false;

    @BeforeEach
    void setupCollection() throws Exception {

        if (!initialized) {
            // Create a test collection using the container's connection details
            // Create a collection for testing
            CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1);
            createRequest.process(solrClient);

            // Verify collection was created successfully
            CollectionAdminRequest.List listRequest = new CollectionAdminRequest.List();
            listRequest.process(solrClient);

            System.out.println("[DEBUG_LOG] Test collection created: " + TEST_COLLECTION);
            initialized = true;
        }
    }

    @Test
    void testListCollections() {
        // Test listing collections
        List<String> collections = collectionService.listCollections();

        // Print the collections for debugging
        System.out.println("[DEBUG_LOG] Collections: " + collections);

        // Enhanced assertions for collections list
        assertNotNull(collections, "Collections list should not be null");
        assertFalse(collections.isEmpty(), "Collections list should not be empty");

        // Check if the test collection exists (either as exact name or as shard)
        boolean testCollectionExists = collections.contains(TEST_COLLECTION) ||
                collections.stream().anyMatch(col -> col.startsWith(TEST_COLLECTION + "_shard"));
        assertTrue(testCollectionExists,
                "Collections should contain the test collection: " + TEST_COLLECTION +
                        " (found: " + collections + ")");

        // Verify collection names are not null or empty
        for (String collection : collections) {
            assertNotNull(collection, "Collection name should not be null");
            assertFalse(collection.trim().isEmpty(), "Collection name should not be empty");
        }

        // Verify expected collection characteristics
        assertEquals(collections.size(), collections.stream().distinct().count(),
                "Collection names should be unique");

        // Verify that collections follow expected naming patterns
        for (String collection : collections) {
            // Collection names should either be simple names or shard names
            assertTrue(collection.matches("^[a-zA-Z0-9_]+(_shard\\d+_replica_n\\d+)?$"),
                    "Collection name should follow expected pattern: " + collection);
        }
    }


    @Test
    void testGetCollectionStats() throws Exception {
        // Test getting collection stats
        SolrMetrics metrics = collectionService.getCollectionStats(TEST_COLLECTION);

        // Enhanced assertions for metrics
        assertNotNull(metrics, "Collection stats should not be null");
        assertNotNull(metrics.getTimestamp(), "Timestamp should not be null");

        // Verify index stats
        assertNotNull(metrics.getIndexStats(), "Index stats should not be null");
        IndexStats indexStats = metrics.getIndexStats();
        assertNotNull(indexStats.getNumDocs(), "Number of documents should not be null");
        assertTrue(indexStats.getNumDocs() >= 0, "Number of documents should be non-negative");

        // Verify query stats
        assertNotNull(metrics.getQueryStats(), "Query stats should not be null");
        QueryStats queryStats = metrics.getQueryStats();
        assertNotNull(queryStats.getQueryTime(), "Query time should not be null");
        assertTrue(queryStats.getQueryTime() >= 0, "Query time should be non-negative");
        assertNotNull(queryStats.getTotalResults(), "Total results should not be null");
        assertTrue(queryStats.getTotalResults() >= 0, "Total results should be non-negative");
        assertNotNull(queryStats.getStart(), "Start should not be null");
        assertTrue(queryStats.getStart() >= 0, "Start should be non-negative");

        // Verify timestamp is recent (within last 10 seconds)
        long currentTime = System.currentTimeMillis();
        long timestampTime = metrics.getTimestamp().getTime();
        assertTrue(currentTime - timestampTime < 10000,
                "Timestamp should be recent (within 10 seconds)");

        // Verify optional stats (cache and handler stats may be null, which is acceptable)
        if (metrics.getCacheStats() != null) {
            CacheStats cacheStats = metrics.getCacheStats();
            // Verify at least one cache type exists if cache stats are present
            assertTrue(cacheStats.getQueryResultCache() != null ||
                            cacheStats.getDocumentCache() != null ||
                            cacheStats.getFilterCache() != null,
                    "At least one cache type should be present if cache stats exist");
        }

        if (metrics.getHandlerStats() != null) {
            HandlerStats handlerStats = metrics.getHandlerStats();
            // Verify at least one handler type exists if handler stats are present
            assertTrue(handlerStats.getSelectHandler() != null ||
                            handlerStats.getUpdateHandler() != null,
                    "At least one handler type should be present if handler stats exist");
        }
    }

    @Test
    void testCheckHealthHealthy() {
        // Test checking health of a valid collection
        SolrHealthStatus status = collectionService.checkHealth(TEST_COLLECTION);

        // Print the status for debugging
        System.out.println("[DEBUG_LOG] Health status for valid collection: " + status);

        // Enhanced assertions for healthy collection
        assertNotNull(status, "Health status should not be null");
        assertTrue(status.isHealthy(), "Collection should be healthy");

        // Verify response time
        assertNotNull(status.getResponseTime(), "Response time should not be null");
        assertTrue(status.getResponseTime() >= 0, "Response time should be non-negative");
        assertTrue(status.getResponseTime() < 30000, "Response time should be reasonable (< 30 seconds)");

        // Verify document count
        assertNotNull(status.getTotalDocuments(), "Total documents should not be null");
        assertTrue(status.getTotalDocuments() >= 0, "Total documents should be non-negative");

        // Verify timestamp
        assertNotNull(status.getLastChecked(), "Last checked timestamp should not be null");
        long currentTime = System.currentTimeMillis();
        long lastCheckedTime = status.getLastChecked().getTime();
        assertTrue(currentTime - lastCheckedTime < 5000,
                "Last checked timestamp should be very recent (within 5 seconds)");

        // Verify no error message for healthy collection
        assertNull(status.getErrorMessage(), "Error message should be null for healthy collection");

        // Verify string representation contains meaningful information
        String statusString = status.toString();
        if (statusString != null) {
            assertTrue(statusString.contains("healthy") || statusString.contains("true"),
                    "Status string should indicate healthy state");
        }
    }

    @Test
    void testCheckHealthUnhealthy() {
        // Test checking health of an invalid collection
        String nonExistentCollection = "non_existent_collection";
        SolrHealthStatus status = collectionService.checkHealth(nonExistentCollection);

        // Print the status for debugging
        System.out.println("[DEBUG_LOG] Health status for invalid collection: " + status);

        // Enhanced assertions for unhealthy collection
        assertNotNull(status, "Health status should not be null");
        assertFalse(status.isHealthy(), "Collection should not be healthy");

        // Verify timestamp
        assertNotNull(status.getLastChecked(), "Last checked timestamp should not be null");
        long currentTime = System.currentTimeMillis();
        long lastCheckedTime = status.getLastChecked().getTime();
        assertTrue(currentTime - lastCheckedTime < 5000,
                "Last checked timestamp should be very recent (within 5 seconds)");

        // Verify error message
        assertNotNull(status.getErrorMessage(), "Error message should not be null for unhealthy collection");
        assertFalse(status.getErrorMessage().trim().isEmpty(),
                "Error message should not be empty for unhealthy collection");

        // Verify that performance metrics are null for unhealthy collection
        assertNull(status.getResponseTime(), "Response time should be null for unhealthy collection");
        assertNull(status.getTotalDocuments(), "Total documents should be null for unhealthy collection");

        // Verify error message contains meaningful information
        String errorMessage = status.getErrorMessage().toLowerCase();
        assertTrue(errorMessage.contains("collection") || errorMessage.contains("not found") ||
                        errorMessage.contains("error") || errorMessage.contains("fail"),
                "Error message should contain meaningful error information");

        // Verify string representation indicates unhealthy state
        String statusString = status.toString();
        if (statusString != null) {
            assertTrue(statusString.contains("false") || statusString.contains("unhealthy") ||
                            statusString.contains("error"),
                    "Status string should indicate unhealthy state");
        }
    }

    @Test
    void testCollectionNameExtraction() {
        // Test collection name extraction functionality
        assertEquals(TEST_COLLECTION,
                collectionService.extractCollectionName(TEST_COLLECTION),
                "Regular collection name should be returned as-is");

        assertEquals("films",
                collectionService.extractCollectionName("films_shard1_replica_n1"),
                "Shard name should be extracted to base collection name");

        assertEquals("products",
                collectionService.extractCollectionName("products_shard2_replica_n3"),
                "Complex shard name should be extracted correctly");

        assertNull(collectionService.extractCollectionName(null),
                "Null input should return null");

        assertEquals("",
                collectionService.extractCollectionName(""),
                "Empty string should return empty string");
    }
}
