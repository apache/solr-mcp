package org.apache.solr.mcp.server.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchServiceTest {

    private static final String COLLECTION_NAME = "search_test_" + System.currentTimeMillis();
    @Autowired
    private SearchService searchService;
    @Autowired
    private IndexingService indexingService;
    @Autowired
    private SolrClient solrClient;

    private static boolean initialized = false;

    @BeforeEach
    void setUp() throws Exception {
        if (!initialized) {
            // Create collection
            CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(
                    COLLECTION_NAME, "_default", 1, 1);
            createRequest.process(solrClient);

            // Index sample data for testing
            String sampleData = """
                    [
                      {
                        "id": "book001",
                        "name": ["A Game of Thrones"],
                        "author_ss": ["George R.R. Martin"],
                        "price": [7.99],
                        "genre_s": "fantasy",
                        "series_s": "A Song of Ice and Fire",
                        "sequence_i": 1,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book002",
                        "name": ["A Clash of Kings"],
                        "author_ss": ["George R.R. Martin"],
                        "price": [8.99],
                        "genre_s": "fantasy",
                        "series_s": "A Song of Ice and Fire",
                        "sequence_i": 2,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book003",
                        "name": ["A Storm of Swords"],
                        "author_ss": ["George R.R. Martin"],
                        "price": [9.99],
                        "genre_s": "fantasy",
                        "series_s": "A Song of Ice and Fire",
                        "sequence_i": 3,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book004",
                        "name": ["The Hobbit"],
                        "author_ss": ["J.R.R. Tolkien"],
                        "price": [6.99],
                        "genre_s": "fantasy",
                        "series_s": "Middle Earth",
                        "sequence_i": 1,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book005",
                        "name": ["Dune"],
                        "author_ss": ["Frank Herbert"],
                        "price": [10.99],
                        "genre_s": "scifi",
                        "series_s": "Dune",
                        "sequence_i": 1,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book006",
                        "name": ["Foundation"],
                        "author_ss": ["Isaac Asimov"],
                        "price": [5.99],
                        "genre_s": "scifi",
                        "series_s": "Foundation",
                        "sequence_i": 1,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book007",
                        "name": ["The Fellowship of the Ring"],
                        "author_ss": ["J.R.R. Tolkien"],
                        "price": [8.99],
                        "genre_s": "fantasy",
                        "series_s": "The Lord of the Rings",
                        "sequence_i": 1,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book008",
                        "name": ["The Two Towers"],
                        "author_ss": ["J.R.R. Tolkien"],
                        "price": [8.99],
                        "genre_s": "fantasy",
                        "series_s": "The Lord of the Rings",
                        "sequence_i": 2,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book009",
                        "name": ["The Return of the King"],
                        "author_ss": ["J.R.R. Tolkien"],
                        "price": [8.99],
                        "genre_s": "fantasy",
                        "series_s": "The Lord of the Rings",
                        "sequence_i": 3,
                        "cat_ss": ["book"]
                      },
                      {
                        "id": "book010",
                        "name": ["Neuromancer"],
                        "author_ss": ["William Gibson"],
                        "price": [7.99],
                        "genre_s": "scifi",
                        "series_s": "Sprawl",
                        "sequence_i": 1,
                        "cat_ss": ["book"]
                      }
                    ]
                    """;

            indexingService.indexJsonDocuments(COLLECTION_NAME, sampleData);
            solrClient.commit(COLLECTION_NAME);
            initialized = true;
        }
    }

    @Test
    void testBasicSearch() throws SolrServerException, IOException {
        // Test basic search with no parameters
        SearchResponse result = searchService.search(COLLECTION_NAME, null, null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        assertEquals(10, documents.size());
    }

    @Test
    void testSearchWithQuery() throws SolrServerException, IOException {
        // Test search with query
        SearchResponse result = searchService.search(COLLECTION_NAME, "name:\"Game of Thrones\"", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> book = documents.getFirst();
        assertEquals("A Game of Thrones", ((List<?>) book.get("name")).getFirst());
    }

    @Test
    void testSearchReturnsAuthor() throws Exception {
        // Test search with filter query
        SearchResponse result = searchService.search(
                COLLECTION_NAME, "author_ss:\"George R.R. Martin\"", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(3, documents.size());

        Map<String, Object> book = documents.getFirst();
        assertEquals("George R.R. Martin", ((List<?>) book.get("author_ss")).getFirst());
    }

    @Test
    void testSearchWithFacets() throws Exception {
        // Test search with facets
        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, null, List.of("genre_s"), null, null, null);

        assertNotNull(result);
        Map<String, Map<String, Long>> facets = result.facets();
        assertNotNull(facets);
        assertTrue(facets.containsKey("genre_s"));
    }

    @Test
    void testSearchWithPrice() throws Exception {
        // Test search with sorting
        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        Map<String, Object> book = documents.getFirst();
        double currentPrice = ((List<?>) book.get("price")).isEmpty() ? 0.0 : ((Number) ((List<?>) book.get("price")).getFirst()).doubleValue();
        assertTrue(currentPrice > 0);
    }

    @Test
    void testSortByPriceAscending() throws Exception {
        // Test sorting by price in ascending order
        List<Map<String, String>> sortClauses = List.of(
                Map.of("item", "price", "order", "asc")
        );

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, null, null, sortClauses, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());

        // Verify documents are sorted by price in ascending order
        double previousPrice = 0.0;
        for (Map<String, Object> book : documents) {
            OptionalDouble priceOpt = extractPrice(book);
            if (priceOpt.isEmpty()) {
                continue;
            }
            
            double currentPrice = priceOpt.getAsDouble();
            assertTrue(currentPrice >= previousPrice, "Books should be sorted by price in ascending order");
            previousPrice = currentPrice;
        }
    }

    @Test
    void testSortByPriceDescending() throws Exception {
        // Test sorting by price in descending order
        List<Map<String, String>> sortClauses = List.of(
                Map.of("item", "price", "order", "desc")
        );

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, null, null, sortClauses, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());

        // Verify documents are sorted by price in descending order
        double previousPrice = Double.MAX_VALUE;
        for (Map<String, Object> book : documents) {
            OptionalDouble priceOpt = extractPrice(book);
            if (priceOpt.isEmpty()) {
                continue;
            }
            
            double currentPrice = priceOpt.getAsDouble();
            assertTrue(currentPrice <= previousPrice, "Books should be sorted by price in descending order");
            previousPrice = currentPrice;
        }
    }

    @Test
    void testSortBySequence() throws Exception {

        // Test sorting by sequence_i field
        List<Map<String, String>> sortClauses = List.of(
                Map.of("item", "sequence_i", "order", "asc")
        );

        // Filter to only get books from the same series to test sequence sorting
        List<String> filterQueries = List.of("series_s:\"A Song of Ice and Fire\"");

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, filterQueries, null, sortClauses, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());

        // Verify documents are sorted by sequence_i in ascending order
        int previousSequence = 0;
        for (Map<String, Object> book : documents) {
            int currentSequence = ((Number) book.get("sequence_i")).intValue();
            assertTrue(currentSequence >= previousSequence, "Books should be sorted by sequence_i in ascending order");
            previousSequence = currentSequence;
        }
    }

    @Test
    void testFilterByGenre() throws Exception {

        // Test filtering by genre_s field
        List<String> filterQueries = List.of("genre_s:fantasy");

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, filterQueries, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());

        // Verify all returned documents have genre_s = fantasy
        for (Map<String, Object> book : documents) {
            String genre = (String) book.get("genre_s");
            assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
        }
    }

    @Test
    void testFilterByPriceRange() throws Exception {
        // Test filtering by price range
        List<String> filterQueries = List.of("price:[6.0 TO 7.0]");

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, filterQueries, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());

        // Verify all returned documents have price between 6.0 and 7.0
        for (Map<String, Object> book : documents) {
            // Skip books without a price field
            if (book.get("price") == null) {
                continue;
            }

            OptionalDouble priceOpt = extractPrice(book);
            if (priceOpt.isEmpty()) {
                continue;
            }
            
            double price = priceOpt.getAsDouble();
            assertTrue(price >= 6.0 && price <= 7.0, "All books should have price between 6.0 and 7.0");
        }
    }

    @Test
    void testCombinedSortingAndFiltering() throws Exception {
        // Test combining sorting and filtering
        List<Map<String, String>> sortClauses = List.of(
                Map.of("item", "price", "order", "desc")
        );

        List<String> filterQueries = List.of("genre_s:fantasy");

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, filterQueries, null, sortClauses, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());

        // Verify all returned documents have genre_s = fantasy
        for (Map<String, Object> book : documents) {
            String genre = (String) book.get("genre_s");
            assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
        }

        // Verify documents are sorted by price in descending order
        double previousPrice = Double.MAX_VALUE;
        for (Map<String, Object> book : documents) {
            // Skip books without a price field
            if (book.get("price") == null) {
                continue;
            }

            // Handle the case where price might be a List or a direct value
            Object priceObj = book.get("price");
            double currentPrice;

            if (priceObj instanceof List) {
                List<?> priceList = (List<?>) priceObj;
                if (priceList.isEmpty()) {
                    continue; // Skip if price list is empty
                }
                currentPrice = ((Number) priceList.getFirst()).doubleValue();
            } else if (priceObj instanceof Number) {
                currentPrice = ((Number) priceObj).doubleValue();
            } else {
                continue; // Skip if price is not a number or list
            }

            assertTrue(currentPrice <= previousPrice, "Books should be sorted by price in descending order");
            previousPrice = currentPrice;
        }
    }

    @Test
    void testPagination() throws Exception {
        // First, get all documents to know how many we have
        SearchResponse allResults = searchService.search(
                COLLECTION_NAME, null, null, null, null, null, null);

        assertNotNull(allResults);
        long totalDocuments = allResults.numFound();
        assertTrue(totalDocuments > 0, "Should have at least some documents");

        // Now test pagination with start=0, rows=2 (first page with 2 items)
        SearchResponse firstPage = searchService.search(
                COLLECTION_NAME, null, null, null, null, 0, 2);

        assertNotNull(firstPage);
        assertEquals(0, firstPage.start(), "Start offset should be 0");
        assertEquals(totalDocuments, firstPage.numFound(), "Total count should match");
        assertEquals(2, firstPage.documents().size(), "Should return exactly 2 documents");

        // Test second page (start=2, rows=2)
        SearchResponse secondPage = searchService.search(
                COLLECTION_NAME, null, null, null, null, 2, 2);

        assertNotNull(secondPage);
        assertEquals(2, secondPage.start(), "Start offset should be 2");
        assertEquals(totalDocuments, secondPage.numFound(), "Total count should match");
        assertEquals(2, secondPage.documents().size(), "Should return exactly 2 documents");

        // Verify first and second page have different documents
        List<String> firstPageIds = getDocumentIds(firstPage.documents());
        List<String> secondPageIds = getDocumentIds(secondPage.documents());

        for (String id : firstPageIds) {
            assertFalse(secondPageIds.contains(id), "Second page should not contain documents from first page");
        }
    }

    private List<String> getDocumentIds(List<Map<String, Object>> documents) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> doc : documents) {
            Object idObj = doc.get("id");
            if (idObj instanceof List) {
                ids.add(((List<?>) idObj).getFirst().toString());
            } else if (idObj != null) {
                ids.add(idObj.toString());
            }
        }
        return ids;
    }

    @Test
    void testMultipleFacets() throws Exception {
        // Test search with multiple facet fields including multi-valued fields
        // Using _s suffix for single-valued strings and _ss suffix for multi-valued strings
        List<String> facetFields = List.of("genre_s", "series_s", "author_ss");

        SearchResponse result = searchService.search(
                COLLECTION_NAME, null, null, facetFields, null, null, null);

        assertNotNull(result);
        Map<String, Map<String, Long>> facets = result.facets();
        assertNotNull(facets);

        // Verify genre_s facet (single-valued)
        assertTrue(facets.containsKey("genre_s"), "Response should contain genre_s facet");
        Map<String, Long> genreFacets = facets.get("genre_s");
        assertFalse(genreFacets.isEmpty(), "genre_s facet should have values");
        assertTrue(genreFacets.containsKey("fantasy") || genreFacets.containsKey("scifi"),
                "genre_s facet should contain fantasy or scifi");

        // Verify series_s facet (single-valued)
        assertTrue(facets.containsKey("series_s"), "Response should contain series_s facet");
        Map<String, Long> seriesFacets = facets.get("series_s");
        assertFalse(seriesFacets.isEmpty(), "series_s facet should have values");

        // Verify author_ss facet (multi-valued)
        assertTrue(facets.containsKey("author_ss"), "Response should contain author_ss facet");
        Map<String, Long> authorFacets = facets.get("author_ss");
        assertFalse(authorFacets.isEmpty(), "author_ss facet should have values");
        assertTrue(authorFacets.containsKey("George R.R. Martin") || authorFacets.containsKey("J.R.R. Tolkien"),
                "author_ss facet should contain known authors");

        // Verify all facet counts are positive
        for (String genre : genreFacets.keySet()) {
            assertTrue(genreFacets.get(genre) > 0, "Genre facet count should be positive");
        }
        for (String series : seriesFacets.keySet()) {
            assertTrue(seriesFacets.get(series) > 0, "Series facet count should be positive");
        }
        for (String author : authorFacets.keySet()) {
            assertTrue(authorFacets.get(author) > 0, "Author facet count should be positive");
        }
    }

    @Test
    void testSpecialCharactersInQuery() throws Exception {
        // First, index a document with special characters
        String specialJson = """
                [
                  {
                    "id": "special001",
                    "title": "Book with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /",
                    "author_ss": ["Special Author (with parentheses)"],
                    "description": "This is a test document with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /"
                  }
                ]
                """;

        try {
            // Index the document with special characters
            indexingService.indexJsonDocuments(COLLECTION_NAME, specialJson);

            // Commit to ensure document is available for search
            solrClient.commit(COLLECTION_NAME);

            // Test searching for the document - search by ID which always works
            String query = "id:special001";
            SearchResponse result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);

            assertNotNull(result);
            assertEquals(1, result.numFound(), "Should find exactly one document");

            // Test searching with escaped parentheses in author field
            query = "author_ss:\"Special Author \\(with parentheses\\)\"";
            result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);

            assertNotNull(result);
            assertEquals(1, result.numFound(), "Should find exactly one document");

            // Test searching with wildcards
            query = "title:special*";
            result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);

            assertNotNull(result);
            assertTrue(result.numFound() > 0, "Should find at least one document");

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Helper method to extract price value from a document field.
     * Handles both List and direct Number values.
     */
    private OptionalDouble extractPrice(Map<String, Object> document) {
        Object priceObj = document.get("price");
        if (priceObj == null) {
            return OptionalDouble.empty();
        }

        if (priceObj instanceof List) {
            List<?> priceList = (List<?>) priceObj;
            if (priceList.isEmpty()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(((Number) priceList.getFirst()).doubleValue());
        } else if (priceObj instanceof Number) {
            return OptionalDouble.of(((Number) priceObj).doubleValue());
        }
        
        return OptionalDouble.empty();
    }
}
