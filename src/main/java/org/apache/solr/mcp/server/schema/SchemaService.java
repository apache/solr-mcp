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

import static org.apache.solr.mcp.server.util.PromptText.optionalCodeBlock;

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.request.schema.AnalyzerDefinition;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.util.PromptNames;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

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
 * CollectionSchema schema = schemaService.getSchema("products");
 *
 * // Analyze field configurations
 * schema.fields().forEach(field -> {
 * 	System.out.println("Field: " + field.name() + " Type: " + field.type());
 * });
 *
 * // Examine dynamic field patterns
 * schema.dynamicFields().forEach(dynField -> {
 * 	System.out.println("Pattern: " + dynField.name() + " Type: " + dynField.type());
 * });
 * }</pre>
 *
 * @see CollectionSchema
 * @see org.apache.solr.client.solrj.request.schema.SchemaRequest
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Service
@Observed
public class SchemaService {

  private static final Logger logger = LoggerFactory.getLogger(SchemaService.class);

  /** The section of a Schema API response that holds the schema. */
  private static final String SCHEMA_SECTION = "schema";

  static final String SCHEMA_RESOURCE_ERROR =
      "Failed to retrieve the schema. Hint: call list-collections to check"
          + " the collection name; if it persists, ask the operator to check the server logs.";

  static final String FIELDS_RESOURCE_ERROR =
      "Failed to list the fields. Hint: call list-collections to check"
          + " the collection name; if it persists, ask the operator to check the server logs.";

  /** The section of a Luke response that lists the fields, one entry per field in the index. */
  private static final String LUKE_FIELDS_SECTION = "fields";

  /** Keeps Luke's field order, which is alphabetical. */

  /** SolrJ client for communicating with Solr server */
  private final SolrClient solrClient;

  private final JsonMapper objectMapper;

  /**
   * Constructs a new SchemaService with the required dependencies.
   *
   * <p>This constructor is automatically called by Spring's dependency injection framework during
   * application startup, providing the service with the necessary Solr client for schema
   * operations.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param objectMapper the Jackson JsonMapper for JSON serialization
   * @see SolrClient
   */
  public SchemaService(SolrClient solrClient, JsonMapper objectMapper) {
    this.solrClient = solrClient;
    this.objectMapper = objectMapper;
  }

