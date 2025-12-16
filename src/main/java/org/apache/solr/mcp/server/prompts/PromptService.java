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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Service;

/**
 * Spring Service providing MCP Prompts for Apache Solr operations.
 *
 * <p>This service exposes pre-built prompt templates that guide AI interactions with Solr
 * collections. Prompts provide structured starting points for common Solr workflows, embedding
 * relevant context like schemas, statistics, and best practices.
 *
 * <p><strong>Available Prompts:</strong>
 *
 * <ul>
 *   <li><strong>build-search-query</strong>: Guides construction of effective Solr queries
 *   <li><strong>prepare-indexing</strong>: Assists with document indexing workflows
 *   <li><strong>analyze-collection</strong>: Helps analyze collection health and performance
 *   <li><strong>design-schema</strong>: Aids in schema design and optimization
 * </ul>
 *
 * <p><strong>Prompt Features:</strong>
 *
 * <ul>
 *   <li>Context-aware with embedded collection metadata
 *   <li>Parametrized for specific collections and use cases
 *   <li>Include relevant documentation and examples
 *   <li>Guide users through complex Solr operations
 * </ul>
 *
 * <p><strong>Usage Pattern:</strong>
 *
 * <p>AI clients can invoke prompts by name with specific arguments. Prompts dynamically fetch
 * relevant context (schemas, stats) and generate comprehensive guidance messages.
 *
 * @version 0.0.1
 * @since 0.0.1
 * @see McpPrompt
 * @see org.apache.solr.client.solrj.SolrClient
 */
@Service
public class PromptService {

