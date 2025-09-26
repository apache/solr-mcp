package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class CollectionServiceTest {

    @Container
    static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.4.1"));

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private SolrClient solrClient;

    private static final String TEST_COLLECTION = "test_collection";

    @DynamicPropertySource
    static void registerSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url", () -> "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/");
    }

    @BeforeAll
    static void setup() throws Exception {
        // Wait for Solr container to be ready
        assertTrue(solrContainer.isRunning(), "Solr container should be running");

        // Create a test collection
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/";
        SolrClient client = new Http2SolrClient.Builder(solrUrl).build();

        try {
            // Create a collection for testing
            CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1);
            client.request(createRequest);

            // Verify collection was created successfully
            CollectionAdminRequest.List listRequest = new CollectionAdminRequest.List();
            CollectionAdminResponse listResponse = listRequest.process(client);

            System.out.println("[DEBUG_LOG] Test collection created: " + TEST_COLLECTION);
        } catch (Exception e) {
            System.err.println("[DEBUG_LOG] Error creating test collection: " + e.getMessage());
            throw e;
        } finally {
            client.close();
        }
    }

    @Test
    void testListCollections() {
        // Test listing collections
        List<String> collections = collectionService.listCollections();

        // Print the collections for debugging
        System.out.println("[DEBUG_LOG] Collections: " + collections);

        // The test should not throw any exceptions
        assertNotNull(collections, "Collections list should not be null");
    }


    @Test
    void testGetCollectionStats() throws Exception {
        // Test getting collection stats
        SolrMetrics metrics = collectionService.getCollectionStats(TEST_COLLECTION);

        // Verify that the metrics objects are not null
        assertNotNull(metrics, "Collection stats should not be null");
        assertNotNull(metrics.getIndexStats(), "Index stats should not be null");
        assertNotNull(metrics.getTimestamp(), "Timestamp should not be null");
    }

    @Test
    void testCheckHealthHealthy() {
        // Test checking health of a valid collection
        SolrHealthStatus status = collectionService.checkHealth(TEST_COLLECTION);

        // Print the status for debugging
        System.out.println("[DEBUG_LOG] Health status for valid collection: " + status);

        // Verify the health status
        assertNotNull(status, "Health status should not be null");
        assertTrue(status.isHealthy(), "Collection should be healthy");
        assertNotNull(status.getResponseTime(), "Response time should not be null");
        assertNotNull(status.getTotalDocuments(), "Total documents should not be null");
        assertNotNull(status.getLastChecked(), "Last checked timestamp should not be null");
        assertNull(status.getErrorMessage(), "Error message should be null for healthy collection");
    }

    @Test
    void testCheckHealthUnhealthy() {
        // Test checking health of an invalid collection
        String nonExistentCollection = "non_existent_collection";
        SolrHealthStatus status = collectionService.checkHealth(nonExistentCollection);

        // Print the status for debugging
        System.out.println("[DEBUG_LOG] Health status for invalid collection: " + status);

        // Verify the health status
        assertNotNull(status, "Health status should not be null");
        assertFalse(status.isHealthy(), "Collection should not be healthy");
        assertNotNull(status.getLastChecked(), "Last checked timestamp should not be null");
        assertNotNull(status.getErrorMessage(), "Error message should not be null for unhealthy collection");
    }
}
