package org.apache.solr.mcp.server;

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
        SearchResponse result = searchService.search("books", null, null, null, null);

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
        SearchResponse result = searchService.search("books", null, null, List.of("genre_s"), null);

        // Verify
        assertNotNull(result);
        assertTrue(result.facets().containsKey("genre_s"));

        Map<String, Long> genreFacets = result.facets().get("genre_s");
        assertEquals(2, genreFacets.size());
        assertEquals(1L, genreFacets.get("fantasy"));
        assertEquals(1L, genreFacets.get("scifi"));
    }
}