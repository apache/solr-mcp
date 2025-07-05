package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class SolrConfigTest {

    @Container
    static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.4.1"));

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private SolrConfigurationProperties properties;

    @DynamicPropertySource
    static void registerSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url", () -> "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr");
    }

    @Test
    void testSolrClientConfiguration() {
        // Verify that the SolrClient is properly configured
        assertNotNull(solrClient);
        assertTrue(solrClient instanceof HttpSolrClient);

        // Verify that the SolrClient is using the correct URL
        HttpSolrClient httpSolrClient = (HttpSolrClient) solrClient;
        assertEquals(properties.url(), httpSolrClient.getBaseURL());
    }

    @Test
    void testSolrConfigurationProperties() {
        // Verify that the properties are correctly loaded
        assertNotNull(properties);
        assertNotNull(properties.url());
        assertEquals("http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr",
                properties.url());
    }
}