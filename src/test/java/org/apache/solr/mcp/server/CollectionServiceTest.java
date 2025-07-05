package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        // Wait for Solr to start
        Thread.sleep(2000);

        // Create a test collection
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/";
        SolrClient client = new org.apache.solr.client.solrj.impl.HttpSolrClient.Builder(solrUrl).build();

        try {
            // Create a collection for testing
            CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1);
            client.request(createRequest);

            // Wait for collection to be available
            Thread.sleep(1000);

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
}
