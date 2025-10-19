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
package org.apache.solr.mcp.server.metadata;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Spring Service providing schema introspection and management capabilities for Apache Solr
 * collections.
 *
 * <p>This service enables exploration and analysis of Solr collection schemas through the Model
 * Context Protocol (MCP), allowing AI clients to understand field definitions, data types, and
 * schema configuration for intelligent query construction and data analysis workflows.
 *
 * <p><strong>Core Capabilities:</strong>
 *
 * <ul>
 *   <li><strong>Schema Retrieval</strong>: Complete schema information for any collection
 *   <li><strong>Field Introspection</strong>: Detailed field type and configuration analysis
 *   <li><strong>Dynamic Field Support</strong>: Discovery of dynamic field patterns and rules
 *   <li><strong>Copy Field Analysis</strong>: Understanding of field copying and aggregation rules
 * </ul>
 *
 * <p><strong>Schema Information Provided:</strong>
 *
 * <ul>
 *   <li><strong>Field Definitions</strong>: Names, types, indexing, and storage configurations
 *   <li><strong>Field Types</strong>: Analyzer configurations, tokenization, and filtering rules
 *   <li><strong>Dynamic Fields</strong>: Pattern-based field matching and type assignment
 *   <li><strong>Copy Fields</strong>: Source-to-destination field copying configurations
 *   <li><strong>Unique Key</strong>: Primary key field identification and configuration
 * </ul>
 *
 * <p><strong>MCP Tool Integration:</strong>
 *
 * <p>Schema operations are exposed as MCP tools that AI clients can invoke through natural language
 * requests such as "show me the schema for my_collection" or "what fields are available for
 * searching in the products index".
 *
 * <p><strong>Use Cases:</strong>
 *
 * <ul>
 *   <li><strong>Query Planning</strong>: Understanding available fields for search construction
 *   <li><strong>Data Analysis</strong>: Identifying field types and capabilities for analytics
 *   <li><strong>Index Optimization</strong>: Analyzing field configurations for performance tuning
 *   <li><strong>Schema Documentation</strong>: Generating documentation from live schema
 *       definitions
 * </ul>
 *
 * <p><strong>Integration with Other Services:</strong>
 *
 * <p>Schema information complements other MCP services by providing the metadata necessary for
 * intelligent search query construction, field validation, and result interpretation.
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // Get complete schema information
 * SchemaRepresentation schema = schemaService.getSchema("products");
 *
 * // Analyze field configurations
 * schema.getFields().forEach(field -> {
 *     System.out.println("Field: " + field.getName() + " Type: " + field.getType());
 * });
 *
 * // Examine dynamic field patterns
 * schema.getDynamicFields().forEach(dynField -> {
 *     System.out.println("Pattern: " + dynField.getName() + " Type: " + dynField.getType());
 * });
 * }</pre>
 *
 * @version 0.0.1
 * @see SchemaRepresentation
 * @see org.apache.solr.client.solrj.request.schema.SchemaRequest
 * @see org.springframework.ai.tool.annotation.Tool
 * @since 0.0.1
 */
@Service
public class SchemaService {

    /**
     * SolrJ client for communicating with Solr server
     */
    private final SolrClient solrClient;

    /**
     * Constructs a new SchemaService with the required SolrClient dependency.
     *
     * <p>This constructor is automatically called by Spring's dependency injection framework during
     * application startup, providing the service with the necessary Solr client for schema
     * operations.
     *
     * @param solrClient the SolrJ client instance for communicating with Solr
     * @see SolrClient
     */
    public SchemaService(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    /**
     * Retrieves the complete schema definition for a specified Solr collection.
     *
     * <p>This method provides comprehensive access to all schema components including field
     * definitions, field types, dynamic fields, copy fields, and schema-level configuration. The
     * returned schema representation contains all information necessary for understanding the
     * collection's data structure and capabilities.
     *
     * <p><strong>Schema Components Included:</strong>
     *
     * <ul>
     *   <li><strong>Fields</strong>: Static field definitions with types and properties
     *   <li><strong>Field Types</strong>: Analyzer configurations and processing rules
     *   <li><strong>Dynamic Fields</strong>: Pattern-based field matching rules
     *   <li><strong>Copy Fields</strong>: Field copying and aggregation configurations
     *   <li><strong>Unique Key</strong>: Primary key field specification
     *   <li><strong>Schema Attributes</strong>: Version, name, and global settings
     * </ul>
     *
     * <p><strong>Field Information Details:</strong>
     *
     * <p>Each field definition includes comprehensive metadata:
     *
     * <ul>
     *   <li><strong>Name</strong>: Field identifier for queries and indexing
     *   <li><strong>Type</strong>: Reference to field type configuration
     *   <li><strong>Indexed</strong>: Whether the field is searchable
     *   <li><strong>Stored</strong>: Whether field values are retrievable
     *   <li><strong>Multi-valued</strong>: Whether multiple values are allowed
     *   <li><strong>Required</strong>: Whether the field must have a value
     * </ul>
     *
     * <p><strong>MCP Tool Usage:</strong>
     *
     * <p>AI clients can invoke this method with natural language requests such as:
     *
     * <ul>
     *   <li>"Show me the schema for the products collection"
     *   <li>"What fields are available in my_index?"
     *   <li>"Get the field definitions for the search index"
     * </ul>
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>If the collection does not exist or schema retrieval fails, the method will throw an
     * exception with details about the failure reason. Common issues include collection name typos,
     * permission problems, or Solr connectivity issues.
     *
     * <p><strong>Performance Considerations:</strong>
     *
     * <p>Schema information is typically cached by Solr and retrieval is generally fast. However,
     * for applications that frequently access schema information, consider implementing client-side
     * caching to reduce network overhead.
     *
     * @param collection the name of the Solr collection to retrieve schema information for
     * @return complete schema representation containing all field and type definitions
     * @throws SolrServerException if collection does not exist, access is denied, or Solr server
     *     error occurs
     * @throws IOException if communication with Solr server fails
     * @see SchemaRepresentation
     * @see SchemaRequest
     * @see org.apache.solr.client.solrj.response.schema.SchemaResponse
     */
    @McpTool(description = "Get schema for a Solr collection")
    public SchemaRepresentation getSchema(String collection)
            throws SolrServerException, IOException {
        SchemaRequest schemaRequest = new SchemaRequest();
        return schemaRequest.process(solrClient, collection).getSchemaRepresentation();
    }
}
