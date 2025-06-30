package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.FacetParams;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    private final SolrClient solrClient;

    public SearchService(SolrClient solrClient) {
        this.solrClient = solrClient;
    }
    @Tool(name = "Search",
            description = """
    Search specified Solr collection with query, optional filters, facets, and sorting. 
    Note that solr has dynamic fields where name of field in schema may end with suffixes
    _s: Represents a string field, used for exact string matching.
    _i: Represents an integer field.
    _l: Represents a long field.
    _f: Represents a float field.
    _d: Represents a double field.
    _dt: Represents a date field.
    _b: Represents a boolean field.
    _t: Often used for text fields that undergo tokenization and analysis.
    One example from the books collection:
    {
          "id":"0553579908",
          "cat":["book"],
          "name":["A Clash of Kings"],
          "price":[7.99],
          "inStock":[true],
          "author":["George R.R. Martin"],
          "series_t":"A Song of Ice and Fire",
          "sequence_i":2,
          "genre_s":"fantasy",
          "_version_":1836275819373133824,
          "_root_":"0553579908"
        }
    """)
    public SearchResponse search(
            @ToolParam(description = "Solr collection to query") String collection,
            @ToolParam(description = "Solr q parameter. If none specified defaults to \"*:*\"", required = false) String query,
            @ToolParam(description = "Solr fq parameter", required = false) List<String> filterQueries,
            @ToolParam(description = "Solr facet fields", required = false)List<String> facetFields,
            @ToolParam(description = "Solr sort parameter", required = false) List<SolrQuery.SortClause> sortClauses)
            throws SolrServerException, IOException {

        // query
        SolrQuery solrQuery = new SolrQuery("*:*");
        if (StringUtils.hasText(query)) {
            solrQuery.setQuery(query);
        }

        // filter queries
        if (!CollectionUtils.isEmpty(filterQueries)) {
            solrQuery.setFilterQueries(filterQueries.toArray(new String[0]));
        }

        // facets
        if (!CollectionUtils.isEmpty(facetFields)) {
            solrQuery.setFacet(true);
            solrQuery.addFacetField(facetFields.toArray(new String[0]));
            solrQuery.setFacetMinCount(1);
            solrQuery.setFacetSort(FacetParams.FACET_SORT_COUNT);
        }

        // sorting
        if(!CollectionUtils.isEmpty(sortClauses)){
            solrQuery.setSorts(sortClauses);
        }

        QueryResponse queryResponse = solrClient.query(collection, solrQuery);

        // Add documents
        SolrDocumentList documents = queryResponse.getResults();

        // Convert SolrDocuments to Maps
        List<Map<String, Object>> docs = new java.util.ArrayList<>(documents.size());
        documents.forEach(doc -> {
            Map<String, Object> docMap = new HashMap<>();
            for (String fieldName : doc.getFieldNames()) {
                docMap.put(fieldName, doc.getFieldValue(fieldName));
            }
            docs.add(docMap);
        });

        // Add facets if present
        Map<String, Map<String, Long>> facets = new HashMap<>();
        if (queryResponse.getFacetFields() != null && !queryResponse.getFacetFields().isEmpty()) {
            queryResponse.getFacetFields().forEach(facetField -> {
                Map<String, Long> facetValues = new HashMap<>();
                for (FacetField.Count count : facetField.getValues()) {
                    facetValues.put(count.getName(), count.getCount());
                }
                facets.put(facetField.getName(), facetValues);
            });
        }

        return new SearchResponse(
                documents.getNumFound(),
                documents.getStart(),
                documents.getMaxScore(),
                docs,
                facets
        );

    }


}
