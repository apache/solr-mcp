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

import io.micrometer.observation.annotation.Observed;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.request.schema.AnalyzerDefinition;
import org.apache.solr.client.solrj.request.schema.FieldTypeDefinition;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.security.McpAuthorities;
import org.apache.solr.mcp.server.util.McpResults;
import org.apache.solr.mcp.server.util.PromptArguments;
import org.apache.solr.mcp.server.util.ResourceUris;
import org.apache.solr.mcp.server.util.SolrCalls;
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
import org.springframework.util.MimeTypeUtils;
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
 * @see McpTool
 */
@Service
@Observed
public class SchemaService {

  /** The description of the {@code collection} parameter of the schema tools that take one. */
  private static final String COLLECTION_PARAM_DESCRIPTION = "Solr collection name";

  private static final Logger logger = LoggerFactory.getLogger(SchemaService.class);

  /**
   * Name of the {@code view-schema} prompt. Public because {@code CollectionService} binds its
   * {@code @McpComplete} handler to this name, and MCP matches the two by exact string, so both
   * sides must reference this constant.
   */
  public static final String VIEW_SCHEMA_PROMPT = "view-schema";

  /**
   * Description of the {@code view-schema} prompt, shared by its {@code @McpPrompt} and its result.
   */
  private static final String VIEW_SCHEMA_PROMPT_DESCRIPTION =
      "Read-only walkthrough: fetch the schema and summarize fields, types, dynamic fields, copy fields, and the unique key.";

  /**
   * Name of the {@code design-schema} prompt. Public because {@code CollectionService} binds its
   * {@code @McpComplete} handler to this name, and MCP matches the two by exact string, so both
   * sides must reference this constant.
   */
  public static final String DESIGN_SCHEMA_PROMPT = "design-schema";

  /**
   * Description of the {@code design-schema} prompt, shared by its {@code @McpPrompt} and its
   * result.
   */
  private static final String DESIGN_SCHEMA_PROMPT_DESCRIPTION =
      "Guides the assistant through inspecting an existing Solr schema, choosing appropriate field types, and applying additive schema changes via the Schema API.";

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

  /** Leads the error text of a failed {@code get-schema}. */
  static final String GET_SCHEMA_FAILED = "Getting the schema failed";

  /** Leads the error text of a failed {@code list-fields}. */
  static final String LIST_FIELDS_FAILED = "Listing fields failed";

  /** Leads the error text of a failed {@code add-fields}. */
  static final String ADD_FIELDS_FAILED = "Adding fields failed";

  /** Leads the error text of a failed {@code add-field-types}. */
  static final String ADD_FIELD_TYPES_FAILED = "Adding field types failed";

  /** SolrJ client for communicating with Solr server */
  private final SolrClient solrClient;

  private final JsonMapper jsonMapper;

  /**
   * Constructs a new SchemaService with the required dependencies.
   *
   * <p>This constructor is automatically called by Spring's dependency injection framework during
   * application startup, providing the service with the necessary Solr client for schema
   * operations.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param jsonMapper the Jackson JsonMapper for JSON serialization
   * @see SolrClient
   */
  public SchemaService(SolrClient solrClient, JsonMapper jsonMapper) {
    this.solrClient = solrClient;
    this.jsonMapper = jsonMapper;
  }

