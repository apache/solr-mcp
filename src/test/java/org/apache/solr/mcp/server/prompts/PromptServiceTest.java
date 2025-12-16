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
package org.apache.solr.mcp.server.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.mcp.server.metadata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PromptService.
 *
 * <p>Tests the MCP Prompt implementations for Solr operations, verifying prompt generation, context
 * embedding, and content formatting.
 */
@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock private SolrClient solrClient;

    @Mock private CollectionService collectionService;

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        promptService = new PromptService(solrClient, collectionService);
    }

    @Test
    void testBuildSearchQueryPrompt() throws Exception {
        // Given
        String collection = "books";
        String description = "Find fantasy books by George R.R. Martin";

        SchemaRepresentation mockSchema = createMockSchema();
        SchemaResponse mockResponse = mock(SchemaResponse.class);
        when(mockResponse.getSchemaRepresentation()).thenReturn(mockSchema);

        SchemaRequest mockRequest = mock(SchemaRequest.class);
        when(mockRequest.process(solrClient, collection)).thenReturn(mockResponse);

        // When
        String result = promptService.buildSearchQueryPrompt(collection, description);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        assertThat(result).contains("Build Solr Search Query");
        assertThat(result).contains(collection);
        assertThat(result).contains(description);
        assertThat(result).contains("Available Fields");
        assertThat(result).contains("Dynamic Fields");
        assertThat(result).contains("Query Syntax Tips");
        assertThat(result).contains("Example Queries");
    }

    @Test
    void testPrepareIndexingPromptJson() throws Exception {
        // Given
        String collection = "products";
        String format = "json";

        SchemaRepresentation mockSchema = createMockSchema();
        SchemaResponse mockResponse = mock(SchemaResponse.class);
        when(mockResponse.getSchemaRepresentation()).thenReturn(mockSchema);

        SchemaRequest mockRequest = mock(SchemaRequest.class);
        when(mockRequest.process(solrClient, collection)).thenReturn(mockResponse);

        // When
        String result = promptService.prepareIndexingPrompt(collection, format);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        assertThat(result).contains("Prepare Documents for Indexing");
        assertThat(result).contains(collection);
        assertThat(result).contains(format);
        assertThat(result).contains("Schema Fields");
        assertThat(result).contains("Unique Key Field");
        assertThat(result).contains("JSON Format Example");
        assertThat(result).contains("Indexing Tips");
    }

    @Test
    void testPrepareIndexingPromptCsv() throws Exception {
        // Given
        String collection = "products";
        String format = "csv";

        SchemaRepresentation mockSchema = createMockSchema();
        SchemaResponse mockResponse = mock(SchemaResponse.class);
        when(mockResponse.getSchemaRepresentation()).thenReturn(mockSchema);

        SchemaRequest mockRequest = mock(SchemaRequest.class);
        when(mockRequest.process(solrClient, collection)).thenReturn(mockResponse);

        // When
        String result = promptService.prepareIndexingPrompt(collection, format);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).contains("CSV Format Example");
        assertThat(result).contains("header row");
    }

    @Test
    void testPrepareIndexingPromptXml() throws Exception {
        // Given
        String collection = "products";
        String format = "xml";

        SchemaRepresentation mockSchema = createMockSchema();
        SchemaResponse mockResponse = mock(SchemaResponse.class);
        when(mockResponse.getSchemaRepresentation()).thenReturn(mockSchema);

        SchemaRequest mockRequest = mock(SchemaRequest.class);
        when(mockRequest.process(solrClient, collection)).thenReturn(mockResponse);

        // When
        String result = promptService.prepareIndexingPrompt(collection, format);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).contains("XML Format Example");
        assertThat(result).contains("<documents>");
    }

    @Test
    void testAnalyzeCollectionPrompt() throws Exception {
        // Given
        String collection = "test_collection";

        IndexStats indexStats = new IndexStats(10000L, 3);
        QueryStats queryStats = new QueryStats(45, 10000L, 0L, 8.5f);
        CacheInfo queryCacheInfo = new CacheInfo(1000L, 850L, 0.85f, 1000L, 150L, 850L);
        CacheStats cacheStats = new CacheStats(queryCacheInfo, null, null);

        SolrMetrics mockMetrics =
                new SolrMetrics(indexStats, queryStats, cacheStats, null, new Date());
        SolrHealthStatus mockHealth =
                new SolrHealthStatus(true, null, 25L, 10000L, new Date(), null, null, null);

        when(collectionService.getCollectionStats(collection)).thenReturn(mockMetrics);
        when(collectionService.checkHealth(collection)).thenReturn(mockHealth);

        // When
        String result = promptService.analyzeCollectionPrompt(collection);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        assertThat(result).contains("Analyze Solr Collection");
        assertThat(result).contains(collection);
        assertThat(result).contains("Health Status");
        assertThat(result).contains("Index Statistics");
        assertThat(result).contains("Query Performance");
        assertThat(result).contains("Cache Performance");
        assertThat(result).contains("Analysis Questions");
        assertThat(result).contains("10000"); // Document count
        assertThat(result).contains("✅ Yes"); // Healthy status
    }

    @Test
    void testAnalyzeCollectionPromptUnhealthy() throws Exception {
        // Given
        String collection = "unhealthy_collection";

        IndexStats indexStats = new IndexStats(100L, 50); // High segment count
        QueryStats queryStats = new QueryStats(500, 100L, 0L, 2.0f); // Slow query
        SolrMetrics mockMetrics = new SolrMetrics(indexStats, queryStats, null, null, new Date());
        SolrHealthStatus mockHealth =
                new SolrHealthStatus(
                        false, "Connection timeout", null, null, new Date(), null, null, null);

        when(collectionService.getCollectionStats(collection)).thenReturn(mockMetrics);
        when(collectionService.checkHealth(collection)).thenReturn(mockHealth);

        // When
        String result = promptService.analyzeCollectionPrompt(collection);

        // Then
        assertThat(result).contains("❌ No");
        assertThat(result).contains("Connection timeout");
    }

    @Test
    void testDesignSchemaPrompt() throws Exception {
        // Given
        String collection = "new_collection";
        String requirements =
                "Need to store product information with title, description, price,"
                        + " and categories";

        SchemaRepresentation mockSchema = createMockSchema();
        SchemaResponse mockResponse = mock(SchemaResponse.class);
        when(mockResponse.getSchemaRepresentation()).thenReturn(mockSchema);

        SchemaRequest mockRequest = mock(SchemaRequest.class);
        when(mockRequest.process(solrClient, collection)).thenReturn(mockResponse);

        // When
        String result = promptService.designSchemaPrompt(collection, requirements);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
        assertThat(result).contains("Design Solr Schema");
        assertThat(result).contains(collection);
        assertThat(result).contains(requirements);
        assertThat(result).contains("Current Schema");
        assertThat(result).contains("Available Field Types");
        assertThat(result).contains("Schema Design Best Practices");
        assertThat(result).contains("Field Naming");
        assertThat(result).contains("Field Types Selection");
        assertThat(result).contains("Performance Considerations");
        assertThat(result).contains("Dynamic Fields");
        assertThat(result).contains("Design Questions");
    }

    /**
     * Creates a mock SchemaRepresentation for testing.
     *
     * @return mock schema with sample fields and configuration
     */
    private SchemaRepresentation createMockSchema() {
        SchemaRepresentation mockSchema = mock(SchemaRepresentation.class);

        Map<String, Object> field1 = new HashMap<>();
        field1.put("name", "id");
        field1.put("type", "string");
        field1.put("required", true);
        field1.put("multiValued", false);

        Map<String, Object> field2 = new HashMap<>();
        field2.put("name", "title");
        field2.put("type", "text_general");
        field2.put("required", false);
        field2.put("multiValued", false);

        Map<String, Object> field3 = new HashMap<>();
        field3.put("name", "price");
        field3.put("type", "float");
        field3.put("required", false);
        field3.put("multiValued", false);

        Map<String, Object> dynField1 = new HashMap<>();
        dynField1.put("name", "*_s");
        dynField1.put("type", "string");

        Map<String, Object> dynField2 = new HashMap<>();
        dynField2.put("name", "*_t");
        dynField2.put("type", "text_general");

        when(mockSchema.getFields()).thenReturn(List.of(field1, field2, field3));
        when(mockSchema.getDynamicFields()).thenReturn(List.of(dynField1, dynField2));
        when(mockSchema.getUniqueKey()).thenReturn("id");

        return mockSchema;
    }
}
