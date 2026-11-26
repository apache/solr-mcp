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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test suite for SchemaService using real Solr containers. Tests actual schema
 * retrieval functionality against a live Solr instance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SchemaServiceIntegrationTest {

  @Autowired private SchemaService schemaService;

  @Autowired private SolrClient solrClient;

  @Autowired private JsonMapper jsonMapper;

  private static final String TEST_COLLECTION = "schema_test_collection";

  /**
   * A collection with its own copy of {@code _default}. TEST_COLLECTION shares {@code _default}
   * with other test classes, so a field default added there would fill their documents too.
   */
  private static final String OWN_CONFIGSET_COLLECTION = "schema_own_configset_collection";

  private static boolean initialized = false;

  @BeforeEach
  void setupCollection() throws Exception {
    // Create a collection for testing
    if (!initialized) {
      CollectionAdminRequest.Create createRequest =
          CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1);
      createRequest.process(solrClient);
      initialized = true;
    }
  }

  @Test
  void testGetSchema_ValidCollection() throws Exception {
    // When
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    // Then
    assertNotNull(schema, "Schema should not be null");
    assertNotNull(schema.fields(), "Schema fields should not be null");
    assertNotNull(schema.fieldTypes(), "Schema field types should not be null");

    // Verify basic schema properties
    assertFalse(schema.fields().isEmpty(), "Schema should have at least some fields");
    assertFalse(schema.fieldTypes().isEmpty(), "Schema should have at least some field types");

    // Check for common default fields in Solr
    boolean hasIdField = schema.fields().stream().anyMatch(field -> "id".equals(field.name()));
    assertTrue(hasIdField, "Schema should have an 'id' field");

    // Check for common field types
    boolean hasStringType =
        schema.fieldTypes().stream().anyMatch(fieldType -> "string".equals(fieldType.name()));
    assertTrue(hasStringType, "Schema should have a 'string' field type");
  }

  @Test
  void testGetSchema_InvalidCollection() {
    // When/Then
    assertThrows(
        Exception.class,
        () -> {
          schemaService.getSchema("non_existent_collection_12345");
        },
        "Getting schema for non-existent collection should throw exception");
  }

  @Test
  void testGetSchema_EmptyCollection() {
    // When/Then
    assertThrows(
        Exception.class,
        () -> {
          schemaService.getSchema("");
        },
        "Getting schema with empty collection should throw exception");
  }

  @Test
  void testGetSchema_ValidatesSchemaContent() throws Exception {
    // When
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    // Then - verify schema has expected structure
    assertNotNull(schema.name(), "Schema should have a name");

    // Check that we can access field details
    schema
        .fields()
        .forEach(
            field -> {
              assertNotNull(field.name(), "Field should have a name");
              assertNotNull(field.type(), "Field should have a type");
              // indexed and stored can be null (defaults to true in many cases)
            });

    // Check that we can access field type details
    schema
        .fieldTypes()
        .forEach(
            fieldType -> {
              assertNotNull(fieldType.name(), "Field type should have a name");
              assertNotNull(fieldType.className(), "Field type should have a class");
            });
  }

  @Test
  void testGetSchema_ChecksDynamicFields() throws Exception {
    // When
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    // Then - verify dynamic fields are accessible
    assertNotNull(schema.dynamicFields(), "Dynamic fields should not be null");

    // The _default configset defines dynamic fields such as *_s and *_i
    assertFalse(schema.dynamicFields().isEmpty(), "Dynamic fields should not be empty");

    // Check for common dynamic field patterns
    boolean hasStringDynamicField =
        schema.dynamicFields().stream()
            .anyMatch(
                dynField -> {
                  String name = dynField.name();
                  return name.contains("*_s") || name.contains("*_str");
                });

    assertTrue(hasStringDynamicField, "Schema should have string dynamic fields");
  }

  @Test
  void testGetSchema_ChecksCopyFields() throws Exception {
    // When
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    // Then - verify copy fields are accessible
    assertNotNull(schema.copyFields(), "Copy fields should not be null");
  }

  @Test
  void getSchema_reportsEveryDeclaredPropertyOfTheSchemaApiResponse() throws Exception {
    // For every field, dynamic field and field type, each schema property Solr declares must come
    // through with the same value, and an undeclared one must stay absent. A field keeps its type,
    // a field type its class as className and every other scalar attribute in parameters. Other
    // attributes outside the contract are dropped on purpose and not compared.
    NamedList<Object> raw = solrClient.request(new SchemaRequest(), TEST_COLLECTION);
    TypeReference<Map<String, Object>> asMap = new TypeReference<>() {};
    Map<String, Object> solr =
        jsonMapper.readValue(jsonMapper.writeValueAsString(raw.get("schema")), asMap);
    Map<String, Object> tool =
        jsonMapper.readValue(
            jsonMapper.writeValueAsString(schemaService.getSchema(TEST_COLLECTION)), asMap);

    for (String section : List.of("fields", "dynamicFields", "fieldTypes")) {
      List<?> fromSolr = (List<?>) solr.get(section);
      List<?> fromTool = (List<?>) tool.get(section);
      assertEquals(fromSolr.size(), fromTool.size(), section);
      for (int i = 0; i < fromSolr.size(); i++) {
        Map<?, ?> expected = (Map<?, ?>) fromSolr.get(i);
        Map<?, ?> actual = (Map<?, ?>) fromTool.get(i);
        String where = section + " " + expected.get("name");
        assertEquals(expected.get("name"), actual.get("name"), section);
        for (String property : CollectionSchema.SCHEMA_PROPERTIES) {
          assertEquals(expected.get(property), actual.get(property), where + "." + property);
        }
        if ("fieldTypes".equals(section)) {
          assertEquals(expected.get("class"), actual.get("className"), where);
          assertScalarAttributesAreParameters(expected, actual, where);
        } else {
          assertEquals(expected.get("type"), actual.get("type"), where);
        }
      }
    }
    assertEquals(solr.get("uniqueKey"), tool.get("uniqueKey"));
  }

  /** Each scalar attribute of a Solr field type that the contract does not name is a parameter. */
  private static void assertScalarAttributesAreParameters(
      Map<?, ?> solrType, Map<?, ?> toolType, String where) {
    Set<String> named = Set.of("name", "class");
    Map<?, ?> parameters = (Map<?, ?>) toolType.get("parameters");
    for (Map.Entry<?, ?> attribute : solrType.entrySet()) {
      Object key = attribute.getKey();
      Object value = attribute.getValue();
      boolean scalar =
          value instanceof String || value instanceof Number || value instanceof Boolean;
      if (scalar && !named.contains(key) && !CollectionSchema.SCHEMA_PROPERTIES.contains(key)) {
        assertEquals(String.valueOf(value), parameters.get(key), where + "." + key);
      }
    }
  }

  @Test
  void getSchema_keepsDeclaredFalse() throws Exception {
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    CollectionSchema.Field id = field(schema, "id");
    CollectionSchema.Field root = field(schema, "_root_");
    CollectionSchema.FieldType strings = fieldType(schema, "strings");

    // _default declares id indexed and stored, _root_ indexed but stored=false
    assertEquals(true, id.indexed(), id::toString);
    assertEquals(true, id.stored(), id::toString);
    assertEquals(true, root.indexed(), root::toString);
    assertEquals(false, root.stored(), "a declared false is kept: " + root);
    // a type's properties are defaults for its fields
    assertEquals(true, strings.multiValued(), strings::toString);
    assertEquals(true, strings.sortMissingLast(), strings::toString);
  }

  @Test
  void schemaObjectsRoundTripThroughTheAddTools() throws Exception {
    // A field type and a field taken from get-schema, renamed, and passed back to the add tools
    // must come out of get-schema again unchanged apart from the name.
    CollectionSchema before = schemaService.getSchema(TEST_COLLECTION);
    CollectionSchema.FieldType textGeneral = fieldType(before, "text_general");
    // _root_ declares stored=false, so the copy also checks that a declared false survives. It is
    // not required, which matters: test collections can share a configset, and a required field
    // added here would reject other tests' documents.
    CollectionSchema.Field root = field(before, "_root_");

    schemaService.addFieldTypes(TEST_COLLECTION, List.of(renamed(textGeneral, "rt_text_general")));
    schemaService.addFields(TEST_COLLECTION, List.of(renamed(root, "rt_root")));

    CollectionSchema after = schemaService.getSchema(TEST_COLLECTION);
    assertEquals(renamed(textGeneral, "rt_text_general"), fieldType(after, "rt_text_general"));
    assertEquals(renamed(root, "rt_root"), field(after, "rt_root"));
    assertEquals(false, field(after, "rt_root").stored());
  }

  @Test
  void testGetSchema_ReturnsUniqueKey() throws Exception {
    // When
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    // Then - verify unique key is accessible
    // Unique key can be null in some schemas, but the method should work
    // Most default schemas have 'id' as unique key
    if (schema.uniqueKey() != null) {
      assertNotNull(schema.uniqueKey(), "Unique key should be accessible");
    }
  }

  @Test
  void addFields_endToEnd_persistsToSchema() throws Exception {
    List<CollectionSchema.Field> fields =
        fields(
            Map.of("name", "addf_title", "type", "text_general", "stored", true, "indexed", true),
            Map.of(
                "name",
                "addf_platform",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "addf_year",
                "type",
                "pint",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true));

    SchemaUpdateResult result = schemaService.addFields(TEST_COLLECTION, fields);

    assertEquals(List.of("addf_title", "addf_platform", "addf_year"), result.addedNames());

    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);
    CollectionSchema.Field title = field(schema, "addf_title");
    CollectionSchema.Field platform = field(schema, "addf_platform");
    CollectionSchema.Field year = field(schema, "addf_year");

    assertEquals("text_general", title.type());
    assertEquals("string", platform.type());
    assertEquals(true, platform.docValues(), platform::toString);
    assertEquals("pint", year.type());
  }

  @Test
  void addFields_declaresOnlyThePropertiesTheClientSent() throws Exception {
    SchemaUpdateResult result =
        schemaService.addFields(
            TEST_COLLECTION,
            fields(Map.of("name", "year_contract", "type", "pint", "docValues", true)));

    assertEquals(new SchemaUpdateResult(TEST_COLLECTION, List.of("year_contract")), result);
    CollectionSchema.Field year = field(schemaService.getSchema(TEST_COLLECTION), "year_contract");
    assertEquals(true, year.docValues(), year::toString);
    assertNull(year.indexed(), "indexed is inherited from pint, not declared: " + year);
    assertNull(year.stored(), "stored is inherited from pint, not declared: " + year);
    assertEquals(
        "{\"name\":\"year_contract\",\"type\":\"pint\",\"docValues\":true}",
        jsonMapper.writeValueAsString(year));
  }

  @Test
  void addFields_defaultReadsBackFromGetSchema() throws Exception {
    CollectionAdminRequest.createCollection(OWN_CONFIGSET_COLLECTION, 1, 1).process(solrClient);

    schemaService.addFields(
        OWN_CONFIGSET_COLLECTION,
        fields(Map.of("name", "status_contract", "type", "string", "default", "active")));

    CollectionSchema.Field status =
        field(schemaService.getSchema(OWN_CONFIGSET_COLLECTION), "status_contract");
    assertEquals("active", status.defaultValue(), status::toString);
  }

  @Test
  void addFieldTypes_customAnalyzer_appliesAtIndexAndQuery() throws Exception {
    schemaService.addFieldTypes(
        TEST_COLLECTION,
        fieldTypes(
            Map.of(
                "name",
                "aft_text_ci_keyword",
                "className",
                "solr.TextField",
                "analyzer",
                Map.of(
                    "tokenizer",
                    Map.of("className", "solr.KeywordTokenizerFactory"),
                    "filters",
                    List.of(Map.of("className", "solr.LowerCaseFilterFactory"))))));

    schemaService.addFields(
        TEST_COLLECTION,
        fields(
            Map.of(
                "name",
                "aft_ci_platform",
                "type",
                "aft_text_ci_keyword",
                "stored",
                true,
                "indexed",
                true)));

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", "aft-doc-1");
    doc.addField("aft_ci_platform", "NetFlix");
    solrClient.add(TEST_COLLECTION, doc);
    solrClient.commit(TEST_COLLECTION);

    QueryResponse lowercase =
        solrClient.query(TEST_COLLECTION, new SolrQuery("aft_ci_platform:netflix"));
    assertEquals(1L, lowercase.getResults().getNumFound());

    QueryResponse uppercase =
        solrClient.query(TEST_COLLECTION, new SolrQuery("aft_ci_platform:NETFLIX"));
    assertEquals(1L, uppercase.getResults().getNumFound());

    QueryResponse partial = solrClient.query(TEST_COLLECTION, new SolrQuery("aft_ci_platform:net"));
    assertEquals(0L, partial.getResults().getNumFound());
  }

  @Test
  void addFieldTypes_denseVectorField_schemaRoundTrip() throws Exception {
    schemaService.addFieldTypes(
        TEST_COLLECTION,
        fieldTypes(
            Map.of(
                "name",
                "aft_test_vector",
                "className",
                "solr.DenseVectorField",
                "parameters",
                Map.of("vectorDimension", "4", "similarityFunction", "cosine"))));

    CollectionSchema.FieldType vt =
        fieldType(schemaService.getSchema(TEST_COLLECTION), "aft_test_vector");

    assertEquals("solr.DenseVectorField", vt.className());
    assertEquals("4", vt.parameters().get("vectorDimension"));
    assertEquals("cosine", vt.parameters().get("similarityFunction"));
  }

  @Test
  void addFields_duplicateField_throws() throws Exception {
    List<CollectionSchema.Field> field =
        fields(Map.of("name", "addf_dup_field", "type", "string", "stored", true, "indexed", true));
    schemaService.addFields(TEST_COLLECTION, field);

    // Second call with same name — Solr returns an error in the response body.
    // Whether SolrJ throws or returns silently is part of what this test verifies.
    Exception ex =
        assertThrows(Exception.class, () -> schemaService.addFields(TEST_COLLECTION, field));
    assertTrue(
        ex instanceof SolrServerException || ex instanceof RuntimeException,
        "Expected SolrServerException or RuntimeException, got " + ex.getClass());
  }

  @Test
  void addFields_unknownType_throws() {
    List<CollectionSchema.Field> field =
        fields(Map.of("name", "addf_broken", "type", "totally_not_a_real_type"));
    assertThrows(Exception.class, () -> schemaService.addFields(TEST_COLLECTION, field));
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

  private static CollectionSchema.Field field(CollectionSchema schema, String name) {
    return schema.fields().stream()
        .filter(field -> name.equals(field.name()))
        .findFirst()
        .orElseThrow();
  }

  private static CollectionSchema.FieldType fieldType(CollectionSchema schema, String name) {
    return schema.fieldTypes().stream()
        .filter(fieldType -> name.equals(fieldType.name()))
        .findFirst()
        .orElseThrow();
  }

  /** A copy of {@code value} under another name, made the way a client would: through JSON. */
  private <T> T renamed(T value, String name) {
    Map<String, Object> json = jsonMapper.convertValue(value, new TypeReference<>() {});
    json.put("name", name);
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) value.getClass();
    return jsonMapper.convertValue(json, type);
  }
}
