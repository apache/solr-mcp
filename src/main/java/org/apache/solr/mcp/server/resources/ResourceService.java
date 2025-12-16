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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.apache.solr.mcp.server.metadata.SolrMetrics;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Service;

/**
 * Spring Service providing MCP Resources for Apache Solr collections.
 *
 * <p>This service exposes Solr collection data as MCP Resources, allowing AI clients to reference
 * and access Solr information through standardized URI templates. Resources provide context that
 * can be embedded in prompts or referenced during AI interactions.
 *
 * <p><strong>Available Resources:</strong>
 *
 * <ul>
 *   <li><strong>schema://{collection}</strong>: Complete schema definition for a collection
 *   <li><strong>stats://{collection}</strong>: Performance metrics and statistics
 *   <li><strong>samples://{collection}</strong>: Sample documents from the collection
 *   <li><strong>collections://list</strong>: List of all available collections
 * </ul>
 *
 * <p><strong>Resource Usage:</strong>
 *
 * <p>Resources can be referenced in prompts or retrieved directly by AI clients to provide context
 * for query construction, data analysis, or schema understanding. The MCP protocol automatically
 * handles resource resolution and content delivery.
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // AI client can reference: resource://schema://books
 * // This will fetch and include the books collection schema
 * }</pre>
 *
 * @version 0.0.1
 * @since 0.0.1
 * @see McpResource
 * @see org.apache.solr.client.solrj.SolrClient
 */
@Service
public class ResourceService {

    private final SolrClient solrClient;
    private final CollectionService collectionService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new ResourceService with required dependencies.
     *
     * @param solrClient the SolrJ client for Solr communication
     * @param collectionService the service for collection operations
     */
    public ResourceService(SolrClient solrClient, CollectionService collectionService) {
        this.solrClient = solrClient;
        this.collectionService = collectionService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Provides access to collection schema as an MCP Resource.
     *
     * <p>Returns the complete schema definition for the specified collection, including field
     * definitions, field types, dynamic fields, and copy field configurations. This resource is
     * useful for AI clients to understand the structure of documents in the collection.
     *
     * <p><strong>URI Template:</strong> {@code schema://{collection}}
     *
     * <p><strong>Use Cases:</strong>
     *
     * <ul>
     *   <li>Query construction with proper field names and types
     *   <li>Document indexing with schema validation
     *   <li>Field analysis and optimization
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @return JSON string containing the complete schema representation
     * @throws Exception if schema retrieval fails
     */
    @McpResource(
            uri = "schema://{collection}",
            name = "Collection Schema",
            description =
                    "Solr collection schema definition with fields, types, and dynamic fields")
    public String getSchemaResource(String collection) throws Exception {
        SchemaRequest schemaRequest = new SchemaRequest();
        SchemaRepresentation schema =
                schemaRequest.process(solrClient, collection).getSchemaRepresentation();
        return objectMapper.writeValueAsString(schema);
    }

    /**
     * Provides access to collection statistics as an MCP Resource.
     *
     * <p>Returns comprehensive performance metrics including index statistics, query performance,
     * cache utilization, and request handler metrics. This resource helps AI clients understand
     * collection health and performance characteristics.
     *
     * <p><strong>URI Template:</strong> {@code stats://{collection}}
     *
     * <p><strong>Use Cases:</strong>
     *
     * <ul>
     *   <li>Performance analysis and optimization
     *   <li>Health monitoring and diagnostics
     *   <li>Capacity planning and scaling decisions
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @return JSON string containing collection statistics
     * @throws JsonProcessingException if JSON serialization fails
     * @throws SolrServerException if Solr communication fails
     * @throws IOException if I/O errors occur
     */
    @McpResource(
            uri = "stats://{collection}",
            name = "Collection Statistics",
            description = "Performance metrics and statistics for a Solr collection")
    public String getStatsResource(String collection)
            throws JsonProcessingException, SolrServerException, IOException {
        SolrMetrics stats = collectionService.getCollectionStats(collection);
        return objectMapper.writeValueAsString(stats);
    }

    /**
     * Provides access to sample documents from a collection as an MCP Resource.
     *
     * <p>Returns up to 5 representative documents from the specified collection, allowing AI
     * clients to understand the document structure, field usage patterns, and data types. Sample
     * documents are randomly selected to provide diverse examples.
     *
     * <p><strong>URI Template:</strong> {@code samples://{collection}}
     *
     * <p><strong>Use Cases:</strong>
     *
     * <ul>
     *   <li>Understanding document structure for query construction
     *   <li>Analyzing field usage and data patterns
     *   <li>Generating example queries or indexing templates
     * </ul>
     *
     * @param collection the name of the Solr collection
     * @return JSON string containing sample documents
     * @throws JsonProcessingException if JSON serialization fails
     * @throws SolrServerException if Solr communication fails
     * @throws IOException if I/O errors occur
     */
    @McpResource(
            uri = "samples://{collection}",
            name = "Sample Documents",
            description = "Sample documents from a Solr collection to understand structure")
    public String getSamplesResource(String collection)
            throws JsonProcessingException, SolrServerException, IOException {
        // Get up to 5 sample documents
        SolrQuery query = new SolrQuery("*:*");
        query.setRows(5);
        QueryResponse response = solrClient.query(collection, query);

        List<Map<String, Object>> samples =
                response.getResults().stream()
                        .map(
                                doc -> {
                                    Map<String, Object> docMap = new HashMap<>();
                                    for (String fieldName : doc.getFieldNames()) {
                                        docMap.put(fieldName, doc.getFieldValue(fieldName));
                                    }
                                    return docMap;
                                })
                        .collect(Collectors.toList());

        return objectMapper.writeValueAsString(samples);
    }

    /**
     * Provides access to the list of all available collections as an MCP Resource.
     *
     * <p>Returns a list of all collections/cores available in the Solr instance. This resource
     * enables AI clients to discover available data sources and make informed decisions about which
     * collections to query or analyze.
     *
     * <p><strong>URI Template:</strong> {@code collections://list}
     *
     * <p><strong>Use Cases:</strong>
     *
     * <ul>
     *   <li>Collection discovery and exploration
     *   <li>Multi-collection query planning
     *   <li>System overview and inventory
     * </ul>
     *
     * @return JSON string containing list of collection names
     * @throws JsonProcessingException if JSON serialization fails
     */
    @McpResource(
            uri = "collections://list",
            name = "Collections List",
            description = "List of all available Solr collections")
    public String getCollectionsListResource() throws JsonProcessingException {
        List<String> collections = collectionService.listCollections();
        return objectMapper.writeValueAsString(collections);
    }
}
