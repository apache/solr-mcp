package org.apache.solr.mcp.server;

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

    @DynamicPropertySource
    static void registerSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url", () -> "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/");
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
}
