package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

        // Verify that the SolrClient is using the correct URL
        var httpSolrClient = assertInstanceOf(Http2SolrClient.class, solrClient);
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

    @ParameterizedTest
    @CsvSource({
        "http://localhost:8983, http://localhost:8983/solr",
        "http://localhost:8983/, http://localhost:8983/solr",
        "http://localhost:8983/solr, http://localhost:8983/solr",
        "http://localhost:8983/solr/, http://localhost:8983/solr",
        "http://localhost:8983/custom/solr/, http://localhost:8983/custom/solr"
    })
    void testUrlNormalization(String inputUrl, String expectedUrl) {
        // Create a test properties object
        SolrConfigurationProperties testProperties = new SolrConfigurationProperties(inputUrl);
        
        // Create SolrConfig instance
        SolrConfig solrConfig = new SolrConfig();
        
        // Test URL normalization
        SolrClient client = solrConfig.solrClient(testProperties);
        assertNotNull(client);
        
        var httpClient = assertInstanceOf(Http2SolrClient.class, client);
        assertEquals(expectedUrl, httpClient.getBaseURL());
        
        // Clean up
        try {
            client.close();
        } catch (Exception e) {
            // Ignore close errors in test
        }
    }

    @Test
    void testUrlWithoutTrailingSlash() {
        // Test URL without trailing slash branch
        SolrConfigurationProperties testProperties = new SolrConfigurationProperties("http://localhost:8983");
        SolrConfig solrConfig = new SolrConfig();
        
        SolrClient client = solrConfig.solrClient(testProperties);
        Http2SolrClient httpClient = (Http2SolrClient) client;
        
        // Should add trailing slash and solr path
        assertEquals("http://localhost:8983/solr", httpClient.getBaseURL());
        
        try {
            client.close();
        } catch (Exception e) {
            // Ignore close errors in test
        }
    }

    @Test
    void testUrlWithTrailingSlashButNoSolrPath() {
        // Test URL with trailing slash but no solr path branch
        SolrConfigurationProperties testProperties = new SolrConfigurationProperties("http://localhost:8983/");
        SolrConfig solrConfig = new SolrConfig();
        
        SolrClient client = solrConfig.solrClient(testProperties);
        Http2SolrClient httpClient = (Http2SolrClient) client;
        
        // Should add solr path to existing trailing slash
        assertEquals("http://localhost:8983/solr", httpClient.getBaseURL());
        
        try {
            client.close();
        } catch (Exception e) {
            // Ignore close errors in test
        }
    }

    @Test
    void testUrlWithSolrPathButNoTrailingSlash() {
        // Test URL with solr path but no trailing slash
        SolrConfigurationProperties testProperties = new SolrConfigurationProperties("http://localhost:8983/solr");
        SolrConfig solrConfig = new SolrConfig();
        
        SolrClient client = solrConfig.solrClient(testProperties);
        Http2SolrClient httpClient = (Http2SolrClient) client;
        
        // Should add trailing slash
        assertEquals("http://localhost:8983/solr", httpClient.getBaseURL());
        
        try {
            client.close();
        } catch (Exception e) {
            // Ignore close errors in test
        }
    }

    @Test
    void testUrlAlreadyProperlyFormatted() {
        // Test URL that's already properly formatted
        SolrConfigurationProperties testProperties = new SolrConfigurationProperties("http://localhost:8983/solr/");
        SolrConfig solrConfig = new SolrConfig();
        
        SolrClient client = solrConfig.solrClient(testProperties);
        Http2SolrClient httpClient = (Http2SolrClient) client;
        
        // Should remain unchanged
        assertEquals("http://localhost:8983/solr", httpClient.getBaseURL());
        
        try {
            client.close();
        } catch (Exception e) {
            // Ignore close errors in test
        }
    }
}