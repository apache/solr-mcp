package org.apache.solr.mcp.server.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceDirectTest {

    @Mock
    private SolrClient solrClient;

    @Mock
    private QueryResponse queryResponse;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(solrClient);
    }

    @Test
    void testBasicSearch() throws SolrServerException, IOException {
        // Setup mock response
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(2);
        documents.setStart(0);
        documents.setMaxScore(1.0f);

        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        doc1.addField("name", List.of("Book 1"));
        doc1.addField("author", List.of("Author 1"));

        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");
        doc2.addField("name", List.of("Book 2"));
        doc2.addField("author", List.of("Author 2"));

        documents.add(doc1);
        documents.add(doc2);

        when(queryResponse.getResults()).thenReturn(documents);
        when(solrClient.query(eq("books"), any(SolrQuery.class))).thenReturn(queryResponse);

        // Test
        SearchResponse result = searchService.search("books", null, null, null, null, null, null);

        // Verify
        assertNotNull(result);
        assertEquals(2L, result.numFound());

        List<Map<String, Object>> resultDocs = result.documents();
        assertEquals(2, resultDocs.size());
        assertEquals("1", resultDocs.get(0).get("id"));
        assertEquals("2", resultDocs.get(1).get("id"));
    }

    @Test
    void testSearchWithFacets() throws SolrServerException, IOException {
        // Setup mock response
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(2);
        documents.setStart(0);
        documents.setMaxScore(1.0f);

        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        doc1.addField("genre_s", "fantasy");

        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");
        doc2.addField("genre_s", "scifi");

        documents.add(doc1);
        documents.add(doc2);

        // Create facet fields
        List<FacetField> facetFields = new ArrayList<>();
        FacetField genreFacet = new FacetField("genre_s");
        genreFacet.add("fantasy", 1);
        genreFacet.add("scifi", 1);
        facetFields.add(genreFacet);

        when(queryResponse.getResults()).thenReturn(documents);
        when(queryResponse.getFacetFields()).thenReturn(facetFields);
        when(solrClient.query(eq("books"), any(SolrQuery.class))).thenReturn(queryResponse);

        // Test
        SearchResponse result = searchService.search("books", null, null, null, null, null, null);

        // Verify
        assertNotNull(result);
        assertTrue(result.facets().containsKey("genre_s"));

        Map<String, Long> genreFacets = result.facets().get("genre_s");
        assertEquals(2, genreFacets.size());
        assertEquals(1L, genreFacets.get("fantasy"));
        assertEquals(1L, genreFacets.get("scifi"));
    }

    @Test
    void testSearchWithEmptyResults() throws SolrServerException, IOException {
        // Setup mock response with empty results
        SolrDocumentList emptyDocuments = new SolrDocumentList();
        emptyDocuments.setNumFound(0);
        emptyDocuments.setStart(0);
        emptyDocuments.setMaxScore(0.0f);

        when(queryResponse.getResults()).thenReturn(emptyDocuments);
        when(solrClient.query(eq("books"), any(SolrQuery.class))).thenReturn(queryResponse);

        // Test
        SearchResponse result = searchService.search("books", "nonexistent_query", null, null, null, null, null);

        // Verify
        assertNotNull(result);
        assertEquals(0L, result.numFound());
        assertEquals(0, result.documents().size());
        assertTrue(result.facets().isEmpty());
    }

    @Test
    void testSearchWithEmptyFacets() throws SolrServerException, IOException {
        // Setup mock response with documents but no facets
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(2);
        documents.setStart(0);
        documents.setMaxScore(1.0f);

        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        doc1.addField("name", "Book 1");

        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");
        doc2.addField("name", "Book 2");

        documents.add(doc1);
        documents.add(doc2);

        when(queryResponse.getResults()).thenReturn(documents);
        when(queryResponse.getFacetFields()).thenReturn(null); // No facet fields
        when(solrClient.query(eq("books"), any(SolrQuery.class))).thenReturn(queryResponse);

        // Test with facet fields requested but none returned
        SearchResponse result = searchService.search("books", null, null, List.of("genre_s"), null, null, null);

        // Verify
        assertNotNull(result);
        assertEquals(2L, result.numFound());
        assertEquals(2, result.documents().size());
        assertTrue(result.facets().isEmpty());
    }

    @Test
    void testSearchWithEmptyFacetValues() throws SolrServerException, IOException {
        // Setup mock response with facet fields but no values
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(2);
        documents.setStart(0);
        documents.setMaxScore(1.0f);

        SolrDocument doc1 = new SolrDocument();
        doc1.addField("id", "1");
        SolrDocument doc2 = new SolrDocument();
        doc2.addField("id", "2");

        documents.add(doc1);
        documents.add(doc2);

        // Create facet field with no values
        List<FacetField> facetFields = new ArrayList<>();
        FacetField emptyFacet = new FacetField("genre_s");
        facetFields.add(emptyFacet);

        when(queryResponse.getResults()).thenReturn(documents);
        when(queryResponse.getFacetFields()).thenReturn(facetFields);
        when(solrClient.query(eq("books"), any(SolrQuery.class))).thenReturn(queryResponse);

        // Test
        SearchResponse result = searchService.search("books", null, null, List.of("genre_s"), null, null, null);

        // Verify
        assertNotNull(result);
        assertEquals(2L, result.numFound());
        assertTrue(result.facets().containsKey("genre_s"));
        assertTrue(result.facets().get("genre_s").isEmpty());
    }

    @Test
    void testSearchWithSolrError() {
        // Setup mock to throw exception
        try {
            when(solrClient.query(eq("books"), any(SolrQuery.class)))
                .thenThrow(new SolrServerException("Simulated Solr server error"));

            // Test
            assertThrows(SolrServerException.class, () -> {
                searchService.search("books", null, null, null, null, null, null);
            });
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
    }

    @Test
    void testSearchWithAllParameters() throws SolrServerException, IOException {
        // Setup mock response
        SolrDocumentList documents = new SolrDocumentList();
        documents.setNumFound(1);
        documents.setStart(5);
        documents.setMaxScore(0.75f);

        SolrDocument doc = new SolrDocument();
        doc.addField("id", "5");
        doc.addField("name", "Book 5");
        doc.addField("author", "Author 5");
        doc.addField("genre_s", "mystery");
        doc.addField("price", 12.99);

        documents.add(doc);

        // Create facet fields
        List<FacetField> facetFields = new ArrayList<>();
        FacetField genreFacet = new FacetField("genre_s");
        genreFacet.add("mystery", 1);
        facetFields.add(genreFacet);

        when(queryResponse.getResults()).thenReturn(documents);
        when(queryResponse.getFacetFields()).thenReturn(facetFields);
        when(solrClient.query(eq("books"), any(SolrQuery.class))).thenReturn(queryResponse);

        // Test with all parameters
        List<String> filterQueries = List.of("price:[10 TO 15]");
        List<String> facetFields2 = List.of("genre_s", "author");
        List<Map<String, String>> sortClauses = List.of(
                Map.of("item", "price", "order", "desc")
        );

        SearchResponse result = searchService.search(
            "books", "mystery", filterQueries, facetFields2, sortClauses, 5, 10);

        // Verify
        assertNotNull(result);
        assertEquals(1L, result.numFound());
        assertEquals(5, result.start());
        assertEquals(0.75f, result.maxScore());
        assertEquals(1, result.documents().size());
        assertEquals("5", result.documents().get(0).get("id"));
        assertEquals("Book 5", result.documents().get(0).get("name"));
        assertTrue(result.facets().containsKey("genre_s"));
        assertEquals(1L, result.facets().get("genre_s").get("mystery"));
    }
}
