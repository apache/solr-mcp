/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.mcp.server.metadata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for ResourceService.
 *
 * <p>Tests the MCP Resource implementations for Solr collections, verifying resource retrieval,
 * JSON serialization, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    @Mock private SolrClient solrClient;

    @Mock private CollectionService collectionService;

    private ResourceService resourceService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resourceService = new ResourceService(solrClient, collectionService);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testGetSchemaResource() throws Exception {
        // Given
        String collection = "test_collection";
        SchemaRepresentation mockSchema = mock(SchemaRepresentation.class);
        SchemaResponse mockResponse = mock(SchemaResponse.class);

        Map<String, Object> field1 = new HashMap<>();
        field1.put("name", "id");
        field1.put("type", "string");

        Map<String, Object> field2 = new HashMap<>();
        field2.put("name", "title");
        field2.put("type", "text_general");

        when(mockSchema.getFields()).thenReturn(List.of(field1, field2));
        when(mockResponse.getSchemaRepresentation()).thenReturn(mockSchema);
        when(solrClient.request(any(SchemaRequest.class), eq(collection)))
                .thenReturn(mockResponse.getResponse());
        when(solrClient.request(any(SchemaRequest.class), eq(collection)))
                .thenAnswer(
                        invocation -> {
                            SchemaResponse response = new SchemaResponse();
                            response.setResponse(mockResponse.getResponse());
                            return response.getResponse();
                        });

        // Simplified mock - just verify the method can be called
        SchemaRequest mockRequest = mock(SchemaRequest.class);
        when(mockRequest.process(solrClient, collection)).thenReturn(mockResponse);

        // When
        String result = resourceService.getSchemaResource(collection);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        // Verify it's valid JSON
        JsonNode jsonNode = objectMapper.readTree(result);
        assertThat(jsonNode).isNotNull();
    }

    @Test
    void testGetStatsResource() throws Exception {
        // Given
        String collection = "test_collection";
        IndexStats indexStats = new IndexStats(1000L, 5);
        QueryStats queryStats = new QueryStats(50, 1000L, 0L, 10.5f);
        SolrMetrics mockMetrics =
                new SolrMetrics(indexStats, queryStats, null, null, new java.util.Date());

        when(collectionService.getCollectionStats(collection)).thenReturn(mockMetrics);

        // When
        String result = resourceService.getStatsResource(collection);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();

        // Verify it's valid JSON
        JsonNode jsonNode = objectMapper.readTree(result);
        assertThat(jsonNode).isNotNull();
        assertThat(jsonNode.has("indexStats")).isTrue();
        assertThat(jsonNode.get("indexStats").get("numDocs").asLong()).isEqualTo(1000L);
    }

    @Test
    void testGetSamplesResource() throws Exception {
        // Given
        String collection = "test_collection";
        SolrDocumentList documentList = new SolrDocumentList();

        SolrDocument doc1 = new SolrDocument();
        doc1.setField("id", "1");
        doc1.setField("title", "Sample Document 1");
        doc1.setField("content", "This is sample content");
        documentList.add(doc1);

        SolrDocument doc2 = new SolrDocument();
        doc2.setField("id", "2");
        doc2.setField("title", "Sample Document 2");
        doc2.setField("content", "This is more sample content");
        documentList.add(doc2);

        QueryResponse mockResponse = mock(QueryResponse.class);
        when(mockResponse.getResults()).thenReturn(documentList);
        when(solrClient.query(eq(collection), any(SolrQuery.class))).thenReturn(mockResponse);

        // When
        String result = resourceService.getSamplesResource(collection);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();

        // Verify it's valid JSON array
        JsonNode jsonNode = objectMapper.readTree(result);
        assertThat(jsonNode.isArray()).isTrue();
        assertThat(jsonNode.size()).isEqualTo(2);
        assertThat(jsonNode.get(0).get("id").asText()).isEqualTo("1");
        assertThat(jsonNode.get(0).get("title").asText()).isEqualTo("Sample Document 1");
    }

    @Test
    void testGetCollectionsListResource() throws Exception {
        // Given
        List<String> mockCollections = List.of("collection1", "collection2", "collection3");
        when(collectionService.listCollections()).thenReturn(mockCollections);

        // When
        String result = resourceService.getCollectionsListResource();

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();

        // Verify it's valid JSON array
        JsonNode jsonNode = objectMapper.readTree(result);
        assertThat(jsonNode.isArray()).isTrue();
        assertThat(jsonNode.size()).isEqualTo(3);
        assertThat(jsonNode.get(0).asText()).isEqualTo("collection1");
        assertThat(jsonNode.get(1).asText()).isEqualTo("collection2");
        assertThat(jsonNode.get(2).asText()).isEqualTo("collection3");
    }

    @Test
    void testGetSamplesResourceWithEmptyCollection() throws Exception {
        // Given
        String collection = "empty_collection";
        SolrDocumentList documentList = new SolrDocumentList();

        QueryResponse mockResponse = mock(QueryResponse.class);
        when(mockResponse.getResults()).thenReturn(documentList);
        when(solrClient.query(eq(collection), any(SolrQuery.class))).thenReturn(mockResponse);

        // When
        String result = resourceService.getSamplesResource(collection);

        // Then
        assertThat(result).isNotNull();
        JsonNode jsonNode = objectMapper.readTree(result);
        assertThat(jsonNode.isArray()).isTrue();
        assertThat(jsonNode.size()).isEqualTo(0);
    }
}