    private final SolrClient solrClient;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new PromptService with required dependencies.
     *
     * @param solrClient the SolrJ client for Solr communication
     * @param collectionService the service for collection operations
     */
    public PromptService(SolrClient solrClient, CollectionService collectionService) {
        this.solrClient = solrClient;
        this.collectionService = collectionService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Provides a prompt for building effective Solr search queries.
     *
     * <p>This prompt guides users through constructing Solr queries by providing the collection
     * schema, available fields, field types, and query syntax examples. It helps users understand
     * what fields are searchable and how to construct queries that match their intent.
     *
     * <p><strong>Arguments:</strong>
     *
     * <ul>
     *   <li><strong>collection</strong>: The collection to query
     *   <li><strong>description</strong>: Natural language description of what to search for
     * </ul>
     *
     * <p><strong>Context Provided:</strong>
     *
     * <ul>
     *   <li>Complete schema with field definitions
     *   <li>Dynamic field patterns
     *   <li>Sample documents for reference
     *   <li>Query syntax examples
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @param description natural language description of the search intent
     * @return formatted prompt message with schema context and guidance
     * @throws Exception if schema retrieval fails
     */
    @McpPrompt(
            name = "build-search-query",
            description =
                    "Guide for building effective Solr search queries with collection context")
    public String buildSearchQueryPrompt(String collection, String description) throws Exception {
        SchemaRequest schemaRequest = new SchemaRequest();
        SchemaRepresentation schema =
                schemaRequest.process(solrClient, collection).getSchemaRepresentation();

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Build Solr Search Query\n\n");
        prompt.append("## Task\n");
        prompt.append("Help build an effective Solr query for: ")
                .append(description)
                .append("\n\n");

        prompt.append("## Collection: ").append(collection).append("\n\n");

        prompt.append("## Available Fields\n");
        schema.getFields()
                .forEach(
                        field -> {
                            prompt.append("- **")
                                    .append(field.get("name"))
                                    .append("** (")
                                    .append(field.get("type"))
                                    .append(")");
                            if (Boolean.TRUE.equals(field.get("multiValued"))) {
                                prompt.append(" [multi-valued]");
                            }
                            prompt.append("\n");
                        });

        prompt.append("\n## Dynamic Fields\n");
        schema.getDynamicFields()
                .forEach(
                        dynField -> {
                            prompt.append("- **")
                                    .append(dynField.get("name"))
                                    .append("** → ")
                                    .append(dynField.get("type"))
                                    .append("\n");
                        });

        prompt.append("\n## Query Syntax Tips\n");
        prompt.append("- Use `field:value` for specific field searches\n");
        prompt.append("- Use `*:*` to match all documents\n");
        prompt.append("- Use `AND`, `OR`, `NOT` for boolean logic\n");
        prompt.append("- Use quotes for exact phrases: `\"George R.R. Martin\"`\n");
        prompt.append("- Use wildcards: `auth*` or `*son`\n");
        prompt.append("- Use range queries: `price:[10 TO 20]`\n");
        prompt.append("- Remember to use the correct field suffixes (_s, _t, _i, etc.)\n\n");

        prompt.append("## Example Queries\n");
        prompt.append("```\n");
        prompt.append("q=*:*                          # All documents\n");
        prompt.append("q=name:\"Some Title\"           # Exact phrase match\n");
        prompt.append("q=author_s:Martin AND genre_s:fantasy  # Boolean query\n");
        prompt.append("q=price_f:[0 TO 20]           # Range query\n");
        prompt.append("```\n\n");

        prompt.append("Now, please build an appropriate query for the described search.\n");

        return prompt.toString();
    }

    /**
     * Provides a prompt for preparing document indexing operations.
     *
     * <p>This prompt guides users through the document indexing process by providing the collection
     * schema, required fields, field types, and format-specific examples. It helps ensure documents
     * are properly structured before indexing.
     *
     * <p><strong>Arguments:</strong>
     *
     * <ul>
     *   <li><strong>collection</strong>: The target collection for indexing
     *   <li><strong>format</strong>: Document format (json, csv, or xml)
     * </ul>
     *
     * <p><strong>Context Provided:</strong>
     *
     * <ul>
     *   <li>Schema field definitions and types
     *   <li>Required vs optional fields
     *   <li>Format-specific examples
     *   <li>Field naming conventions
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @param format the document format (json, csv, or xml)
     * @return formatted prompt message with schema context and indexing guidance
     * @throws Exception if schema retrieval fails
     */
    @McpPrompt(
            name = "prepare-indexing",
            description = "Guide for preparing and indexing documents into Solr collections")
    public String prepareIndexingPrompt(String collection, String format) throws Exception {
        SchemaRequest schemaRequest = new SchemaRequest();
        SchemaRepresentation schema =
                schemaRequest.process(solrClient, collection).getSchemaRepresentation();

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Prepare Documents for Indexing\n\n");
        prompt.append("## Collection: ").append(collection).append("\n");
        prompt.append("## Format: ").append(format).append("\n\n");

        prompt.append("## Schema Fields\n");
        schema.getFields()
                .forEach(
                        field -> {
                            prompt.append("- **")
                                    .append(field.get("name"))
                                    .append("** (")
                                    .append(field.get("type"))
                                    .append(")");
                            if (Boolean.TRUE.equals(field.get("required"))) {
                                prompt.append(" **[REQUIRED]**");
                            }
                            if (Boolean.TRUE.equals(field.get("multiValued"))) {
                                prompt.append(" [multi-valued]");
                            }
                            prompt.append("\n");
                        });

        prompt.append("\n## Unique Key Field\n");
        prompt.append("- ").append(schema.getUniqueKey()).append("\n\n");

        prompt.append("## Dynamic Field Patterns\n");
        schema.getDynamicFields()
                .forEach(
                        dynField -> {
                            prompt.append("- **")
                                    .append(dynField.get("name"))
                                    .append("** → ")
                                    .append(dynField.get("type"))
                                    .append("\n");
                        });

        prompt.append("\n## Indexing Tips\n");
        prompt.append("- Ensure all required fields are present\n");
        prompt.append("- Use appropriate field suffixes for dynamic fields (_s, _t, _i, etc.)\n");
        prompt.append(
                "- Multi-valued fields accept arrays: `\"categories\": [\"fiction\","
                        + " \"fantasy\"]`\n");
        prompt.append("- Invalid field names will be sanitized automatically\n");
        prompt.append("- Documents without a unique key will be auto-generated\n\n");

        if ("json".equalsIgnoreCase(format)) {
            prompt.append("## JSON Format Example\n");
            prompt.append("```json\n");
            prompt.append("[\n");
            prompt.append("  {\n");
            prompt.append("    \"id\": \"unique-id-1\",\n");
            prompt.append("    \"title_t\": \"Document Title\",\n");
            prompt.append("    \"content_t\": \"Document content here\",\n");
            prompt.append("    \"category_s\": \"books\",\n");
            prompt.append("    \"tags_ss\": [\"tag1\", \"tag2\"],\n");
            prompt.append("    \"price_f\": 19.99\n");
            prompt.append("  }\n");
            prompt.append("]\n");
            prompt.append("```\n");
        } else if ("csv".equalsIgnoreCase(format)) {
            prompt.append("## CSV Format Example\n");
            prompt.append("```csv\n");
            prompt.append("id,title_t,content_t,category_s,price_f\n");
            prompt.append("unique-id-1,Document Title,Document content,books,19.99\n");
            prompt.append("unique-id-2,Another Doc,More content,articles,9.99\n");
            prompt.append("```\n");
            prompt.append(
                    "\nNote: CSV format requires header row and doesn't support multi-valued"
                            + " fields.\n");
        } else if ("xml".equalsIgnoreCase(format)) {
            prompt.append("## XML Format Example\n");
            prompt.append("```xml\n");
            prompt.append("<documents>\n");
            prompt.append("  <doc>\n");
            prompt.append("    <id>unique-id-1</id>\n");
            prompt.append("    <title_t>Document Title</title_t>\n");
            prompt.append("    <content_t>Document content here</content_t>\n");
            prompt.append("    <category_s>books</category_s>\n");
            prompt.append("    <price_f>19.99</price_f>\n");
            prompt.append("  </doc>\n");
            prompt.append("</documents>\n");
            prompt.append("```\n");
        }

        prompt.append(
                "\nNow, please prepare your documents according to this schema and format.\n");

        return prompt.toString();
    }

    /**
     * Provides a prompt for analyzing collection health and performance.
     *
     * <p>This prompt guides users through collection analysis by providing current metrics,
     * statistics, and identifying potential issues or optimization opportunities. It combines
     * schema information with performance data for comprehensive analysis.
     *
     * <p><strong>Arguments:</strong>
     *
     * <ul>
     *   <li><strong>collection</strong>: The collection to analyze
     * </ul>
     *
     * <p><strong>Context Provided:</strong>
     *
     * <ul>
     *   <li>Collection statistics and metrics
     *   <li>Index health indicators
     *   <li>Query performance metrics
     *   <li>Cache utilization
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @return formatted prompt message with collection stats and analysis guidance
     * @throws Exception if stats retrieval fails
     */
    @McpPrompt(
            name = "analyze-collection",
            description = "Guide for analyzing Solr collection health and performance")
    public String analyzeCollectionPrompt(String collection) throws Exception {
        var stats = collectionService.getCollectionStats(collection);
        var health = collectionService.checkHealth(collection);

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Analyze Solr Collection\n\n");
        prompt.append("## Collection: ").append(collection).append("\n\n");

        prompt.append("## Health Status\n");
        prompt.append("- **Healthy**: ").append(health.isHealthy() ? "✅ Yes" : "❌ No").append("\n");
        if (health.responseTime() != null) {
            prompt.append("- **Response Time**: ").append(health.responseTime()).append("ms\n");
        }
        if (health.documentCount() != null) {
            prompt.append("- **Document Count**: ").append(health.documentCount()).append("\n");
        }
        prompt.append("\n");

        prompt.append("## Index Statistics\n");
        if (stats.indexStats() != null) {
            prompt.append("- **Number of Documents**: ")
                    .append(stats.indexStats().numDocs())
                    .append("\n");
            if (stats.indexStats().segmentCount() != null) {
                prompt.append("- **Segment Count**: ")
                        .append(stats.indexStats().segmentCount())
                        .append("\n");
            }
        }
        prompt.append("\n");

        prompt.append("## Query Performance\n");
        if (stats.queryStats() != null) {
            prompt.append("- **Last Query Time**: ")
                    .append(stats.queryStats().queryTime())
                    .append("ms\n");
            prompt.append("- **Total Results**: ")
                    .append(stats.queryStats().totalResults())
                    .append("\n");
        }
        prompt.append("\n");

        if (stats.cacheStats() != null) {
            prompt.append("## Cache Performance\n");
            if (stats.cacheStats().queryResultCache() != null) {
                var cache = stats.cacheStats().queryResultCache();
                prompt.append("### Query Result Cache\n");
                prompt.append("- Hit Ratio: ")
                        .append(
                                cache.hitRatio() != null
                                        ? String.format("%.2f%%", cache.hitRatio() * 100)
                                        : "N/A")
                        .append("\n");
                prompt.append("- Evictions: ").append(cache.evictions()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("## Analysis Questions\n");
        prompt.append("1. Is the collection healthy and performing well?\n");
        prompt.append("2. Are there any performance bottlenecks?\n");
        prompt.append("3. Is cache utilization optimal?\n");
        prompt.append("4. Are there opportunities for optimization?\n");
        prompt.append("5. What recommendations would improve performance?\n\n");

        prompt.append(
                "Please analyze the above metrics and provide insights and"
                        + " recommendations.\n");

        return prompt.toString();
    }

    /**
     * Provides a prompt for designing or optimizing Solr schemas.
     *
     * <p>This prompt guides users through schema design decisions by providing current schema
     * information, best practices, and recommendations based on specific requirements.
     *
     * <p><strong>Arguments:</strong>
     *
     * <ul>
     *   <li><strong>collection</strong>: The collection to design schema for
     *   <li><strong>requirements</strong>: Natural language description of schema requirements
     * </ul>
     *
     * <p><strong>Context Provided:</strong>
     *
     * <ul>
     *   <li>Current schema structure
     *   <li>Available field types
     *   <li>Best practices for field design
     *   <li>Performance considerations
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @param requirements natural language description of schema requirements
     * @return formatted prompt message with schema design guidance
     * @throws Exception if schema retrieval fails
     */
    @McpPrompt(
            name = "design-schema",
            description = "Guide for designing or optimizing Solr collection schemas")
    public String designSchemaPrompt(String collection, String requirements) throws Exception {
        SchemaRequest schemaRequest = new SchemaRequest();
        SchemaRepresentation schema =
                schemaRequest.process(solrClient, collection).getSchemaRepresentation();

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Design Solr Schema\n\n");
        prompt.append("## Collection: ").append(collection).append("\n\n");

        prompt.append("## Requirements\n");
        prompt.append(requirements).append("\n\n");

        prompt.append("## Current Schema\n");
        prompt.append("- **Unique Key**: ").append(schema.getUniqueKey()).append("\n");
        prompt.append("- **Field Count**: ").append(schema.getFields().size()).append("\n");
        prompt.append("- **Dynamic Fields**: ")
                .append(schema.getDynamicFields().size())
                .append("\n\n");

        prompt.append("## Available Field Types\n");
        List<String> fieldTypes =
                List.of(
                        "string",
                        "text_general",
                        "text_en",
                        "int",
                        "long",
                        "float",
                        "double",
                        "boolean",
                        "date",
                        "binary");
        fieldTypes.forEach(type -> prompt.append("- ").append(type).append("\n"));
        prompt.append("\n");

        prompt.append("## Schema Design Best Practices\n\n");

        prompt.append("### Field Naming\n");
        prompt.append("- Use descriptive field names\n");
        prompt.append("- Use consistent naming conventions\n");
        prompt.append("- Add type suffixes for dynamic fields (_s, _t, _i, _f, _d, _dt, _b)\n\n");

        prompt.append("### Field Types Selection\n");
        prompt.append("- **string**: Exact matching, sorting, faceting (not analyzed)\n");
        prompt.append("- **text_general**: Full-text search with analysis\n");
        prompt.append("- **text_en**: English-language text with stemming\n");
        prompt.append("- **int/long/float/double**: Numeric fields for range queries\n");
        prompt.append("- **date**: Date/time fields with proper parsing\n");
        prompt.append("- **boolean**: True/false values\n\n");

        prompt.append("### Performance Considerations\n");
        prompt.append("- Set `indexed=\"true\"` for searchable fields\n");
        prompt.append("- Set `stored=\"true\"` for fields you need to retrieve\n");
        prompt.append("- Use `docValues=\"true\"` for sorting and faceting\n");
        prompt.append("- Consider `multiValued=\"true\"` for array-like data\n");
        prompt.append("- Use copy fields to aggregate content for search\n\n");

        prompt.append("### Dynamic Fields\n");
        prompt.append("- Provide flexibility without pre-defining every field\n");
        prompt.append("- Common patterns: *_s (string), *_t (text), *_i (int), etc.\n");
        prompt.append("- Use sparingly to maintain schema clarity\n\n");

        prompt.append("## Design Questions\n");
        prompt.append("1. What fields are needed to fulfill the requirements?\n");
        prompt.append("2. What field types are appropriate for each field?\n");
        prompt.append("3. Which fields need to be indexed, stored, or both?\n");
        prompt.append("4. Should any fields use docValues for better performance?\n");
        prompt.append("5. Are dynamic fields or copy fields beneficial?\n\n");

        prompt.append("Please design or recommend schema changes based on the requirements.\n");

        return prompt.toString();
    }
}