  /**
   * MCP Resource endpoint that returns the schema for a specified Solr collection.
   *
   * <p>This resource uses a URI template with {collection} placeholder that can be completed using
   * the {@code @McpComplete} endpoint in CollectionService. The returned JSON contains the complete
   * schema definition including fields, field types, dynamic fields, and copy fields.
   *
   * @param request the read request, whose URI the result echoes
   * @param collection the name of the collection to retrieve schema for
   * @return the schema as one {@code application/json} resource content
   * @throws IllegalStateException with {@link #SCHEMA_RESOURCE_ERROR} if the schema cannot be
   *     retrieved or serialized
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpResource(
      uri = ResourceUris.COLLECTION_SCHEMA,
      name = "solr-collection-schema",
      description =
          "Full schema definition for a Solr collection: field types, declared fields, dynamic-field patterns and copy fields",
      mimeType = MimeTypeUtils.APPLICATION_JSON_VALUE)
  public ReadResourceResult getSchemaResource(ReadResourceRequest request, String collection) {
    try {
      return McpResults.jsonResource(
          request.uri(), jsonMapper.writeValueAsString(getSchema(collection)));
    } catch (IllegalArgumentException | IllegalStateException _) {
      // SolrCalls' translation of a failed request; SolrCalls has already logged the cause.
      throw new IllegalStateException(SCHEMA_RESOURCE_ERROR);
    } catch (RuntimeException e) {
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
   * <p>If the collection does not exist or schema retrieval fails, the method throws an exception
   * whose message {@link SolrCalls} writes: a hint to call {@code list-collections} for an unknown
   * collection, Solr's reason for a refused request, or a fixed text when Solr denied access or
   * could not be reached.
   *
   * @param collection the name of the Solr collection to retrieve schema information for
   * @return the schema, with the properties the contract names
   * @throws IllegalArgumentException if the collection does not exist or Solr refused the request
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see CollectionSchema
   * @see SchemaRequest
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpTool(
      name = "get-schema",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description =
          "Get the full schema definition of a Solr collection: field types with their "
              + "analyzers, declared fields, dynamic-field patterns (such as *_s) and copy fields. "
              + "A property a field does not declare is inherited from its field type; check that "
              + "type in fieldTypes. "
              + "Use this before add-fields or add-field-types to see what is already defined. "
              + "It does not say which fields hold data or which dynamic fields are in use; to "
              + "choose fields for search, call list-fields instead.",
      generateOutputSchema = true)
  public CollectionSchema getSchema(String collection) {
    // request(), not schemaRequest.process(): process() parses into SolrJ's SchemaRepresentation,
    // whose FieldTypeDefinition nests each type's properties under "attributes" and serializes
    // every unset analyzer slot as null. The Schema API's own JSON objects arrive as
    // SimpleOrderedMap, a java.util.Map, which CollectionSchema.fromSolr maps into the contract.
    NamedList<Object> response =
        SolrCalls.call(
            GET_SCHEMA_FAILED,
            SolrCalls.collectionNotFound(collection),
            () -> solrClient.request(new SchemaRequest(), collection));
    return CollectionSchema.fromSolr(asStringKeyed((Map<?, ?>) response.get(SCHEMA_SECTION)));
  }

  /**
   * MCP Resource endpoint that returns the fields holding data in a collection, the same JSON as
   * {@link #listFields(String)}.
   *
   * @param request the read request, whose URI the result echoes
   * @param collection the collection to list fields for
   * @return the field listing as one {@code application/json} resource content
   * @throws IllegalStateException with {@link #FIELDS_RESOURCE_ERROR} if the fields cannot be
   *     listed or serialized
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpResource(
      uri = ResourceUris.COLLECTION_FIELDS,
      name = "solr-collection-fields",
      description =
          "The fields that hold data in a Solr collection, with their types, flags and document counts",
      mimeType = MimeTypeUtils.APPLICATION_JSON_VALUE)
  public ReadResourceResult getFieldsResource(ReadResourceRequest request, String collection) {
    try {
      return McpResults.jsonResource(
          request.uri(), jsonMapper.writeValueAsString(listFields(collection)));
    } catch (IllegalArgumentException | IllegalStateException _) {
      // SolrCalls' translation of a failed request; SolrCalls has already logged the cause.
      throw new IllegalStateException(FIELDS_RESOURCE_ERROR);
    } catch (RuntimeException e) {
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
   * @throws IllegalArgumentException if the collection name is blank, the collection does not
   *     exist, or Solr refused the request
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.READ)
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
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection) {
    requireCollection(collection);

    LukeRequest lukeRequest = new LukeRequest();
    lukeRequest.setNumTerms(0);
    lukeRequest.setIncludeIndexFieldFlags(false);
    // request(), not lukeRequest.process(): process() also builds SolrJ's typed LukeResponse,
    // which casts each field's docs to Integer and fails on a distributed sum past 2^31 (a Long),
    // and drops dynamicBase. Luke's JSON objects arrive as SimpleOrderedMap, a java.util.Map,
    // which IndexedField.fromLuke maps into the contract.
    NamedList<Object> response =
        SolrCalls.call(
            LIST_FIELDS_FAILED,
            SolrCalls.collectionNotFound(collection),
            () -> solrClient.request(lukeRequest, collection));
    return response.get(LUKE_FIELDS_SECTION) instanceof Map<?, ?> fields
        ? IndexedField.fromLuke(asStringKeyed(fields))
        : List.of();
  }

  /**
   * Adds one or more fields to a Solr collection schema as a single transactional batch.
   *
   * @param collection target Solr collection name
   * @param fields the fields to add, in the shape {@link #getSchema(String)} reports
   * @return summary listing the collection and the names of the fields added
   * @throws IllegalArgumentException if an argument is blank or empty, the collection does not
   *     exist, or Solr refused the batch; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.ADMIN)
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
              + "none are applied. On failure, fix the invalid field(s) and retry the whole batch.",
      generateOutputSchema = true)
  public SchemaUpdateResult addFields(
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection,
      @McpToolParam(description = "The fields to add, in the shape get-schema reports")
          List<CollectionSchema.Field> fields) {
    requireCollection(collection);
    requireNonEmpty(fields, "fields");
    return applySchemaUpdates(
        collection,
        ADD_FIELDS_FAILED,
        fields,
        "field",
        CollectionSchema.Field::name,
        field -> new SchemaRequest.AddField(field.toSolr()));
  }

  /**
   * Adds one or more field types to a Solr collection schema as a single transactional batch.
   *
   * @param collection target Solr collection name
   * @param fieldTypes the field types to add, in the shape {@link #getSchema(String)} reports
   * @return summary listing the collection and the names of the field types added
   * @throws IllegalArgumentException if an argument is blank or empty, the collection does not
   *     exist, or Solr refused the batch; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.ADMIN)
  @McpTool(
      name = "add-field-types",
      annotations = @McpTool.McpAnnotations(destructiveHint = false),
      description =
          "Add one or more field types to a Solr collection schema. "
              + "Call get-schema first to inspect existing field types before adding. "
              + "Each field type has the shape get-schema reports: required 'name' and 'className', "
              + "optional 'analyzer' (or 'indexAnalyzer'+'queryAnalyzer'), optional booleans such "
              + "as 'docValues', and class-specific settings as string 'parameters'. An analyzer "
              + "has optional 'charFilters', a 'tokenizer' and 'filters', or is a single class "
              + "such as {\"className\":\"solr.WhitespaceAnalyzer\"}; it may have its own string "
              + "'parameters' too. Each component has 'name' (Solr's short name) or 'className', "
              + "plus its own string 'parameters'. "
              + "Common recipes: "
              + "(1) case-insensitive exact match: className=solr.TextField with analyzer "
              + "{tokenizer:{className:solr.KeywordTokenizerFactory}, filters:[{className:solr.LowerCaseFilterFactory}]}; "
              + "(2) dense vector for semantic search: className=solr.DenseVectorField with parameters "
              + "vectorDimension, similarityFunction (cosine/dot_product/euclidean), and knnAlgorithm=hnsw; "
              + "(3) autocomplete: className=solr.TextField with separate indexAnalyzer using EdgeNGramFilterFactory "
              + "and queryAnalyzer without it. "
              + "After adding a type, use add-fields to create fields of that type. "
              + "Solr's Schema API is transactional — if any command in the batch fails, none are applied.",
      generateOutputSchema = true)
  public SchemaUpdateResult addFieldTypes(
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection,
      @McpToolParam(description = "The field types to add, in the shape get-schema reports")
          List<CollectionSchema.FieldType> fieldTypes) {
    requireCollection(collection);
    requireNonEmpty(fieldTypes, "fieldTypes");
    return applySchemaUpdates(
        collection,
        ADD_FIELD_TYPES_FAILED,
        fieldTypes,
        "field type",
        CollectionSchema.FieldType::name,
        fieldType -> new SchemaRequest.AddFieldType(toFieldTypeDefinition(fieldType)));
  }

  /**
   * Sends one update per definition as a single {@link SchemaRequest.MultiUpdate}, which Solr
   * applies all-or-nothing. Every name is checked before anything is sent.
   *
   * @param collection target collection
   * @param operation leads the error text if Solr rejects the batch
   * @param definitions the caller-supplied definitions
   * @param kind human-readable noun for the blank-name error
   * @param nameOf reads a definition's name
   * @param toUpdate builds the Schema API command for one definition
   * @param <T> the definition type
   * @return the collection and the names of the definitions applied
   */
  private <T> SchemaUpdateResult applySchemaUpdates(
      String collection,
      String operation,
      List<T> definitions,
      String kind,
      Function<T, String> nameOf,
      Function<T, SchemaRequest.Update> toUpdate) {
    List<String> names = new ArrayList<>(definitions.size());
    List<SchemaRequest.Update> updates = new ArrayList<>(definitions.size());
    for (T definition : definitions) {
      names.add(requireName(nameOf.apply(definition), kind));
      updates.add(toUpdate.apply(definition));
    }
    SolrCalls.call(
        operation,
        SolrCalls.collectionNotFound(collection),
        () -> new SchemaRequest.MultiUpdate(updates).process(solrClient, collection));
    return new SchemaUpdateResult(collection, names);
  }

