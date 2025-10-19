package org.apache.solr.mcp.server.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Combined tests for SearchService: integration + unit (mocked SolrClient) in one class.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchServiceTest {

    // ===== Integration test context =====
    private static final String COLLECTION_NAME = "search_test_" + System.currentTimeMillis();
    private static boolean initialized = false;
    @Autowired
    private SearchService searchService;
    @Autowired
    private IndexingService indexingService;
    @Autowired
    private SolrClient solrClient;

    @BeforeEach
    void setUp() throws Exception {
        if (!initialized) {
            CollectionAdminRequest.Create createRequest =
                    CollectionAdminRequest.createCollection(COLLECTION_NAME, "_default", 1, 1);
            createRequest.process(solrClient);

            String sampleData =
                    """
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

    // ===== Integration tests (from original SearchServiceTest) =====

    @Test
    void testBasicSearch() throws SolrServerException, IOException {
        SearchResponse result =
                searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        assertEquals(10, documents.size());
    }

    @Test
    void testSearchWithQuery() throws SolrServerException, IOException {
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, "name:\"Game of Thrones\"", null, null, null, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());
        Map<String, Object> book = documents.getFirst();
        assertEquals("A Game of Thrones", ((List<?>) book.get("name")).getFirst());
    }

    @Test
    void testSearchReturnsAuthor() throws Exception {
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME,
                        "author_ss:\"George R.R. Martin\"",
                        null,
                        null,
                        null,
                        null,
                        null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(3, documents.size());
        Map<String, Object> book = documents.getFirst();
        assertEquals("George R.R. Martin", ((List<?>) book.get("author_ss")).getFirst());
    }

    @Test
    void testSearchWithFacets() throws Exception {
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, null, null, List.of("genre_s"), null, null, null);
        assertNotNull(result);
        Map<String, Map<String, Long>> facets = result.facets();
        assertNotNull(facets);
        assertTrue(facets.containsKey("genre_s"));
    }

    @Test
    void testSearchWithPrice() throws Exception {
        SearchResponse result =
                searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        Map<String, Object> book = documents.getFirst();
        double currentPrice =
                ((List<?>) book.get("price")).isEmpty()
                        ? 0.0
                        : ((Number) ((List<?>) book.get("price")).getFirst()).doubleValue();
        assertTrue(currentPrice > 0);
    }

    @Test
    void testSortByPriceAscending() throws Exception {
        List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "asc"));
        SearchResponse result =
                searchService.search(COLLECTION_NAME, null, null, null, sortClauses, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        double previousPrice = 0.0;
        for (Map<String, Object> book : documents) {
            OptionalDouble priceOpt = extractPrice(book);
            if (priceOpt.isEmpty()) continue;
            double currentPrice = priceOpt.getAsDouble();
            assertTrue(
                    currentPrice >= previousPrice,
                    "Books should be sorted by price in ascending order");
            previousPrice = currentPrice;
        }
    }

    @Test
    void testSortByPriceDescending() throws Exception {
        List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "desc"));
        SearchResponse result =
                searchService.search(COLLECTION_NAME, null, null, null, sortClauses, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        double previousPrice = Double.MAX_VALUE;
        for (Map<String, Object> book : documents) {
            OptionalDouble priceOpt = extractPrice(book);
            if (priceOpt.isEmpty()) continue;
            double currentPrice = priceOpt.getAsDouble();
            assertTrue(
                    currentPrice <= previousPrice,
                    "Books should be sorted by price in descending order");
            previousPrice = currentPrice;
        }
    }

    @Test
    void testSortBySequence() throws Exception {
        List<Map<String, String>> sortClauses =
                List.of(Map.of("item", "sequence_i", "order", "asc"));
        List<String> filterQueries = List.of("series_s:\"A Song of Ice and Fire\"");
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, null, filterQueries, null, sortClauses, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        int previousSequence = 0;
        for (Map<String, Object> book : documents) {
            int currentSequence = ((Number) book.get("sequence_i")).intValue();
            assertTrue(
                    currentSequence >= previousSequence,
                    "Books should be sorted by sequence_i in ascending order");
            previousSequence = currentSequence;
        }
    }

    @Test
    void testFilterByGenre() throws Exception {
        List<String> filterQueries = List.of("genre_s:fantasy");
        SearchResponse result =
                searchService.search(COLLECTION_NAME, null, filterQueries, null, null, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        for (Map<String, Object> book : documents) {
            String genre = (String) book.get("genre_s");
            assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
        }
    }

    @Test
    void testFilterByPriceRange() throws Exception {
        List<String> filterQueries = List.of("price:[6.0 TO 7.0]");
        SearchResponse result =
                searchService.search(COLLECTION_NAME, null, filterQueries, null, null, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        for (Map<String, Object> book : documents) {
            if (book.get("price") == null) continue;
            OptionalDouble priceOpt = extractPrice(book);
            if (priceOpt.isEmpty()) continue;
            double price = priceOpt.getAsDouble();
            assertTrue(
                    price >= 6.0 && price <= 7.0,
                    "All books should have price between 6.0 and 7.0");
        }
    }

    @Test
    void testCombinedSortingAndFiltering() throws Exception {
        List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "desc"));
        List<String> filterQueries = List.of("genre_s:fantasy");
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, null, filterQueries, null, sortClauses, null, null);
        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertFalse(documents.isEmpty());
        for (Map<String, Object> book : documents) {
            String genre = (String) book.get("genre_s");
            assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
        }
        double previousPrice = Double.MAX_VALUE;
        for (Map<String, Object> book : documents) {
            Object priceObj = book.get("price");
            double currentPrice;
            if (priceObj instanceof List) {
                List<?> priceList = (List<?>) priceObj;
                if (priceList.isEmpty()) {
                    continue;
                }
                currentPrice = ((Number) priceList.getFirst()).doubleValue();
            } else if (priceObj instanceof Number) {
                currentPrice = ((Number) priceObj).doubleValue();
            } else {
                continue;
            }
            assertTrue(
                    currentPrice <= previousPrice,
                    "Books should be sorted by price in descending order");
            previousPrice = currentPrice;
        }
    }

    @Test
    void testPagination() throws Exception {
        SearchResponse allResults =
                searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
        assertNotNull(allResults);
        long totalDocuments = allResults.numFound();
        assertTrue(totalDocuments > 0, "Should have at least some documents");
        SearchResponse firstPage =
                searchService.search(COLLECTION_NAME, null, null, null, null, 0, 2);
        assertNotNull(firstPage);
        assertEquals(0, firstPage.start(), "Start offset should be 0");
        assertEquals(totalDocuments, firstPage.numFound(), "Total count should match");
        assertEquals(2, firstPage.documents().size(), "Should return exactly 2 documents");
        SearchResponse secondPage =
                searchService.search(COLLECTION_NAME, null, null, null, null, 2, 2);
        assertNotNull(secondPage);
        assertEquals(2, secondPage.start(), "Start offset should be 2");
        assertEquals(totalDocuments, secondPage.numFound(), "Total count should match");
        assertEquals(2, secondPage.documents().size(), "Should return exactly 2 documents");
        List<String> firstPageIds = getDocumentIds(firstPage.documents());
        List<String> secondPageIds = getDocumentIds(secondPage.documents());
        for (String id : firstPageIds) {
            assertFalse(
                    secondPageIds.contains(id),
                    "Second page should not contain documents from first page");
        }
    }

    @Test
    void testSpecialCharactersInQuery() throws Exception {
        String specialJson =
                """
                        [
                          {
                            "id": "special001",
                            "title": "Book with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /",
                            "author_ss": ["Special Author (with parentheses)"],
                            "description": "This is a test document with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /"
                          }
                        ]
                        """;
        indexingService.indexJsonDocuments(COLLECTION_NAME, specialJson);
        solrClient.commit(COLLECTION_NAME);
        String query = "id:special001";
        SearchResponse result =
                searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
        assertNotNull(result);
        assertEquals(1, result.numFound(), "Should find exactly one document");
        query = "author_ss:\"Special Author \\(" + "with parentheses\\)\""; // escape parentheses
        result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
        assertNotNull(result);
        assertEquals(1, result.numFound(), "Should find exactly one document");
        query = "title:special*";
        result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
        assertNotNull(result);
        assertTrue(result.numFound() > 0, "Should find at least one document");
    }

    // ===== Unit-style tests with mocked SolrClient (from SearchServiceUnitTest) =====

    @Test
    void unit_constructor_ShouldInitializeWithSolrClient() {
        SearchService localService = new SearchService(mock(SolrClient.class));
        assertNotNull(localService);
    }

    @Test
    void unit_search_WithNullQuery_ShouldDefaultToMatchAll() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery q = invocation.getArgument(1);
                            assertEquals("*:*", q.getQuery());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, null, null, null, null, null);
        assertNotNull(result);
    }

    @Test
    void unit_search_WithCustomQuery_ShouldUseProvidedQuery() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        String customQuery = "name:\"Spring Boot\"";
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery q = invocation.getArgument(1);
                            assertEquals(customQuery, q.getQuery());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", customQuery, null, null, null, null, null);
        assertNotNull(result);
    }

    @Test
    void unit_search_WithFilterQueries_ShouldApplyFilters() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        List<String> filterQueries = List.of("genre_s:fantasy", "price:[0 TO 10]");
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery q = invocation.getArgument(1);
                            assertArrayEquals(filterQueries.toArray(), q.getFilterQueries());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, filterQueries, null, null, null, null);
        assertNotNull(result);
    }

    @Test
    void unit_search_WithFacetFields_ShouldEnableFaceting() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        List<String> facetFields = List.of("genre_s", "author_ss");
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(invocation -> mockResponse);
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, null, facetFields, null, null, null);
        assertNotNull(result);
        assertNotNull(result.facets());
    }

    @Test
    void unit_search_WithSortClauses_ShouldApplySorting() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        List<Map<String, String>> sortClauses =
                List.of(
                        Map.of("item", "price", "order", "asc"),
                        Map.of("item", "name", "order", "desc"));
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(invocation -> mockResponse);
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, null, null, sortClauses, null, null);
        assertNotNull(result);
    }

    @Test
    void unit_search_WithPagination_ShouldApplyStartAndRows() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        Integer start = 10;
        Integer rows = 20;
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery q = invocation.getArgument(1);
                            assertEquals(start, q.getStart());
                            assertEquals(rows, q.getRows());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, null, null, null, start, rows);
        assertNotNull(result);
    }

    @Test
    void unit_search_WithAllParameters_ShouldCombineAllOptions() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        String query = "title:Java";
        List<String> filterQueries = List.of("inStock:true");
        List<String> facetFields = List.of("category");
        List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "asc"));
        Integer start = 0;
        Integer rows = 10;
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery captured = invocation.getArgument(1);
                            assertEquals(query, captured.getQuery());
                            assertArrayEquals(filterQueries.toArray(), captured.getFilterQueries());
                            assertNotNull(captured.getFacetFields());
                            assertEquals(start, captured.getStart());
                            assertEquals(rows, captured.getRows());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search(
                        "test_collection",
                        query,
                        filterQueries,
                        facetFields,
                        sortClauses,
                        start,
                        rows);
        assertNotNull(result);
    }

    @Test
    void unit_search_WhenSolrThrowsException_ShouldPropagateException() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenThrow(new SolrServerException("Connection error"));
        SearchService localService = new SearchService(mockClient);
        assertThrows(
                SolrServerException.class,
                () -> localService.search("test_collection", null, null, null, null, null, null));
    }

    @Test
    void unit_search_WhenIOException_ShouldPropagateException() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenThrow(new IOException("Network error"));
        SearchService localService = new SearchService(mockClient);
        assertThrows(
                IOException.class,
                () -> localService.search("test_collection", null, null, null, null, null, null));
    }

    @Test
    void unit_search_WithEmptyResults_ShouldReturnEmptyDocumentList() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        SolrDocumentList emptyDocuments = new SolrDocumentList();
        emptyDocuments.setNumFound(0);
        emptyDocuments.setStart(0);
        when(mockResponse.getResults()).thenReturn(emptyDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenReturn(mockResponse);
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search(
                        "test_collection", "nonexistent:value", null, null, null, null, null);
        assertNotNull(result);
        assertEquals(0, result.numFound());
        assertTrue(result.documents().isEmpty());
    }

    @Test
    void unit_search_WithNullFilterQueries_ShouldNotApplyFilters() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery q = invocation.getArgument(1);
                            assertNull(q.getFilterQueries());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, null, null, null, null, null);
        assertNotNull(result);
    }

    @Test
    void unit_search_WithEmptyFacetFields_ShouldNotEnableFaceting() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        SolrDocumentList mockDocuments = createMockDocumentList();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(null);
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenAnswer(
                        invocation -> {
                            SolrQuery q = invocation.getArgument(1);
                            assertNull(q.getFacetFields());
                            return mockResponse;
                        });
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search("test_collection", null, null, List.of(), null, null, null);
        assertNotNull(result);
    }

    @Test
    void unit_searchResponse_ShouldContainAllFields() throws Exception {
        SolrClient mockClient = mock(SolrClient.class);
        QueryResponse mockResponse = mock(QueryResponse.class);
        SolrDocumentList mockDocuments = createMockDocumentListWithData();
        when(mockResponse.getResults()).thenReturn(mockDocuments);
        when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
        when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
                .thenReturn(mockResponse);
        SearchService localService = new SearchService(mockClient);
        SearchResponse result =
                localService.search(
                        "test_collection", null, null, List.of("genre_s"), null, null, null);
        assertNotNull(result);
        assertEquals(2, result.numFound());
        assertEquals(0, result.start());
        assertNotNull(result.documents());
        assertEquals(2, result.documents().size());
        assertNotNull(result.facets());
        assertFalse(result.facets().isEmpty());
    }

    // ===== Helpers (from unit tests and integration tests) =====

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

    private SolrDocumentList createMockDocumentList() {
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(0);
        documents.setStart(0);
        return documents;
    }

    private SolrDocumentList createMockDocumentListWithData() {
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(2);
        documents.setStart(0);
        documents.setMaxScore(1.0f);
        SolrDocument doc1 = new SolrDocument();
        doc1.setField("id", "book001");
        doc1.setField("name", "Spring Boot in Action");
        doc1.setField("author_ss", List.of("Craig Walls"));
        doc1.setField("price", 39.99);
        doc1.setField("genre_s", "technology");
        documents.add(doc1);
        SolrDocument doc2 = new SolrDocument();
        doc2.setField("id", "book002");
        doc2.setField("name", "Effective Java");
        doc2.setField("author_ss", List.of("Joshua Bloch"));
        doc2.setField("price", 44.99);
        doc2.setField("genre_s", "technology");
        documents.add(doc2);
        return documents;
    }

    private List<FacetField> createMockFacetFields() {
        FacetField genreFacet = new FacetField("genre_s");
        genreFacet.add("technology", 5);
        genreFacet.add("fiction", 3);
        FacetField authorFacet = new FacetField("author_ss");
        authorFacet.add("Craig Walls", 2);
        authorFacet.add("Joshua Bloch", 1);
        return List.of(genreFacet, authorFacet);
    }
}
