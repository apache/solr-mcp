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

import static org.apache.solr.mcp.server.McpResultAssertions.promptText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest.MultiUpdate;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.json.JsonContent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comprehensive test suite for the SchemaService class. Tests schema retrieval functionality with
 * various scenarios including success and error cases.
 */
@JsonTest
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class SchemaServiceTest {

  /** Where an add-field-types request body holds its one field type. */
  private static final String FIELD_TYPE = "$['add-field-type']";

  @Mock private SolrClient solrClient;

  @Autowired private JsonMapper jsonMapper;

  private SchemaService schemaService;

  @BeforeEach
  void setUp() {
    schemaService = new SchemaService(solrClient, jsonMapper);
  }

  @Test
  void testSchemaService_InstantiatesCorrectly() {
    // Given/When
    SchemaService service = new SchemaService(solrClient, jsonMapper);

    // Then
    assertNotNull(service, "SchemaService should be instantiated correctly");
  }

  @Test
  void getSchema_keepsDeclaredFalseAndDropsAttributesOutsideTheContract() throws Exception {
    // The contract names each schema property. A declared false must survive, an undeclared
    // property must stay absent, and anything else Solr sends must not reach the client.
    SimpleOrderedMap<Object> field = new SimpleOrderedMap<>();
    field.add("name", "genre");
    field.add("type", "string");
    field.add("indexed", true);
    field.add("stored", false);
    field.add("futureFlag", true);
    SimpleOrderedMap<Object> schema = new SimpleOrderedMap<>();
    schema.add("name", "test");
    schema.add("uniqueKey", "id");
    schema.add("fieldTypes", List.of());
    schema.add("fields", List.of(field));
    schema.add("dynamicFields", List.of());
    schema.add("copyFields", List.of());
    schema.add("futureSection", Map.of("k", "v"));
    NamedList<Object> response = new NamedList<>();
    response.add("schema", schema);
    when(solrClient.request(any(SchemaRequest.class), eq("c"))).thenReturn(response);

    CollectionSchema result = new SchemaService(solrClient, jsonMapper).getSchema("c");

    CollectionSchema.Field genre = result.fields().getFirst();
    assertEquals("genre", genre.name());
    assertEquals(true, genre.indexed());
    assertEquals(false, genre.stored());
    assertNull(genre.docValues(), "an undeclared property stays absent");
    assertThat(new JsonContent(jsonMapper.writeValueAsString(result)))
        .isStrictlyEqualTo(
            """
            {"name": "test", "uniqueKey": "id", "fieldTypes": [],
             "fields": [{"name": "genre", "type": "string", "indexed": true, "stored": false}],
             "dynamicFields": [], "copyFields": []}
            """);
  }

  @ParameterizedTest
  @ValueSource(classes = {CollectionSchema.Field.class, CollectionSchema.FieldType.class})
  void schemaPropertiesMatchTheRecordsBooleanComponents(Class<? extends Record> recordType) {
    List<String> booleans =
        Arrays.stream(recordType.getRecordComponents())
            .filter(component -> component.getType() == Boolean.class)
            .map(RecordComponent::getName)
            .toList();

    assertEquals(CollectionSchema.SCHEMA_PROPERTIES, booleans);
  }

  @Test
  void getSchema_putsClassSettingsInParametersAndTypesTheAnalyzerChain() throws Exception {
    SimpleOrderedMap<Object> type = new SimpleOrderedMap<>();
    type.add("name", "text_general");
    type.add("class", "solr.TextField");
    type.add("positionIncrementGap", "100");
    type.add("multiValued", true);
    type.add("similarity", Map.of("class", "solr.BM25SimilarityFactory"));
    type.add(
        "analyzer",
        Map.of(
            "tokenizer",
            Map.of("name", "standard"),
            "filters",
            List.of(Map.of("name", "lowercase"), Map.of("name", "stop", "ignoreCase", "true"))));
    SimpleOrderedMap<Object> schema = new SimpleOrderedMap<>();
    schema.add("fieldTypes", List.of(type));
    NamedList<Object> response = new NamedList<>();
    response.add("schema", schema);
    when(solrClient.request(any(SchemaRequest.class), eq("c"))).thenReturn(response);

    CollectionSchema.FieldType result =
        new SchemaService(solrClient, jsonMapper).getSchema("c").fieldTypes().getFirst();

    assertEquals("solr.TextField", result.className());
    assertEquals(true, result.multiValued());
    assertEquals(
        Map.of("positionIncrementGap", "100"),
        result.parameters(),
        "multiValued is a declared property, and a nested similarity is outside the contract");
    CollectionSchema.Analyzer analyzer = result.analyzer();
    assertNotNull(analyzer);
    assertEquals(
        new CollectionSchema.AnalyzerComponent("standard", null, Map.of()), analyzer.tokenizer());
    assertEquals(
        List.of(
            new CollectionSchema.AnalyzerComponent("lowercase", null, Map.of()),
            new CollectionSchema.AnalyzerComponent("stop", null, Map.of("ignoreCase", "true"))),
        analyzer.filters());
  }

  @Test
  void testGetSchema_CollectionNotFound() throws Exception {
    // Given
    final String nonExistentCollection = "non_existent_collection";

    // When SolrClient throws an exception for non-existent collection
    when(solrClient.request(any(SchemaRequest.class), eq(nonExistentCollection)))
        .thenThrow(new SolrServerException("Collection not found: " + nonExistentCollection));

    // Then - translated, without SolrJ's text or a cause
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> schemaService.getSchema(nonExistentCollection));
    assertTrue(
        exception.getMessage().startsWith(SchemaService.GET_SCHEMA_FAILED), exception::getMessage);
    assertNull(exception.getCause());
  }

  @Test
  void testGetSchema_SolrServerException() throws Exception {
    // Given
    final String collectionName = "test_collection";

    // When SolrClient throws a SolrServerException
    when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
        .thenThrow(new SolrServerException("Solr server error"));

    // Then
    assertThrows(
        Exception.class,
        () -> {
          schemaService.getSchema(collectionName);
        });
  }

  @Test
  void testGetSchema_IOException() throws Exception {
    // Given
    final String collectionName = "test_collection";

    // When SolrClient throws an IOException
    when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
        .thenThrow(new IOException("Network connection error"));

    // Then
    assertThrows(
        Exception.class,
        () -> {
          schemaService.getSchema(collectionName);
        });
  }

  @Test
  void testGetSchemaResource_SolrFailure_DoesNotLeakCause() throws Exception {
    // Given
    final String collectionName = "test_collection";
    when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
        .thenThrow(
            new SolrServerException(
                "Server refused connection at: http://solr-internal.corp:8983/solr"));

    ReadResourceRequest request = new ReadResourceRequest("solr://" + collectionName + "/schema");

    // When
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> schemaService.getSchemaResource(request, collectionName));

    // Then the client gets the fixed message, never the Solr failure text
    assertEquals(SchemaService.SCHEMA_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
  }

  @Test
  void testGetSchema_WithEmptyCollection() {
    // Given an empty collection name
    // Then should throw an exception
    assertThrows(
        Exception.class,
        () -> {
          schemaService.getSchema("");
        });
  }

  @Test
  void testConstructor() {
    // Test that constructor properly initializes the service
    SchemaService service = new SchemaService(solrClient, jsonMapper);
    assertNotNull(service);
  }

  @Test
  void addFields_blankCollection_throws() {
    List<CollectionSchema.Field> fields = fields(Map.of("name", "x", "type", "string"));
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("", fields));
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("   ", fields));
  }

  @Test
  void addFields_emptyList_throws() {
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("col", List.of()));
  }

  @Test
  void addFields_happyPath_buildsMultiUpdateAndEchoesNames() throws Exception {
    List<CollectionSchema.Field> fields =
        fields(
            Map.of("name", "title", "type", "text_general", "stored", true, "indexed", true),
            Map.of(
                "name",
                "platform",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true));

    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = schemaService.addFields("col", fields);

    assertEquals(List.of("title", "platform"), result.addedNames());
    assertEquals("col", result.collection());

    ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
    verify(solrClient).request(captor.capture(), eq("col"));
    assertInstanceOf(MultiUpdate.class, captor.getValue());
  }

  @Test
  void addFields_defaultIsNamedDefaultInTheContractAndTheSchemaApiRequest() throws Exception {
    List<CollectionSchema.Field> fields =
        fields(Map.of("name", "status", "type", "string", "default", "active"));
    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    CollectionSchema.Field status = fields.getFirst();
    assertEquals("active", status.defaultValue());
    JsonContent json = new JsonContent(jsonMapper.writeValueAsString(status));
    assertThat(json).extractingPath("$.default").isEqualTo("active");
    assertThat(json).doesNotHavePath("$.defaultValue");

    schemaService.addFields("col", fields);

    JsonContent body = capturedBody();
    assertThat(body).extractingPath("$['add-field'].default").isEqualTo("active");
    assertThat(body).extractingPath("$..defaultValue").asArray().isEmpty();
  }

  @Test
  void addFields_solrThrows_isTranslated() throws Exception {
    when(solrClient.request(any(SolrRequest.class), eq("col")))
        .thenThrow(new SolrServerException("simulated"));

    List<CollectionSchema.Field> fields = fields(Map.of("name", "x", "type", "string"));
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> schemaService.addFields("col", fields));
    assertTrue(
        exception.getMessage().startsWith(SchemaService.ADD_FIELDS_FAILED), exception::getMessage);
  }

  @Test
  void addFieldTypes_blankCollection_throws() {
    List<CollectionSchema.FieldType> types =
        fieldTypes(Map.of("name", "x", "className", "solr.StrField"));
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFieldTypes("", types));
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFieldTypes("   ", types));
  }

  @Test
  void addFieldTypes_emptyList_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> schemaService.addFieldTypes("col", List.of()));
  }

  @Test
  void addFieldTypes_happyPathWithAnalyzer_sendsClassesAndChain() throws Exception {
    List<CollectionSchema.FieldType> types =
        fieldTypes(
            Map.of(
                "name",
                "text_lowercase",
                "className",
                "solr.TextField",
                "analyzer",
                Map.of(
                    "tokenizer",
                    Map.of("className", "solr.KeywordTokenizerFactory"),
                    "filters",
                    List.of(Map.of("className", "solr.LowerCaseFilterFactory")))));
    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = schemaService.addFieldTypes("col", types);

    assertEquals(List.of("text_lowercase"), result.addedNames());
    JsonContent body = capturedBody();
    assertThat(body).extractingPath(FIELD_TYPE + ".class").isEqualTo("solr.TextField");
    assertThat(body)
        .extractingPath(FIELD_TYPE + ".analyzer.tokenizer.class")
        .isEqualTo("solr.KeywordTokenizerFactory");
    assertThat(body)
        .extractingPath(FIELD_TYPE + ".analyzer.filters[*].class")
        .asArray()
        .containsExactly("solr.LowerCaseFilterFactory");
    // The contract's names must not reach Solr.
    assertThat(body).extractingPath("$..className").asArray().isEmpty();
  }

  @Test
  void addFieldTypes_separateAnalyzers_sendComponentParameters() throws Exception {
    List<CollectionSchema.FieldType> types =
        fieldTypes(
            Map.of(
                "name",
                "text_autocomplete",
                "className",
                "solr.TextField",
                "parameters",
                Map.of("positionIncrementGap", "100"),
                "indexAnalyzer",
                Map.of(
                    "tokenizer",
                    Map.of("className", "solr.KeywordTokenizerFactory"),
                    "filters",
                    List.of(
                        Map.of("className", "solr.LowerCaseFilterFactory"),
                        Map.of(
                            "className",
                            "solr.EdgeNGramFilterFactory",
                            "parameters",
                            Map.of("minGramSize", "2", "maxGramSize", "20")))),
                "queryAnalyzer",
                Map.of(
                    "tokenizer",
                    Map.of("className", "solr.KeywordTokenizerFactory"),
                    "filters",
                    List.of(Map.of("className", "solr.LowerCaseFilterFactory")))));
    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = schemaService.addFieldTypes("col", types);

    assertEquals(List.of("text_autocomplete"), result.addedNames());
    JsonContent body = capturedBody();
    assertThat(body).extractingPath(FIELD_TYPE + ".positionIncrementGap").isEqualTo("100");
    assertThat(body)
        .extractingPath(
            FIELD_TYPE + ".indexAnalyzer.filters[?(@.class == 'solr.EdgeNGramFilterFactory')]")
        .asArray()
        .singleElement()
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("minGramSize", "2")
        .containsEntry("maxGramSize", "20");
    assertThat(body)
        .extractingPath(FIELD_TYPE + ".queryAnalyzer.tokenizer.class")
        .isEqualTo("solr.KeywordTokenizerFactory");
    // Parameters are flattened into the object they belong to.
    assertThat(body).extractingPath("$..parameters").asArray().isEmpty();
  }

  @Test
  void addFieldTypes_denseVectorField_noAnalyzer() throws Exception {
    List<CollectionSchema.FieldType> types =
        fieldTypes(
            Map.of(
                "name",
                "openai_embedding",
                "className",
                "solr.DenseVectorField",
                "parameters",
                Map.of(
                    "vectorDimension",
                    "1536",
                    "similarityFunction",
                    "cosine",
                    "knnAlgorithm",
                    "hnsw")));
    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = schemaService.addFieldTypes("col", types);

    assertEquals(List.of("openai_embedding"), result.addedNames());
    JsonContent body = capturedBody();
    assertThat(body).extractingPath(FIELD_TYPE + ".class").isEqualTo("solr.DenseVectorField");
    assertThat(body).extractingPath(FIELD_TYPE + ".vectorDimension").isEqualTo("1536");
    assertThat(body).doesNotHavePath(FIELD_TYPE + ".analyzer");
  }

  @Test
  void addFieldTypes_solrThrows_isTranslated() throws Exception {
    when(solrClient.request(any(SolrRequest.class), eq("col")))
        .thenThrow(new SolrServerException("simulated"));

    List<CollectionSchema.FieldType> types =
        fieldTypes(Map.of("name", "x", "className", "solr.StrField"));
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> schemaService.addFieldTypes("col", types));
    assertTrue(
        exception.getMessage().startsWith(SchemaService.ADD_FIELD_TYPES_FAILED),
        exception::getMessage);
  }

  /**
   * The single-class analyzer form, {@code {"analyzer":{"className":"solr.WhitespaceAnalyzer"}}}.
   * SolrJ's {@code AnalyzerDefinition} has no setter for the analyzer's {@code class}, so it must
   * go out through {@code setAttributes}, or Solr gets a malformed analyzer.
   */
  @Test
  void addFieldTypes_analyzerWithOnlyClass_sendsTheClass() throws Exception {
    List<CollectionSchema.FieldType> types =
        fieldTypes(
            Map.of(
                "name",
                "text_whitespace",
                "className",
                "solr.TextField",
                "analyzer",
                Map.of("className", "solr.WhitespaceAnalyzer")));
    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    schemaService.addFieldTypes("col", types);

    JsonContent body = capturedBody();
    assertThat(body).extractingPath(FIELD_TYPE + ".class").isEqualTo("solr.TextField");
    assertThat(body)
        .extractingPath(FIELD_TYPE + ".analyzer.class")
        .isEqualTo("solr.WhitespaceAnalyzer");
  }

  @Test
  void designSchemaPrompt_includesKeyWorkflowSteps() {
    String body =
        promptText(
            schemaService.designSchemaPrompt(
                "products", "A catalog of products with title and price", null));

    assertNotNull(body);
    assertTrue(body.contains("products"), "Prompt should mention the target collection name");
    assertTrue(body.contains("get-schema"), "Prompt should reference get-schema tool");
    assertTrue(body.contains("add-fields"), "Prompt should reference add-fields tool");
    assertTrue(body.contains("add-field-types"), "Prompt should reference add-field-types tool");
    assertTrue(body.contains("text_general"), "Prompt should mention common Solr field types");
    assertTrue(body.contains("transactional"), "Prompt should warn about Schema API atomicity");
  }

  @Test
  void designSchemaPrompt_embedsSampleDocumentWhenProvided() {
    String sample =
        """
				{"id":"sku-1","title":"Widget","price":9.99}""";

    String body = promptText(schemaService.designSchemaPrompt("products", "Catalog", sample));

    assertTrue(body.contains(sample), "Prompt should include the sample document body");
  }

  @Test
  void viewSchemaPrompt_isReadOnlyAndReferencesGetSchema() {
    String body = promptText(schemaService.viewSchemaPrompt("products"));

    assertNotNull(body);
    assertTrue(body.contains("products"), "Prompt should mention the target collection name");
    assertTrue(body.contains("get-schema"), "Prompt should reference get-schema tool");
    assertTrue(body.contains("uniqueKey"), "Prompt should explain uniqueKey");
    assertTrue(body.contains("dynamic"), "Prompt should explain dynamic fields");
    assertTrue(
        body.contains("copyField") || body.contains("copy field") || body.contains("copyFields"),
        "Prompt should explain copy fields");
    assertFalse(
        body.contains("add-fields"),
        "View prompt is read-only; should not direct the LLM to add-fields");
    assertTrue(
        body.contains("design-schema"),
        "View prompt should cross-reference design-schema for follow-up modification");
  }

  @Test
  void viewSchemaPrompt_saysUncountedFieldsAreNotEmpty() {
    String body = promptText(schemaService.viewSchemaPrompt("products"));

    assertTrue(body.contains("cannot count them, not because they are empty"), body);
  }

  // ===== list-fields =====

  private static final String COLLECTION = "shows";

  @Test
  void listFields_decodesFlagsAndLeavesOutTheInfoAndIndexSections() throws Exception {
    NamedList<Object> genre = new SimpleOrderedMap<>();
    genre.add("type", "string");
    genre.add("schema", "I-SD-M----OF-----l");
    genre.add("dynamicBase", "*_s");
    genre.add("docs", 5);
    NamedList<Object> fields = new SimpleOrderedMap<>();
    fields.add("genre_s", genre);
    NamedList<Object> key = new SimpleOrderedMap<>();
    key.add("I", "Indexed");
    NamedList<Object> info = new SimpleOrderedMap<>();
    info.add("key", key);
    NamedList<Object> index = new SimpleOrderedMap<>();
    index.add("directory", "MMapDirectory@/var/solr/data/shows/index");
    NamedList<Object> response = new NamedList<>();
    response.add("index", index);
    response.add("fields", fields);
    response.add("info", info);
    when(solrClient.request(any(LukeRequest.class), eq(COLLECTION))).thenReturn(response);
    List<IndexedField> listing = new SchemaService(solrClient, jsonMapper).listFields(COLLECTION);

    IndexedField field = listing.getFirst();
    assertEquals("genre_s", field.name());
    assertEquals("string", field.type());
    assertEquals(
        Set.of(
            FieldProperty.indexed,
            FieldProperty.stored,
            FieldProperty.docValues,
            FieldProperty.multiValued,
            FieldProperty.omitNorms,
            FieldProperty.omitTermFreqAndPositions,
            FieldProperty.sortMissingLast),
        field.flags());
    assertEquals("*_s", field.dynamicBase());
    assertEquals(5L, field.docs());
    assertThat(new JsonContent(jsonMapper.writeValueAsString(listing)))
        .isStrictlyEqualTo(
            """
            [{"name": "genre_s", "type": "string",
              "flags": ["indexed", "stored", "docValues", "multiValued", "omitNorms",
                        "omitTermFreqAndPositions", "sortMissingLast"],
              "dynamicBase": "*_s", "docs": 5}]
            """);
  }

  @Test
  void listFields_isOrderedByFieldName() throws Exception {
    NamedList<Object> fields = new SimpleOrderedMap<>();
    fields.add("zeta_s", new SimpleOrderedMap<>());
    fields.add("alpha_s", new SimpleOrderedMap<>());
    NamedList<Object> response = new NamedList<>();
    response.add("fields", fields);
    when(solrClient.request(any(LukeRequest.class), eq(COLLECTION))).thenReturn(response);

    assertEquals(
        List.of("alpha_s", "zeta_s"),
        schemaService.listFields(COLLECTION).stream().map(IndexedField::name).toList());
  }

  @Test
  void listFields_keepsADistributedDocCountPastIntegerMax() throws Exception {
    // SolrJ's LukeResponse casts docs to Integer; a sum across shards can exceed it.
    NamedList<Object> title = new SimpleOrderedMap<>();
    title.add("type", "text_general");
    title.add("schema", "ITS---------------");
    title.add("docs", 3_000_000_000L);
    NamedList<Object> fields = new SimpleOrderedMap<>();
    fields.add("title", title);
    NamedList<Object> response = new NamedList<>();
    response.add("fields", fields);
    when(solrClient.request(any(LukeRequest.class), eq(COLLECTION))).thenReturn(response);

    List<IndexedField> listing = schemaService.listFields(COLLECTION);

    assertEquals(3_000_000_000L, listing.getFirst().docs());
  }

  @Test
  void listFields_unknownFlagLetterIsSkippedAndTheKnownFlagsKept() throws Exception {
    // A letter a later Solr version adds must not throw or hide the flags SolrJ knows, and Luke's
    // letter encoding is not part of the contract.
    NamedList<Object> future = new SimpleOrderedMap<>();
    future.add("type", "string");
    future.add("schema", "I-S-----------Z");
    NamedList<Object> fields = new SimpleOrderedMap<>();
    fields.add("future_s", future);
    NamedList<Object> response = new NamedList<>();
    response.add("fields", fields);
    when(solrClient.request(any(LukeRequest.class), eq(COLLECTION))).thenReturn(response);

    IndexedField field = schemaService.listFields(COLLECTION).getFirst();

    assertEquals(Set.of(FieldProperty.indexed, FieldProperty.stored), field.flags());
    // Neither Luke's letter string nor the unknown letter reaches the contract.
    assertThat(new JsonContent(jsonMapper.writeValueAsString(field)))
        .isStrictlyEqualTo(
            """
            {"name": "future_s", "type": "string", "flags": ["indexed", "stored"]}
            """);
  }

  @Test
  void listFields_asksLukeForNoTopTermsAndNoIndexFlags() throws Exception {
    ArgumentCaptor<LukeRequest> request = ArgumentCaptor.forClass(LukeRequest.class);
    when(solrClient.request(request.capture(), eq(COLLECTION))).thenReturn(new NamedList<>());

    schemaService.listFields(COLLECTION);

    SolrParams params = request.getValue().getParams();
    assertEquals("0", params.get("numTerms"));
    assertEquals("false", params.get("includeIndexFieldFlags"));
  }

  @Test
  void listFields_rejectsBlankCollection() {
    assertThrows(IllegalArgumentException.class, () -> schemaService.listFields(" "));
  }

  /** Fields as an MCP client sends them: JSON objects in the shape get-schema reports. */
  @SafeVarargs
  private List<CollectionSchema.Field> fields(Map<String, Object>... fields) {
    return jsonMapper.convertValue(List.of(fields), new TypeReference<>() {});
  }

  /** Field types as an MCP client sends them: JSON objects in the shape get-schema reports. */
  @SafeVarargs
  private List<CollectionSchema.FieldType> fieldTypes(Map<String, Object>... types) {
    return jsonMapper.convertValue(List.of(types), new TypeReference<>() {});
  }

  /** The JSON body of the single Schema API request the service sent. */
  /** The Schema API request body sent to Solr, as JSON. */
  private JsonContent capturedBody() throws Exception {
    ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
    verify(solrClient).request(captor.capture(), eq("col"));
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    captor.getValue().getContentWriter("application/json").write(body);
    return new JsonContent(body.toString(StandardCharsets.UTF_8));
  }
}
