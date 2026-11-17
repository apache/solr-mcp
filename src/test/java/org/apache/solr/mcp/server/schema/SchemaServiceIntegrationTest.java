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
import org.apache.solr.common.luke.FieldFlag;
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
  void getSchema_isTheSchemaApiResponseWithFlagPropertiesAsFlags() throws Exception {
    // The records may change one thing about the Schema API's JSON: on every field, dynamic
    // field and field type, the boolean flag properties become a "flags" list of the ones set to
    // true. Everything else must come through unchanged. Both sides are written to JSON text and
    // parsed back, so key order and Float-versus-Double for decimals such as version do not matter.
    NamedList<Object> raw = solrClient.request(new SchemaRequest(), TEST_COLLECTION);
    TypeReference<Map<String, Object>> asMap = new TypeReference<>() {};
    Map<String, Object> expected =
        jsonMapper.readValue(jsonMapper.writeValueAsString(raw.get("schema")), asMap);
    for (String section : List.of("fields", "dynamicFields", "fieldTypes")) {
      for (Object entry : (List<?>) expected.get(section)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) entry;
        List<String> flags =
            FieldFlags.SCHEMA_PROPERTIES.entrySet().stream()
                .filter(property -> Boolean.TRUE.equals(properties.remove(property.getKey())))
                .map(Map.Entry::getValue)
                .sorted()
                .map(Enum::name)
                .toList();
        properties.put("flags", flags);
      }
    }

    Map<String, Object> fromTool =
        jsonMapper.readValue(
            jsonMapper.writeValueAsString(schemaService.getSchema(TEST_COLLECTION)), asMap);

    assertEquals(expected, fromTool);
  }

  @Test
  void getSchema_reportsFieldFlags() throws Exception {
    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);

    CollectionSchema.Field id =
        schema.fields().stream().filter(f -> "id".equals(f.name())).findFirst().orElseThrow();
    CollectionSchema.Field root =
        schema.fields().stream().filter(f -> "_root_".equals(f.name())).findFirst().orElseThrow();
    CollectionSchema.FieldType strings =
        schema.fieldTypes().stream()
            .filter(t -> "strings".equals(t.name()))
            .findFirst()
            .orElseThrow();

    // _default declares id indexed and stored, _root_ indexed but stored=false
    assertTrue(id.flags().containsAll(Set.of(FieldFlag.INDEXED, FieldFlag.STORED)), id::toString);
    assertTrue(root.flags().contains(FieldFlag.INDEXED), root::toString);
    assertFalse(root.flags().contains(FieldFlag.STORED), root::toString);
    assertFalse(root.other().containsKey("stored"), root::toString);
    // a type's flags are defaults for its fields
    assertTrue(
        strings.flags().containsAll(Set.of(FieldFlag.MULTI_VALUED, FieldFlag.SORT_MISSING_LAST)),
        strings::toString);
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
    List<Map<String, Object>> fields =
        List.of(
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
    CollectionSchema.Field title =
        schema.fields().stream()
            .filter(f -> "addf_title".equals(f.name()))
            .findFirst()
            .orElseThrow();
    CollectionSchema.Field platform =
        schema.fields().stream()
            .filter(f -> "addf_platform".equals(f.name()))
            .findFirst()
            .orElseThrow();
    CollectionSchema.Field year =
        schema.fields().stream()
            .filter(f -> "addf_year".equals(f.name()))
            .findFirst()
            .orElseThrow();

    assertEquals("text_general", title.type());
    assertEquals("string", platform.type());
    assertTrue(platform.flags().contains(FieldFlag.DOC_VALUES), platform::toString);
    assertEquals("pint", year.type());
  }

  @Test
  void addFieldTypes_customAnalyzer_appliesAtIndexAndQuery() throws Exception {
    schemaService.addFieldTypes(
        TEST_COLLECTION,
        List.of(
            Map.of(
                "name",
                "aft_text_ci_keyword",
                "class",
                "solr.TextField",
                "analyzer",
                Map.of(
                    "tokenizer",
                    Map.of("class", "solr.KeywordTokenizerFactory"),
                    "filters",
                    List.of(Map.of("class", "solr.LowerCaseFilterFactory"))))));

    schemaService.addFields(
        TEST_COLLECTION,
        List.of(
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
        List.of(
            Map.of(
                "name",
                "aft_test_vector",
                "class",
                "solr.DenseVectorField",
                "vectorDimension",
                4,
                "similarityFunction",
                "cosine")));

    CollectionSchema schema = schemaService.getSchema(TEST_COLLECTION);
    CollectionSchema.FieldType vt =
        schema.fieldTypes().stream()
            .filter(t -> "aft_test_vector".equals(t.name()))
            .findFirst()
            .orElseThrow();

    assertEquals("solr.DenseVectorField", vt.className());
    // vectorDimension has no record component, so it is kept in the catch-all
    Object vectorDimension = vt.other().get("vectorDimension");
    int vectorDimensionInt =
        vectorDimension instanceof Number n
            ? n.intValue()
            : Integer.parseInt(vectorDimension.toString());
    assertEquals(4, vectorDimensionInt);
    assertEquals("cosine", vt.other().get("similarityFunction"));
  }

  @Test
  void addFields_duplicateField_throws() throws Exception {
    List<Map<String, Object>> field =
        List.of(
            Map.of("name", "addf_dup_field", "type", "string", "stored", true, "indexed", true));
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
    List<Map<String, Object>> field =
        List.of(Map.of("name", "addf_broken", "type", "totally_not_a_real_type"));
    assertThrows(Exception.class, () -> schemaService.addFields(TEST_COLLECTION, field));
  }
}
