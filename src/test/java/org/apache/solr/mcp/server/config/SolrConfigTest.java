package org.apache.solr.mcp.server.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.SolrContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SolrConfigTest {

    @Autowired
    private SolrClient solrClient;

    @Autowired
    SolrContainer solrContainer;

    @Autowired
    private SolrConfigurationProperties properties;

    @Test
    void testSolrClientConfiguration() {
        // Verify that the SolrClient is properly configured
        assertNotNull(solrClient);

        // Verify that the SolrClient is using the correct URL
        // Note: SolrConfig normalizes the URL to have trailing slash, but Http2SolrClient removes it
        var httpSolrClient = assertInstanceOf(Http2SolrClient.class, solrClient);
        String expectedUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
        assertEquals(expectedUrl, httpSolrClient.getBaseURL());
    }

    @Test
    void testSolrConfigurationProperties() {
        // Verify that the properties are correctly loaded
        assertNotNull(properties);
        assertNotNull(properties.url());
        assertEquals("http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/",
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