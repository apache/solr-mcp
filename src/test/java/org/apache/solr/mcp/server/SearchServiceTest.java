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
    void setUp() throws Exception {
        // Wait for Solr to be ready
        solrContainer.loadSampleData();
    }

    @Test
    void testBasicSearch() throws SolrServerException, IOException {
        // Test basic search with no parameters
        Map<String, Object> result = searchService.search("books", null, null, null, null);

        assertNotNull(result);
        assertTrue(result.containsKey(SearchService.DOCUMENTS));
        assertTrue(result.containsKey(SearchService.NUM_FOUND));

        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get(SearchService.DOCUMENTS);
        assertFalse(documents.isEmpty());
        assertEquals(10, documents.size());
    }

    @Test
    void testSearchWithQuery() throws SolrServerException, IOException {
        // Test search with query
        Map<String, Object> result = searchService.search("books", "name:\"Game of Thrones\"", null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get(SearchService.DOCUMENTS);
        assertEquals(1, documents.size());

        Map<String, Object> book = documents.get(0);
        assertEquals("A Game of Thrones", ((List<String>)book.get("name")).get(0));
    }

    @Test
    void testSearchWithFilterQuery() throws SolrServerException, IOException {
        // Test search with filter query
        Map<String, Object> result = searchService.search(
            "books", 
            "*:*", 
            List.of("author:\"George R.R. Martin\""), 
            null, 
            null
        );

        assertNotNull(result);
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get(SearchService.DOCUMENTS);
        assertEquals(3, documents.size());

        for (Map<String, Object> book : documents) {
            assertEquals("George R.R. Martin", ((List<String>)book.get("author")).get(0));
        }
    }

    @Test
    void testSearchWithFacets() throws SolrServerException, IOException {
        // Test search with facets
        Map<String, Object> result = searchService.search(
            "books", 
            "*:*", 
            null, 
            List.of("genre_s"), 
            null
        );

        assertNotNull(result);
        assertTrue(result.containsKey(SearchService.FACETS));

        Map<String, Map<String, Long>> facets = (Map<String, Map<String, Long>>) result.get(SearchService.FACETS);
        assertTrue(facets.containsKey("genre_s"));

        Map<String, Long> genreFacets = facets.get("genre_s");
        assertTrue(genreFacets.containsKey("fantasy"));
        assertTrue(genreFacets.containsKey("scifi"));
    }

    @Test
    void testSearchWithSorting() throws SolrServerException, IOException {
        // Test search with sorting
        Map<String, Object> result = searchService.search(
            "books", 
            "*:*", 
            null, 
            null, 
            List.of(new SolrQuery.SortClause("price", SolrQuery.ORDER.asc))
        );

        assertNotNull(result);
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get(SearchService.DOCUMENTS);

        // Verify documents are sorted by price in ascending order
        double previousPrice = 0.0;
        for (Map<String, Object> book : documents) {
            double currentPrice = ((List<Double>)book.get("price")).get(0);
            assertTrue(currentPrice >= previousPrice);
            previousPrice = currentPrice;
        }
    }
}
