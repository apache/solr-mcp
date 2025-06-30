package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchServiceTest {

    @Container
    static SolrTestContainer solrContainer = new SolrTestContainer();

    @Autowired
    private SearchService searchService;

    @Autowired
    private SolrClient solrClient;

    @DynamicPropertySource
    static void registerSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url", solrContainer::getSolrUrl);
    }

    @BeforeAll
    void setUp() {
        // Wait for Solr to be ready
        solrContainer.loadSampleData();
    }

    @Test
    void testBasicSearch() throws SolrServerException, IOException {
        // Test basic search with no parameters
        SearchResponse result = searchService.search("books", null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        assertEquals(10, documents.size());
    }

    @Test
    void testSearchWithQuery() throws SolrServerException, IOException {
        // Test search with query
        SearchResponse result = searchService.search("books", "name:\"Game of Thrones\"", null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> book = documents.get(0);
        assertEquals("A Game of Thrones", ((List<?>)book.get("name")).get(0));
    }

    @Test
    void testSearchReturnsAuthor() throws Exception {
        // Test search with filter query
        SearchResponse result = searchService.search(
                "books", "author:\"George R.R. Martin\"", null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(3, documents.size());

        Map<String, Object> book = documents.get(0);
        assertEquals("George R.R. Martin", ((List<?>)book.get("author")).get(0));
    }

    @Test
    void testSearchWithFacets() throws Exception {
        // Test search with facets
        SearchResponse result = searchService.search(
                "books", null, null, List.of("genre_s"), null);

        assertNotNull(result);
        Map<String, Map<String, Long>> facets = result.facets();
        assertNotNull(facets);
        assertTrue(facets.containsKey("genre_s"));
    }

    @Test
    void testSearchWithPrice() throws Exception {
        // Test search with sorting
        SearchResponse result = searchService.search(
                "books", null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        Map<String, Object> book = documents.get(0);
        double currentPrice = ((List<?>)book.get("price")).isEmpty() ? 0.0 : ((Number)((List<?>)book.get("price")).get(0)).doubleValue();
        assertTrue(currentPrice > 0);
    }
}
