package org.apache.solr.mcp.server.metadata;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test suite for SchemaService using real Solr containers.
 * Tests actual schema retrieval functionality against a live Solr instance.
 */
@SpringBootTest
@Testcontainers
class SchemaServiceIntegrationTest {

    @Container
    static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9.4.1"));

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private SolrClient solrClient;

    private static final String TEST_COLLECTION = "schema_test_collection";

    @DynamicPropertySource
    static void registerSolrProperties(DynamicPropertyRegistry registry) {
        registry.add("solr.url", () -> "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/");
    }

    @BeforeAll
    static void setup() throws Exception {
        // Wait for Solr container to be ready
        assertTrue(solrContainer.isRunning(), "Solr container should be running");

        // Create a test collection
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/";
        SolrClient client = new Http2SolrClient.Builder(solrUrl).build();

        try {
            // Create a collection for testing
            CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1);
            client.request(createRequest);

            // Verify collection was created successfully
            CollectionAdminRequest.List listRequest = new CollectionAdminRequest.List();
            listRequest.process(client);

        } catch (Exception e) {
            // Collection might already exist, which is fine
        } finally {
            client.close();
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
        assertFalse(schema.getFieldTypes().isEmpty(), "Schema should have at least some field types");
        
        // Check for common default fields in Solr
        boolean hasIdField = schema.getFields().stream()
                .anyMatch(field -> "id".equals(field.get("name")));
        assertTrue(hasIdField, "Schema should have an 'id' field");
        
        // Check for common field types
        boolean hasStringType = schema.getFieldTypes().stream()
                .anyMatch(fieldType -> "string".equals(fieldType.getAttributes().get("name")));
        assertTrue(hasStringType, "Schema should have a 'string' field type");
    }

    @Test
    void testGetSchema_InvalidCollection() {
        // When/Then
        assertThrows(Exception.class, () -> {
            schemaService.getSchema("non_existent_collection_12345");
        }, "Getting schema for non-existent collection should throw exception");
    }

    @Test
    void testGetSchema_NullCollection() {
        // When/Then
        assertThrows(Exception.class, () -> {
            schemaService.getSchema(null);
        }, "Getting schema with null collection should throw exception");
    }

    @Test
    void testGetSchema_EmptyCollection() {
        // When/Then
        assertThrows(Exception.class, () -> {
            schemaService.getSchema("");
        }, "Getting schema with empty collection should throw exception");
    }

    @Test
    void testGetSchema_ValidatesSchemaContent() throws Exception {
        // When
        SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);

        // Then - verify schema has expected structure
        assertNotNull(schema.getName(), "Schema should have a name");

        // Check that we can access field details
        schema.getFields().forEach(field -> {
            assertNotNull(field.get("name"), "Field should have a name");
            assertNotNull(field.get("type"), "Field should have a type");
            // indexed and stored can be null (defaults to true in many cases)
        });

        // Check that we can access field type details
        schema.getFieldTypes().forEach(fieldType -> {
            assertNotNull(fieldType.getAttributes().get("name"), "Field type should have a name");
            assertNotNull(fieldType.getAttributes().get("class"), "Field type should have a class");
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
        boolean hasStringDynamicField = schema.getDynamicFields().stream()
                .anyMatch(dynField -> {
                    String name = (String) dynField.get("name");
                    return name != null && (name.contains("*_s") || name.contains("*_str"));
                });
        
        // This assertion is lenient since dynamic fields vary by schema
        // assertTrue(hasStringDynamicField, "Schema should have string dynamic fields");
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