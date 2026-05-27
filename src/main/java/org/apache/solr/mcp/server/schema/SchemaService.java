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
package org.apache.solr.mcp.server.schema;

import static org.apache.solr.mcp.server.util.JsonUtils.toJson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.AnalyzerDefinition;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Spring Service providing schema introspection and management capabilities for
 * Apache Solr collections.
 *
 * <p>
 * This service enables exploration and analysis of Solr collection schemas
 * through the Model Context Protocol (MCP), allowing AI clients to understand
 * field definitions, data types, and schema configuration for intelligent query
 * construction and data analysis workflows.
 *
 * <p>
 * <strong>Core Capabilities:</strong>
 *
 * <ul>
 * <li><strong>Schema Retrieval</strong>: Complete schema information for any
 * collection
 * <li><strong>Field Introspection</strong>: Detailed field type and
 * configuration analysis
 * <li><strong>Dynamic Field Support</strong>: Discovery of dynamic field
 * patterns and rules
 * <li><strong>Copy Field Analysis</strong>: Understanding of field copying and
 * aggregation rules
 * </ul>
 *
 * <p>
 * <strong>Schema Information Provided:</strong>
 *
 * <ul>
 * <li><strong>Field Definitions</strong>: Names, types, indexing, and storage
 * configurations
 * <li><strong>Field Types</strong>: Analyzer configurations, tokenization, and
 * filtering rules
 * <li><strong>Dynamic Fields</strong>: Pattern-based field matching and type
 * assignment
 * <li><strong>Copy Fields</strong>: Source-to-destination field copying
 * configurations
 * <li><strong>Unique Key</strong>: Primary key field identification and
 * configuration
 * </ul>
 *
 * <p>
 * <strong>MCP Tool Integration:</strong>
 *
 * <p>
 * Schema operations are exposed as MCP tools that AI clients can invoke through
 * natural language requests such as "show me the schema for my_collection" or
 * "what fields are available for searching in the products index".
 *
 * <p>
 * <strong>Use Cases:</strong>
 *
 * <ul>
 * <li><strong>Query Planning</strong>: Understanding available fields for
 * search construction
 * <li><strong>Data Analysis</strong>: Identifying field types and capabilities
 * for analytics
 * <li><strong>Index Optimization</strong>: Analyzing field configurations for
 * performance tuning
 * <li><strong>Schema Documentation</strong>: Generating documentation from live
 * schema definitions
 * </ul>
 *
 * <p>
 * <strong>Integration with Other Services:</strong>
 *
 * <p>
 * Schema information complements other MCP services by providing the metadata
 * necessary for intelligent search query construction, field validation, and
 * result interpretation.
 *
 * <p>
 * <strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // Get complete schema information
 * SchemaRepresentation schema = schemaService.getSchema("products");
 *
 * // Analyze field configurations
 * schema.getFields().forEach(field -> {
 * 	System.out.println("Field: " + field.getName() + " Type: " + field.getType());
 * });
 *
 * // Examine dynamic field patterns
 * schema.getDynamicFields().forEach(dynField -> {
 * 	System.out.println("Pattern: " + dynField.getName() + " Type: " + dynField.getType());
 * });
 * }</pre>
 *
 * @see SchemaRepresentation
 * @see org.apache.solr.client.solrj.request.schema.SchemaRequest
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Service
@Observed
public class SchemaService {

	/** SolrJ client for communicating with Solr server */
	private final SolrClient solrClient;

	private final ObjectMapper objectMapper;