  private static String requireName(String name, String kind) {
    if (name.isBlank()) {
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

  /** Solr's JSON objects arrive as maps keyed by property name; the one unchecked cast. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> asStringKeyed(Map<?, ?> map) {
    return (Map<String, Object>) map;
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
  @PreAuthorize(McpAuthorities.READ)
  @McpPrompt(
      name = VIEW_SCHEMA_PROMPT,
      title = "View a Solr collection schema",
      description = VIEW_SCHEMA_PROMPT_DESCRIPTION)
  public GetPromptResult viewSchemaPrompt(
      @McpArg(
              name = PromptArguments.COLLECTION,
              description = PromptArguments.COLLECTION_DESCRIPTION,
              required = true)
          String collection) {
    return McpResults.prompt(
        VIEW_SCHEMA_PROMPT_DESCRIPTION,
        Role.USER,
        """
				You are inspecting the schema of Solr collection `%s`. This prompt is read-only; do not
				add or modify any fields.

				1. Fetch the schema.
				   - Call `get-schema` on `%s`. Capture the full response (fields, fieldTypes,
				     dynamicFields, copyFields, uniqueKey).

				2. Summarize fields.
				   - Total field count.
				   - Group fields by type (e.g. how many `text_general`, `string`, `strings`, `pint`,
				     `pdouble`, `pdate`, etc.).
				   - A property a field does not declare is inherited from its field type; check that
				     type in `fieldTypes`.
				   - Flag which fields are `indexed=true` (searchable / filterable), `stored=true`
				     (retrievable in responses), `docValues=true` (sortable / facetable / range-
				     filterable), and `multiValued=true`, counting the properties they inherit.
				   - Call out the `uniqueKey` field — this is the primary identifier.

				3. Summarize dynamic fields and copy fields.
				   - List dynamic-field patterns (e.g. `*_s`, `*_txt`) and their types — these accept
				     fields whose names are not predeclared.
				   - The schema shows only the patterns. To see which dynamic fields are actually in
				     use (e.g. `genre_s`) and how many documents have each, call `list-fields`. Its
				     `docs` count is missing or 0 for numeric, date and docValues-only fields because
				     Solr cannot count them, not because they are empty.
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
            .formatted(collection, collection));
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
  @PreAuthorize(McpAuthorities.READ)
  @McpPrompt(
      name = DESIGN_SCHEMA_PROMPT,
      title = "Design a Solr schema for a dataset",
      description = DESIGN_SCHEMA_PROMPT_DESCRIPTION)
  public GetPromptResult designSchemaPrompt(
      @McpArg(
              name = PromptArguments.COLLECTION,
              description = PromptArguments.COLLECTION_DESCRIPTION,
              required = true)
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
        sampleDocument == null || sampleDocument.isBlank()
            ? "   - No sample document was provided; ask the user for one if the dataset description leaves field types ambiguous."
            : "   - A sample document was provided. Use it as ground truth for field names and value\n"
                + "     shapes:\n\n     ```\n"
                + sampleDocument.strip().indent(5)
                + "     ```";
    return McpResults.prompt(
        DESIGN_SCHEMA_PROMPT_DESCRIPTION,
        Role.USER,
        """
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
            .formatted(collection, datasetDescription, collection, sampleSection));
  }
}
