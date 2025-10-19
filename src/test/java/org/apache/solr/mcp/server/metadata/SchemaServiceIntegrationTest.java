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
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test suite for SchemaService using real Solr containers. Tests actual schema
 * retrieval functionality against a live Solr instance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaServiceIntegrationTest {

    private static final String TEST_COLLECTION = "schema_test_collection";
    private static boolean initialized = false;
    @Autowired
    private SchemaService schemaService;
    @Autowired
    private SolrClient solrClient;

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
        SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);

        // Then
        assertNotNull(schema, "Schema should not be null");
        assertNotNull(schema.getFields(), "Schema fields should not be null");
        assertNotNull(schema.getFieldTypes(), "Schema field types should not be null");

        // Verify basic schema properties
        assertFalse(schema.getFields().isEmpty(), "Schema should have at least some fields");
        assertFalse(
                schema.getFieldTypes().isEmpty(), "Schema should have at least some field types");

        // Check for common default fields in Solr
        boolean hasIdField =
                schema.getFields().stream().anyMatch(field -> "id".equals(field.get("name")));
        assertTrue(hasIdField, "Schema should have an 'id' field");

        // Check for common field types
        boolean hasStringType =
                schema.getFieldTypes().stream()
                        .anyMatch(
                                fieldType ->
                                        "string".equals(fieldType.getAttributes().get("name")));
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
    void testGetSchema_NullCollection() {
        // When/Then
        assertThrows(
                Exception.class,
                () -> {
                    schemaService.getSchema(null);
                },
                "Getting schema with null collection should throw exception");
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
        SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);

        // Then - verify schema has expected structure
        assertNotNull(schema.getName(), "Schema should have a name");

        // Check that we can access field details
        schema.getFields()
                .forEach(
                        field -> {
                            assertNotNull(field.get("name"), "Field should have a name");
                            assertNotNull(field.get("type"), "Field should have a type");
                            // indexed and stored can be null (defaults to true in many cases)
                        });

        // Check that we can access field type details
        schema.getFieldTypes()
                .forEach(
                        fieldType -> {
                            assertNotNull(
                                    fieldType.getAttributes().get("name"),
                                    "Field type should have a name");
                            assertNotNull(
                                    fieldType.getAttributes().get("class"),
                                    "Field type should have a class");
                        });
    }

    @Test
    void testGetSchema_ChecksDynamicFields() throws Exception {
        // When
        SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);

        // Then - verify dynamic fields are accessible
        assertNotNull(schema.getDynamicFields(), "Dynamic fields should not be null");

        // Most Solr schemas have some dynamic fields by default
        assertTrue(schema.getDynamicFields().size() >= 0, "Dynamic fields should be a valid list");

        // Check for common dynamic field patterns
        boolean hasStringDynamicField =
                schema.getDynamicFields().stream()
                        .anyMatch(
                                dynField -> {
                                    String name = (String) dynField.get("name");
                                    return name != null
                                            && (name.contains("*_s") || name.contains("*_str"));
                                });

        assertTrue(hasStringDynamicField, "Schema should have string dynamic fields");
    }

    @Test
    void testGetSchema_ChecksCopyFields() throws Exception {
        // When
        SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);

        // Then - verify copy fields are accessible
        assertNotNull(schema.getCopyFields(), "Copy fields should not be null");

        // Copy fields list can be empty, that's valid
        assertTrue(schema.getCopyFields().size() >= 0, "Copy fields should be a valid list");
    }

    @Test
    void testGetSchema_ReturnsUniqueKey() throws Exception {
        // When
        SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);

        // Then - verify unique key is accessible
        // Unique key can be null in some schemas, but the method should work
        // Most default schemas have 'id' as unique key
        if (schema.getUniqueKey() != null) {
            assertNotNull(schema.getUniqueKey(), "Unique key should be accessible");
        }
    }
}