  /**
   * MCP Resource endpoint that returns the schema for a specified Solr collection.
   *
   * <p>This resource uses a URI template with {collection} placeholder that can be completed using
   * the {@code @McpComplete} endpoint in CollectionService. The returned JSON contains the complete
   * schema definition including fields, field types, dynamic fields, and copy fields.
   *
   * @param collection the name of the collection to retrieve schema for
   * @return JSON string containing the schema representation
   * @throws IllegalStateException with {@link #SCHEMA_RESOURCE_ERROR} if the schema cannot be
   *     retrieved or serialized
   */
  @PreAuthorize("isAuthenticated()")
  @McpResource(
      uri = "solr://{collection}/schema",
      name = "solr-collection-schema",
      description =
          "Full schema definition for a Solr collection: field types, declared fields, dynamic-field patterns and copy fields",
      mimeType = "application/json")
  public String getSchemaResource(String collection) {
    try {
      return objectMapper.writeValueAsString(getSchema(collection));
    } catch (Exception e) {
      // Deliberately no cause: MCP reports the root cause's message to the client,
      // and Solr and SolrJ failure text can carry internal host names, URLs and
      // paths. The log is the sole record of the cause.
      logger.error("Failed to get schema for collection: {}", collection, e);
      throw new IllegalStateException(SCHEMA_RESOURCE_ERROR);
    }
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
   * <p>Schema information is typically cached by Solr and retrieval is generally fast. However, for
   * applications that frequently access schema information, consider implementing client-side
   * caching to reduce network overhead.
   *
   * @param collection the name of the Solr collection to retrieve schema information for
   * @return the schema, with the properties the contract names
   * @throws SolrServerException if the Solr server returns an error; a missing collection surfaces
   *     as an unchecked {@code SolrException} (404)
   * @throws IOException if communication with Solr fails
   * @see CollectionSchema
   * @see SchemaRequest
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "get-schema",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description =
          "Get the full schema definition of a Solr collection: field types with their "
              + "analyzers, declared fields, dynamic-field patterns (such as *_s) and copy fields. "
              + "Use this before add-fields or add-field-types to see what is already defined. "
              + "It does not say which fields hold data or which dynamic fields are in use; to "
              + "choose fields for search, call list-fields instead.")
  public CollectionSchema getSchema(String collection) throws SolrServerException, IOException {
    // request(), not schemaRequest.process(): process() parses into SolrJ's SchemaRepresentation,
    // whose FieldTypeDefinition nests each type's properties under "attributes" and serializes
    // every unset analyzer slot as null. The Schema API's own JSON objects arrive as
    // SimpleOrderedMap, a java.util.Map, which CollectionSchema.fromSolr maps into the contract.
    NamedList<Object> response = solrClient.request(new SchemaRequest(), collection);
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) response.get(SCHEMA_SECTION);
    return CollectionSchema.fromSolr(schema);
  }

  /**
   * MCP Resource endpoint that returns the fields holding data in a collection, the same JSON as
   * {@link #listFields(String)}.
   *
   * @param collection the collection to list fields for
   * @return JSON string containing the field listing
   * @throws IllegalStateException with {@link #FIELDS_RESOURCE_ERROR} if the fields cannot be
   *     listed or serialized
   */
  @PreAuthorize("isAuthenticated()")
  @McpResource(
      uri = "solr://{collection}/fields",
      name = "solr-collection-fields",
      description =
          "The fields that hold data in a Solr collection, with their types, flags and document counts",
      mimeType = "application/json")
  public String getFieldsResource(String collection) {
    try {
      return objectMapper.writeValueAsString(listFields(collection));
    } catch (Exception e) {
      // No cause, for the same reason as getSchemaResource: Solr failure text can
      // carry internal host names and URLs.
      logger.error("Failed to list fields for collection: {}", collection, e);
      throw new IllegalStateException(FIELDS_RESOURCE_ERROR);
    }
  }

  /**
   * Lists the fields that hold data in a collection, from the {@code fields} section of Solr's Luke
   * response.
   *
   * <p>Unlike {@link #getSchema(String)}, Luke reports the dynamic-field instances in the index
   * ({@code genre_s}, not just {@code *_s}) and, per field, how many documents have terms in it.
   * Each field's {@code schema} flag letters are decoded into {@link FieldProperty} values, so
   * Luke's letter encoding and its {@code info} legend stay out of the result. The {@code index}
   * section is left out too: {@code get-collection-stats} reports index statistics, and its {@code
   * directory} entry names a path on the Solr host.
   *
   * <p>One collection-level request is made. Solr 9.11 and 10.1 distribute it across shards in
   * SolrCloud (SOLR-8127); older versions answer from one core, which the tool description tells
   * the model about.
   *
   * @param collection the collection to list fields for
   * @return the fields Luke reports, ordered by name, with their flags decoded
   * @throws SolrServerException if the request cannot be sent or its response read; an error Solr
   *     returns, an unknown collection's 404 included, surfaces as an unchecked {@code
   *     SolrException}
   * @throws IOException if communication with Solr fails
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "list-fields",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description =
          "List the fields that hold data in a collection, from Solr's Luke handler: per field its"
              + " type, flags (indexed, stored, docValues, multiValued, ...), dynamicBase for a"
              + " dynamic-field instance (genre_s, not *_s), and docs, the number of documents with"
              + " terms in the field (deleted documents count until their segments merge). Facet"
              + " only on fields with docValues, and sort only on ones without multiValued. Use"
              + " this before"
              + " search to decide which fields go in q, fq, facets and sorts. Unlike get-schema it"
              + " shows no analyzers or field-type definitions; use get-schema before changing the"
              + " schema. Numeric and date point fields and docValues-only fields have no terms, so"
              + " their docs is missing, or 0 on Solr 9.11+ and 10.1+: that means not counted, not"
              + " empty. Before Solr 9.11 and 10.1, a collection with more than one shard lists the"
              + " fields and counts of one shard only.")
  public List<IndexedField> listFields(
      @McpToolParam(description = "Solr collection name") String collection)
      throws SolrServerException, IOException {
    requireCollection(collection);

    LukeRequest lukeRequest = new LukeRequest();
    lukeRequest.setNumTerms(0);
    lukeRequest.setIncludeIndexFieldFlags(false);
    // request(), not lukeRequest.process(): process() also builds SolrJ's typed LukeResponse,
    // which casts each field's docs to Integer and fails on a distributed sum past 2^31 (a Long),
    // and drops dynamicBase. Luke's JSON objects arrive as SimpleOrderedMap, a java.util.Map,
    // which IndexedField.fromLuke maps into the contract.
    NamedList<Object> response = solrClient.request(lukeRequest, collection);
    return response.get(LUKE_FIELDS_SECTION) instanceof Map<?, ?> fields
        ? IndexedField.fromLuke(castFields(fields))
        : List.of();
  }

  /**
   * Adds one or more fields to a Solr collection schema as a single transactional batch.
   *
   * @param collection target Solr collection name
   * @param fields the fields to add, in the shape {@link #getSchema(String)} reports
   * @return summary listing the collection and the names of the fields added
   * @throws SolrServerException if there are errors communicating with Solr
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "add-fields",
      annotations = @McpTool.McpAnnotations(destructiveHint = false),
      description =
          "Add one or more fields to a Solr collection schema. "
              + "Call get-schema first to inspect existing field configuration before adding. "
              + "Each field has the shape get-schema reports: required 'name' and 'type', plus "
              + "optional booleans such as 'stored', 'indexed', 'docValues', 'multiValued', "
              + "'required' and 'omitNorms', and an optional 'default'. A property left out is "
              + "inherited from the field type. "
              + "Example: {\"name\":\"platform\",\"type\":\"string\",\"stored\":true,\"indexed\":true,\"docValues\":true}. "
              + "Use 'strings' (not 'string') for multi-valued string fields. "
              + "Note: this only adds new fields; existing fields cannot be modified. "
              + "Solr's Schema API is transactional — if any command in the batch fails, "
              + "none are applied. On failure, fix the invalid field(s) and retry the whole batch.")
  public SchemaUpdateResult addFields(
      @McpToolParam(description = "Solr collection name") String collection,
      @McpToolParam(description = "The fields to add, in the shape get-schema reports")
          List<CollectionSchema.Field> fields)
      throws SolrServerException, IOException {
    requireCollection(collection);
    requireNonEmpty(fields, "fields");

    List<String> names = new ArrayList<>(fields.size());
    List<SchemaRequest.Update> updates = new ArrayList<>(fields.size());
    for (CollectionSchema.Field field : fields) {
      names.add(requireName(field.name(), "field"));
      updates.add(new SchemaRequest.AddField(field.toSolr()));
    }

    new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
    return new SchemaUpdateResult(collection, names);
  }

  /**
   * Adds one or more field types to a Solr collection schema as a single transactional batch.
   *
   * @param collection target Solr collection name
   * @param fieldTypes the field types to add, in the shape {@link #getSchema(String)} reports
   * @return summary listing the collection and the names of the field types added
   * @throws SolrServerException if there are errors communicating with Solr
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "add-field-types",
      annotations = @McpTool.McpAnnotations(destructiveHint = false),
      description =
          "Add one or more field types to a Solr collection schema. "
              + "Call get-schema first to inspect existing field types before adding. "
              + "Each field type has the shape get-schema reports: required 'name' and 'className', "
              + "optional 'analyzer' (or 'indexAnalyzer'+'queryAnalyzer'), optional booleans such "
              + "as 'docValues', and class-specific settings as string 'parameters'. An analyzer "
              + "has a 'tokenizer' and 'filters'; each component has 'name' (Solr's short name) or "
              + "'className', plus its own string 'parameters'. "
              + "Common recipes: "
              + "(1) case-insensitive exact match: className=solr.TextField with analyzer "
              + "{tokenizer:{className:solr.KeywordTokenizerFactory}, filters:[{className:solr.LowerCaseFilterFactory}]}; "
              + "(2) dense vector for semantic search: className=solr.DenseVectorField with parameters "
              + "vectorDimension, similarityFunction (cosine/dot_product/euclidean), and knnAlgorithm=hnsw; "
              + "(3) autocomplete: className=solr.TextField with separate indexAnalyzer using EdgeNGramFilterFactory "
              + "and queryAnalyzer without it. "
              + "After adding a type, use add-fields to create fields of that type. "
              + "Solr's Schema API is transactional — if any command in the batch fails, none are applied.")
  public SchemaUpdateResult addFieldTypes(
      @McpToolParam(description = "Solr collection name") String collection,
      @McpToolParam(description = "The field types to add, in the shape get-schema reports")
          List<CollectionSchema.FieldType> fieldTypes)
      throws SolrServerException, IOException {
    requireCollection(collection);
    requireNonEmpty(fieldTypes, "fieldTypes");

    List<String> names = new ArrayList<>(fieldTypes.size());
    List<SchemaRequest.Update> updates = new ArrayList<>(fieldTypes.size());
    for (CollectionSchema.FieldType fieldType : fieldTypes) {
      names.add(requireName(fieldType.name(), "field type"));
      updates.add(new SchemaRequest.AddFieldType(toFieldTypeDefinition(fieldType)));
    }

    new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
    return new SchemaUpdateResult(collection, names);
  }

  private static String requireName(@Nullable String name, String kind) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "Each " + kind + " definition requires a non-empty 'name'; got: " + name);
    }
    return name;
  }

  /** The field type as SolrJ's Schema API definition: its attributes plus typed analyzers. */
  private static FieldTypeDefinition toFieldTypeDefinition(CollectionSchema.FieldType fieldType) {
    FieldTypeDefinition definition = new FieldTypeDefinition();
    definition.setAttributes(fieldType.attributesToSolr());
    definition.setAnalyzer(toAnalyzerDefinition(fieldType.analyzer()));
    definition.setIndexAnalyzer(toAnalyzerDefinition(fieldType.indexAnalyzer()));
    definition.setQueryAnalyzer(toAnalyzerDefinition(fieldType.queryAnalyzer()));
    definition.setMultiTermAnalyzer(toAnalyzerDefinition(fieldType.multiTermAnalyzer()));
    return definition;
  }

  private static @Nullable AnalyzerDefinition toAnalyzerDefinition(
      CollectionSchema.@Nullable Analyzer analyzer) {
    if (analyzer == null) {
      return null;
    }
    AnalyzerDefinition definition = new AnalyzerDefinition();
    Map<String, Object> attributes = analyzer.attributesToSolr();
    if (!attributes.isEmpty()) {
      definition.setAttributes(attributes);
    }
    if (!analyzer.charFilterList().isEmpty()) {
      definition.setCharFilters(
          analyzer.charFilterList().stream()
              .map(CollectionSchema.AnalyzerComponent::toSolr)
              .toList());
    }
    if (analyzer.tokenizer() != null) {
      definition.setTokenizer(analyzer.tokenizer().toSolr());
    }
    if (!analyzer.filterList().isEmpty()) {
      definition.setFilters(
          analyzer.filterList().stream().map(CollectionSchema.AnalyzerComponent::toSolr).toList());
    }
    return definition;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> castFields(Map<?, ?> fields) {
    return (Map<String, ?>) fields;
  }

  private static void requireCollection(String collection) {
    if (collection.isBlank()) {
      throw new IllegalArgumentException("Collection name must not be blank");
    }
  }

  private static void requireNonEmpty(List<?> list, String name) {
    if (list.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be empty");
    }
  }

  /**
   * MCP prompt that drives a read-only walkthrough of a collection's schema: fetch it and summarize
   * fields, types, dynamic fields, copy fields, and the unique key.
   *
   * @param collection target Solr collection name
   * @return the prompt text instructing the model how to inspect the schema
   */
  @PreAuthorize("isAuthenticated()")
  @McpPrompt(
      name = PromptNames.VIEW_SCHEMA,
      title = "View a Solr collection schema",
      description =
          "Read-only walkthrough: fetch the schema and summarize fields, types, dynamic fields, copy fields, and the unique key.")
  public String viewSchemaPrompt(
      @McpArg(name = "collection", description = "Target Solr collection name", required = true)
          String collection) {
    return """
				You are inspecting the schema of Solr collection `%s`. This prompt is read-only; do not
				add or modify any fields.

				1. Fetch the schema.
				   - Call `get-schema` on `%s`. Capture the full response (fields, fieldTypes,
				     dynamicFields, copyFields, uniqueKey).

				2. Summarize fields.
				   - Total field count.
				   - Group fields by type (e.g. how many `text_general`, `string`, `strings`, `pint`,
				     `pdouble`, `pdate`, etc.).
				   - Flag which fields are `indexed=true` (searchable / filterable), `stored=true`
				     (retrievable in responses), `docValues=true` (sortable / facetable / range-
				     filterable), and `multiValued=true`.
				   - Call out the `uniqueKey` field — this is the primary identifier.

				3. Summarize dynamic fields and copy fields.
				   - List dynamic-field patterns (e.g. `*_s`, `*_txt`) and their types — these accept
				     fields whose names are not predeclared.
				   - The schema shows only the patterns. To see which dynamic fields are actually in
				     use (e.g. `genre_s`) and how many documents have each, call `list-fields`.
				   - List copyField rules (source → destination) — these duplicate content into
				     aggregator fields, often used for catch-all search fields.

				4. Surface anything unusual.
				   - Custom field types (analyzers, tokenizers, filters).
				   - Required fields (`required=true`) the indexer must always populate.
				   - Fields that are indexed but not stored (searchable but not returnable) or vice
				     versa.

				Next step suggestion: if the schema is missing fields the user needs, the
				`design-schema` prompt drives the additive workflow.
				"""
        .formatted(collection, collection);
  }

  /**
   * MCP prompt that guides the client through designing a schema: inspect the existing schema,
   * choose appropriate field types, and apply additive schema changes via the Schema API.
   *
   * @param collection target Solr collection name
   * @param datasetDescription free-text description of the data being indexed (entity, key
   *     attributes, expected query patterns)
   * @param sampleDocument optional single document in JSON to ground field inference; may be {@code
   *     null} or blank
   * @return the prompt text instructing the model how to design the schema
   */
  @PreAuthorize("isAuthenticated()")
  @McpPrompt(
      name = PromptNames.DESIGN_SCHEMA,
      title = "Design a Solr schema for a dataset",
      description =
          "Guides the assistant through inspecting an existing Solr schema, choosing appropriate field types, and applying additive schema changes via the Schema API.")
  public String designSchemaPrompt(
      @McpArg(name = "collection", description = "Target Solr collection name", required = true)
          String collection,
      @McpArg(
              name = "datasetDescription",
              description =
                  "Free-text description of the data being indexed (entity, key attributes, expected query patterns)",
              required = true)
          String datasetDescription,
      @McpArg(
              name = "sampleDocument",
              description = "Optional single document in JSON to ground field inference",
              required = false)
          @Nullable String sampleDocument) {
    String sampleSection =
        optionalCodeBlock(
            sampleDocument,
            "A sample document was provided. Use it as ground truth for field names and value\n     shapes:",
            "No sample document was provided; ask the user for one if the dataset description leaves field types ambiguous.");
    return """
				You are designing a Solr schema for collection `%s`. Dataset:

				    %s

				Follow this workflow. The Solr Schema API is transactional per batch — if any command in
				a batch fails, none are applied — so plan carefully before each `add-fields` or
				`add-field-types` call.

				1. Inspect the current schema.
				   - Call `get-schema` on `%s`. Note which fields already exist and which field types
				     (e.g. `text_general`, `string`, `strings`, `pint`, `pdouble`, `pdate`) are defined.
				     Existing fields cannot be modified, only added to.

				2. Anchor on the dataset.
				%s

				3. Map dataset attributes to Solr field types.
				   - Free-text the user will search on: `text_general` (tokenized) for descriptions,
				     titles, bodies.
				   - Exact-match facets / filters: `string` for single-valued, `strings` for multi-valued
				     (categories, tags, genres).
				   - Numerics: `pint`, `plong`, `pfloat`, `pdouble`. Add `docValues=true` if you need to
				     sort, facet, or range-filter on the field.
				   - Dates: `pdate`.
				   - Identifiers: `string` with `docValues=true`.
				   - Semantic / vector search: a custom `DenseVectorField` type via `add-field-types`
				     (put `vectorDimension`, `similarityFunction` and `knnAlgorithm=hnsw` in its
				     `parameters`), then a field
				     of that type via `add-fields`.
				   - Case-insensitive exact match or autocomplete: a custom `solr.TextField` type with a
				     `KeywordTokenizerFactory` + `LowerCaseFilterFactory` analyzer (or split
				     index/query analyzers with `EdgeNGramFilterFactory` for autocomplete).

				4. Add field types first, then fields.
				   - If you need any custom field types, call `add-field-types` with the full batch in
				     one call. On failure, inspect the error, fix the offending entry, and retry the
				     entire batch.
				   - Then call `add-fields` with the full batch of new fields. Each field map must
				     include `name` and `type`; recommended extras are `stored`, `indexed`,
				     `docValues`, `multiValued`, `required`.

				5. Verify.
				   - Call `get-schema` again and confirm every desired field and type is present. If the
				     collection shares a configset with other collections, some fields may already
				     exist from earlier work — that is fine; only add the gap.

				Next step suggestion: once the schema is in place, the `index-data` prompt drives
				indexing documents into the collection.
				"""
        .formatted(collection, datasetDescription, collection, sampleSection);
  }
}