	/**
	 * Constructs a new SchemaService with the required dependencies.
	 *
	 * <p>
	 * This constructor is automatically called by Spring's dependency injection
	 * framework during application startup, providing the service with the
	 * necessary Solr client for schema operations.
	 *
	 * @param solrClient
	 *            the SolrJ client instance for communicating with Solr
	 * @param objectMapper
	 *            the Jackson ObjectMapper for JSON serialization
	 * @see SolrClient
	 */
	public SchemaService(SolrClient solrClient, ObjectMapper objectMapper) {
		this.solrClient = solrClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * MCP Resource endpoint that returns the schema for a specified Solr
	 * collection.
	 *
	 * <p>
	 * This resource uses a URI template with {collection} placeholder that can be
	 * completed using the {@code @McpComplete} endpoint in CollectionService. The
	 * returned JSON contains the complete schema definition including fields, field
	 * types, dynamic fields, and copy fields.
	 *
	 * @param collection
	 *            the name of the collection to retrieve schema for
	 * @return JSON string containing the schema representation
	 */
	@McpResource(uri = "solr://{collection}/schema", name = "solr-collection-schema", description = "Schema definition for a Solr collection including fields, field types, and copy fields", mimeType = "application/json")
	public String getSchemaResource(String collection) {
		try {
			return toJson(objectMapper, getSchema(collection));
		} catch (Exception e) {
			return "{\"error\": \"" + e.getMessage() + "\"}";
		}
	}

	/**
	 * Retrieves the complete schema definition for a specified Solr collection.
	 *
	 * <p>
	 * This method provides comprehensive access to all schema components including
	 * field definitions, field types, dynamic fields, copy fields, and schema-level
	 * configuration. The returned schema representation contains all information
	 * necessary for understanding the collection's data structure and capabilities.
	 *
	 * <p>
	 * <strong>Schema Components Included:</strong>
	 *
	 * <ul>
	 * <li><strong>Fields</strong>: Static field definitions with types and
	 * properties
	 * <li><strong>Field Types</strong>: Analyzer configurations and processing
	 * rules
	 * <li><strong>Dynamic Fields</strong>: Pattern-based field matching rules
	 * <li><strong>Copy Fields</strong>: Field copying and aggregation
	 * configurations
	 * <li><strong>Unique Key</strong>: Primary key field specification
	 * <li><strong>Schema Attributes</strong>: Version, name, and global settings
	 * </ul>
	 *
	 * <p>
	 * <strong>Field Information Details:</strong>
	 *
	 * <p>
	 * Each field definition includes comprehensive metadata:
	 *
	 * <ul>
	 * <li><strong>Name</strong>: Field identifier for queries and indexing
	 * <li><strong>Type</strong>: Reference to field type configuration
	 * <li><strong>Indexed</strong>: Whether the field is searchable
	 * <li><strong>Stored</strong>: Whether field values are retrievable
	 * <li><strong>Multi-valued</strong>: Whether multiple values are allowed
	 * <li><strong>Required</strong>: Whether the field must have a value
	 * </ul>
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * AI clients can invoke this method with natural language requests such as:
	 *
	 * <ul>
	 * <li>"Show me the schema for the products collection"
	 * <li>"What fields are available in my_index?"
	 * <li>"Get the field definitions for the search index"
	 * </ul>
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * If the collection does not exist or schema retrieval fails, the method will
	 * throw an exception with details about the failure reason. Common issues
	 * include collection name typos, permission problems, or Solr connectivity
	 * issues.
	 *
	 * <p>
	 * <strong>Performance Considerations:</strong>
	 *
	 * <p>
	 * Schema information is typically cached by Solr and retrieval is generally
	 * fast. However, for applications that frequently access schema information,
	 * consider implementing client-side caching to reduce network overhead.
	 *
	 * @param collection
	 *            the name of the Solr collection to retrieve schema information for
	 * @return complete schema representation containing all field and type
	 *         definitions
	 * @throws Exception
	 *             if collection does not exist, access is denied, or communication
	 *             fails
	 * @see SchemaRepresentation
	 * @see SchemaRequest
	 * @see org.apache.solr.client.solrj.response.schema.SchemaResponse
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "get-schema", annotations = @McpTool.McpAnnotations(readOnlyHint = true), description = "Get schema for a Solr collection")
	public SchemaRepresentation getSchema(String collection) throws Exception {
		SchemaRequest schemaRequest = new SchemaRequest();
		return schemaRequest.process(solrClient, collection).getSchemaRepresentation();
	}

	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "add-fields", annotations = @McpTool.McpAnnotations(destructiveHint = false), description = "Add one or more fields to a Solr collection schema. "
			+ "Call get-schema first to inspect existing field configuration before adding. "
			+ "Each field map follows the Solr Schema API add-field shape: required keys "
			+ "'name' and 'type', plus optional 'stored', 'indexed', 'docValues', "
			+ "'multiValued', 'required', 'omitNorms', etc. "
			+ "Example: {\"name\":\"platform\",\"type\":\"string\",\"stored\":true,\"indexed\":true,\"docValues\":true}. "
			+ "Use 'strings' (not 'string') for multi-valued string fields. "
			+ "Note: this only adds new fields; existing fields cannot be modified. "
			+ "Solr's Schema API is transactional — if any command in the batch fails, "
			+ "none are applied. On failure, fix the invalid field(s) and retry the whole batch.")
	public SchemaUpdateResult addFields(@McpToolParam(description = "Solr collection name") String collection,
			@McpToolParam(description = "List of field definitions (Solr add-field JSON shape)") List<Map<String, Object>> fields)
			throws SolrServerException, IOException {
		requireCollection(collection);
		requireNonEmpty(fields, "fields");

		List<String> names = new ArrayList<>(fields.size());
		List<SchemaRequest.Update> updates = new ArrayList<>(fields.size());
		for (Map<String, Object> field : fields) {
			names.add((String) field.get("name"));
			updates.add(new SchemaRequest.AddField(field));
		}

		new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
		return new SchemaUpdateResult(collection, names);
	}

	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "add-field-types", annotations = @McpTool.McpAnnotations(destructiveHint = false), description = "Add one or more field types to a Solr collection schema. "
			+ "Call get-schema first to inspect existing field types before adding. "
			+ "Each map follows the Solr Schema API add-field-type shape: required keys "
			+ "'name' and 'class', optional 'analyzer' (or 'indexAnalyzer'+'queryAnalyzer'), "
			+ "and class-specific attributes. " + "Common recipes: "
			+ "(1) case-insensitive exact match: class=solr.TextField with analyzer "
			+ "{tokenizer:{class:solr.KeywordTokenizerFactory}, filters:[{class:solr.LowerCaseFilterFactory}]}; "
			+ "(2) dense vector for semantic search: class=solr.DenseVectorField with "
			+ "vectorDimension, similarityFunction (cosine/dot_product/euclidean), and knnAlgorithm=hnsw; "
			+ "(3) autocomplete: class=solr.TextField with separate indexAnalyzer using EdgeNGramFilterFactory "
			+ "and queryAnalyzer without it. " + "After adding a type, use add-fields to create fields of that type. "
			+ "Solr's Schema API is transactional — if any command in the batch fails, none are applied.")
	public SchemaUpdateResult addFieldTypes(@McpToolParam(description = "Solr collection name") String collection,
			@McpToolParam(description = "List of field type definitions (Solr add-field-type JSON shape)") List<Map<String, Object>> fieldTypes)
			throws SolrServerException, IOException {
		requireCollection(collection);
		requireNonEmpty(fieldTypes, "fieldTypes");

		List<String> names = new ArrayList<>(fieldTypes.size());
		List<SchemaRequest.Update> updates = new ArrayList<>(fieldTypes.size());
		for (Map<String, Object> fieldType : fieldTypes) {
			names.add((String) fieldType.get("name"));
			updates.add(new SchemaRequest.AddFieldType(toFieldTypeDefinition(fieldType)));
		}

		new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
		return new SchemaUpdateResult(collection, names);
	}

	/**
	 * Builds a {@link FieldTypeDefinition} from a flat input map matching the Solr
	 * Schema API add-field-type JSON shape. SolrJ's {@code FieldTypeDefinition}
	 * stores name/class and other scalar attributes inside an attributes map, with
	 * analyzers pulled into typed sub-objects — so we can't deserialize the flat
	 * input directly via Jackson.
	 */
	private FieldTypeDefinition toFieldTypeDefinition(Map<String, Object> input) {
		FieldTypeDefinition def = new FieldTypeDefinition();
		Map<String, Object> attributes = new LinkedHashMap<>(input);
		Object analyzer = attributes.remove("analyzer");
		Object indexAnalyzer = attributes.remove("indexAnalyzer");
		Object queryAnalyzer = attributes.remove("queryAnalyzer");
		def.setAttributes(attributes);
		if (analyzer != null) {
			def.setAnalyzer(toAnalyzerDefinition(analyzer));
		}
		if (indexAnalyzer != null) {
			def.setIndexAnalyzer(toAnalyzerDefinition(indexAnalyzer));
		}
		if (queryAnalyzer != null) {
			def.setQueryAnalyzer(toAnalyzerDefinition(queryAnalyzer));
		}
		return def;
	}

	/**
	 * Builds an {@link AnalyzerDefinition} from a flat input map matching the Solr
	 * Schema API analyzer shape. SolrJ's {@code AnalyzerDefinition} only exposes
	 * typed setters for {@code charFilters}, {@code tokenizer}, and
	 * {@code filters}; every other analyzer-level key (e.g.
	 * {@code "class":"solr.WhitespaceAnalyzer"}, {@code "luceneMatchVersion"},
	 * {@code "positionIncrementGap"}) must go through {@code setAttributes} or Solr
	 * never sees them.
	 *
	 * <p>
	 * A naive {@code objectMapper.convertValue(raw, AnalyzerDefinition.class)}
	 * silently drops those unrecognized top-level keys, which breaks the valid
	 * single-class analyzer form
	 * {@code {"analyzer":{"class":"solr.WhitespaceAnalyzer"}}}. This helper does
	 * the split manually so unknown keys are preserved as attributes.
	 */
	private AnalyzerDefinition toAnalyzerDefinition(Object raw) {
		if (!(raw instanceof Map<?, ?> rawMap)) {
			// Defensive: unexpected shape (e.g. analyzer specified as a bare class
			// name string). Let Jackson try its default conversion.
			return objectMapper.convertValue(raw, AnalyzerDefinition.class);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> input = (Map<String, Object>) rawMap;

		AnalyzerDefinition def = new AnalyzerDefinition();
		Map<String, Object> attributes = new LinkedHashMap<>(input);

		Object charFilters = attributes.remove("charFilters");
		Object tokenizer = attributes.remove("tokenizer");
		Object filters = attributes.remove("filters");

		// Anything left (class, luceneMatchVersion, positionIncrementGap, ...)
		// is an attribute and must be passed through; SolrJ emits these inside
		// the analyzer JSON alongside the typed sub-objects.
		if (!attributes.isEmpty()) {
			def.setAttributes(attributes);
		}
		if (charFilters instanceof List<?> charFilterList) {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> typed = (List<Map<String, Object>>) charFilterList;
			def.setCharFilters(typed);
		}
		if (tokenizer instanceof Map<?, ?> tokenizerMap) {
			@SuppressWarnings("unchecked")
			Map<String, Object> typed = (Map<String, Object>) tokenizerMap;
			def.setTokenizer(typed);
		}
		if (filters instanceof List<?> filterList) {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> typed = (List<Map<String, Object>>) filterList;
			def.setFilters(typed);
		}
		return def;
	}

	private static void requireCollection(String collection) {
		if (collection == null || collection.isBlank()) {
			throw new IllegalArgumentException("Collection name must not be blank");
		}
	}

	private static void requireNonEmpty(List<?> list, String name) {
		if (list == null || list.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be empty");
		}
	}
}
