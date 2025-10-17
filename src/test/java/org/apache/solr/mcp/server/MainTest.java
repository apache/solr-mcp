package org.apache.solr.mcp.server;

import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.apache.solr.mcp.server.metadata.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Application context loading test with mocked services.
 * This test verifies that the Spring application context can be loaded successfully
 * without requiring actual Solr connections, using mocked beans to prevent external dependencies.
 */
@SpringBootTest
@ActiveProfiles("test")
class MainTest {

    @MockitoBean
    private SearchService searchService;

    @MockitoBean
    private IndexingService indexingService;

    @MockitoBean
    private CollectionService collectionService;

    @MockitoBean
    private SchemaService schemaService;

    @Test
    void contextLoads() {
        // Context loading test - all services are mocked to prevent Solr API calls
    }

}
