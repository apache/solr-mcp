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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNotNull(metrics.timestamp(), "Timestamp should not be null");

        // Verify index stats
        assertNotNull(metrics.indexStats(), "Index stats should not be null");
        IndexStats indexStats = metrics.indexStats();
        assertNotNull(indexStats.numDocs(), "Number of documents should not be null");
        assertTrue(indexStats.numDocs() >= 0, "Number of documents should be non-negative");

        // Verify query stats
        assertNotNull(metrics.queryStats(), "Query stats should not be null");
        QueryStats queryStats = metrics.queryStats();
        assertNotNull(queryStats.queryTime(), "Query time should not be null");
        assertTrue(queryStats.queryTime() >= 0, "Query time should be non-negative");
        assertNotNull(queryStats.totalResults(), "Total results should not be null");
        assertTrue(queryStats.totalResults() >= 0, "Total results should be non-negative");
        assertNotNull(queryStats.start(), "Start should not be null");
        assertTrue(queryStats.start() >= 0, "Start should be non-negative");

        // Verify timestamp is recent (within last 10 seconds)
        long currentTime = System.currentTimeMillis();
        long timestampTime = metrics.timestamp().getTime();
        assertTrue(currentTime - timestampTime < 10000,
                "Timestamp should be recent (within 10 seconds)");

        // Verify optional stats (cache and handler stats may be null, which is acceptable)
        if (metrics.cacheStats() != null) {
            CacheStats cacheStats = metrics.cacheStats();
            // Verify at least one cache type exists if cache stats are present
            assertTrue(cacheStats.queryResultCache() != null ||
                            cacheStats.documentCache() != null ||
                            cacheStats.filterCache() != null,
                    "At least one cache type should be present if cache stats exist");
        }

        if (metrics.handlerStats() != null) {
            HandlerStats handlerStats = metrics.handlerStats();
            // Verify at least one handler type exists if handler stats are present
            assertTrue(handlerStats.selectHandler() != null ||
                            handlerStats.updateHandler() != null,
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
        assertNotNull(status.responseTime(), "Response time should not be null");
        assertTrue(status.responseTime() >= 0, "Response time should be non-negative");
        assertTrue(status.responseTime() < 30000, "Response time should be reasonable (< 30 seconds)");

        // Verify document count
        assertNotNull(status.totalDocuments(), "Total documents should not be null");
        assertTrue(status.totalDocuments() >= 0, "Total documents should be non-negative");

        // Verify timestamp
        assertNotNull(status.lastChecked(), "Last checked timestamp should not be null");
        long currentTime = System.currentTimeMillis();
        long lastCheckedTime = status.lastChecked().getTime();
        assertTrue(currentTime - lastCheckedTime < 5000,
                "Last checked timestamp should be very recent (within 5 seconds)");

        // Verify no error message for healthy collection
        assertNull(status.errorMessage(), "Error message should be null for healthy collection");

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
        assertNotNull(status.lastChecked(), "Last checked timestamp should not be null");
        long currentTime = System.currentTimeMillis();
        long lastCheckedTime = status.lastChecked().getTime();
        assertTrue(currentTime - lastCheckedTime < 5000,
                "Last checked timestamp should be very recent (within 5 seconds)");

        // Verify error message
        assertNotNull(status.errorMessage(), "Error message should not be null for unhealthy collection");
        assertFalse(status.errorMessage().trim().isEmpty(),
                "Error message should not be empty for unhealthy collection");

        // Verify that performance metrics are null for unhealthy collection
        assertNull(status.responseTime(), "Response time should be null for unhealthy collection");
        assertNull(status.totalDocuments(), "Total documents should be null for unhealthy collection");

        // Verify error message contains meaningful information
        String errorMessage = status.errorMessage().toLowerCase();
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
