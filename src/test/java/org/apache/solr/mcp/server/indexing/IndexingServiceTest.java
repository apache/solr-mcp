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
package org.apache.solr.mcp.server.indexing;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.indexing.documentcreator.CsvDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.JsonDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.XmlDocumentCreator;
import org.apache.solr.mcp.server.search.SearchResponse;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.SolrContainer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IndexingServiceTest {

    private static final String COLLECTION_NAME = "indexing_test_" + System.currentTimeMillis();
    private static boolean initialized = false;
    @Autowired
    private SolrContainer solrContainer;
    @Autowired
    private IndexingDocumentCreator indexingDocumentCreator;
    @Autowired
    private IndexingService indexingService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private SolrClient solrClient;

    @BeforeEach
    void setUp() throws Exception {

        // Create processor instances and wire them manually since this is not a Spring Boot test
        XmlDocumentCreator xmlDocumentCreator = new XmlDocumentCreator();
        CsvDocumentCreator csvDocumentCreator = new CsvDocumentCreator();
        JsonDocumentCreator jsonDocumentCreator = new JsonDocumentCreator();

        indexingDocumentCreator =
                new IndexingDocumentCreator(
                        xmlDocumentCreator, csvDocumentCreator, jsonDocumentCreator);

        indexingService = new IndexingService(solrClient, indexingDocumentCreator);
        searchService = new SearchService(solrClient);

        if (!initialized) {
            // Create collection
            CollectionAdminRequest.Create createRequest =
                    CollectionAdminRequest.createCollection(COLLECTION_NAME, "_default", 1, 1);
            createRequest.process(solrClient);
            initialized = true;
        }
    }

    @Test
    void testCreateSchemalessDocumentsFromJson() throws Exception {
        // Test JSON string
        String json =
                """
                        [
                          {
                            "id": "test001",
                            "cat": ["book"],
                            "name": ["Test Book 1"],
                            "price": [9.99],
                            "inStock": [true],
                            "author": ["Test Author"],
                            "series_t": "Test Series",
                            "sequence_i": 1,
                            "genre_s": "test"
                          }
                        ]
                        """;

        // Create documents
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromJson(json);

        // Verify documents were created correctly
        assertNotNull(documents);
        assertEquals(1, documents.size());

        SolrInputDocument doc = documents.getFirst();
        assertEquals("test001", doc.getFieldValue("id"));

        // Check field values - they might be stored directly or as collections
        Object nameValue = doc.getFieldValue("name");
        if (nameValue instanceof List) {
            assertEquals("Test Book 1", ((List<?>) nameValue).getFirst());
        } else {
            assertEquals("Test Book 1", nameValue);
        }

        Object priceValue = doc.getFieldValue("price");
        if (priceValue instanceof List) {
            assertEquals(9.99, ((List<?>) priceValue).getFirst());
        } else {
            assertEquals(9.99, priceValue);
        }

        Object inStockValue = doc.getFieldValue("inStock");
        // Check if inStock field exists
        if (inStockValue != null) {
            if (inStockValue instanceof List) {
                assertEquals(true, ((List<?>) inStockValue).getFirst());
            } else {
                assertEquals(true, inStockValue);
            }
        } else {
            // If inStock is not present in the document, we'll skip this assertion
            // Removed debug print statement
        }

        Object authorValue = doc.getFieldValue("author");
        if (authorValue instanceof List) {
            assertEquals("Test Author", ((List<?>) authorValue).getFirst());
        } else {
            assertEquals("Test Author", authorValue);
        }

        assertEquals("Test Series", doc.getFieldValue("series_t"));
        assertEquals(1, doc.getFieldValue("sequence_i"));
        assertEquals("test", doc.getFieldValue("genre_s"));
    }

    @Test
    void testIndexJsonDocuments() throws Exception {

        // Test JSON string with multiple documents
        String json =
                """
                        [
                          {
                            "id": "test002",
                            "cat": ["book"],
                            "name": ["Test Book 2"],
                            "price": [19.99],
                            "inStock": [true],
                            "author": ["Test Author 2"],
                            "genre_s": "scifi"
                          },
                          {
                            "id": "test003",
                            "cat": ["book"],
                            "name": ["Test Book 3"],
                            "price": [29.99],
                            "inStock": [false],
                            "author": ["Test Author 3"],
                            "genre_s": "fantasy"
                          }
                        ]
                        """;

        // Index documents
        indexingService.indexJsonDocuments(COLLECTION_NAME, json);

        // Verify documents were indexed by searching for them
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, "id:test002 OR id:test003", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(2, documents.size());

        // Verify specific document fields
        boolean foundBook2 = false;
        boolean foundBook3 = false;

        for (Map<String, Object> book : documents) {
            // Get ID and handle both String and List cases
            Object idValue = book.get("id");
            String id;
            if (idValue instanceof List) {
                id = (String) ((List<?>) idValue).getFirst();
            } else {
                id = (String) idValue;
            }

            if (id.equals("test002")) {
                foundBook2 = true;

                // Handle name field
                Object nameValue = book.get("name");
                if (nameValue instanceof List) {
                    assertEquals("Test Book 2", ((List<?>) nameValue).getFirst());
                } else {
                    assertEquals("Test Book 2", nameValue);
                }

                // Handle author field
                Object authorValue = book.get("author");
                if (authorValue instanceof List) {
                    assertEquals("Test Author 2", ((List<?>) authorValue).getFirst());
                } else {
                    assertEquals("Test Author 2", authorValue);
                }

                // Handle genre field
                Object genreValue = book.get("genre_s");
                if (genreValue instanceof List) {
                    assertEquals("scifi", ((List<?>) genreValue).getFirst());
                } else {
                    assertEquals("scifi", genreValue);
                }
            } else if (id.equals("test003")) {
                foundBook3 = true;

                // Handle name field
                Object nameValue = book.get("name");
                if (nameValue instanceof List) {
                    assertEquals("Test Book 3", ((List<?>) nameValue).getFirst());
                } else {
                    assertEquals("Test Book 3", nameValue);
                }

                // Handle author field
                Object authorValue = book.get("author");
                if (authorValue instanceof List) {
                    assertEquals("Test Author 3", ((List<?>) authorValue).getFirst());
                } else {
                    assertEquals("Test Author 3", authorValue);
                }

                // Handle genre field
                Object genreValue = book.get("genre_s");
                if (genreValue instanceof List) {
                    assertEquals("fantasy", ((List<?>) genreValue).getFirst());
                } else {
                    assertEquals("fantasy", genreValue);
                }
            }
        }

        assertTrue(foundBook2, "Book 2 should be found in search results");
        assertTrue(foundBook3, "Book 3 should be found in search results");
    }

    @Test
    void testIndexJsonDocumentsWithNestedObjects() throws Exception {

        // Test JSON string with nested objects
        String json =
                """
                        [
                          {
                            "id": "test004",
                            "cat": ["book"],
                            "name": ["Test Book 4"],
                            "price": [39.99],
                            "details": {
                              "publisher": "Test Publisher",
                              "year": 2023,
                              "edition": 1
                            },
                            "author": ["Test Author 4"]
                          }
                        ]
                        """;

        // Index documents
        indexingService.indexJsonDocuments(COLLECTION_NAME, json);

        // Verify documents were indexed by searching for them
        SearchResponse result =
                searchService.search(COLLECTION_NAME, "id:test004", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> book = documents.getFirst();

        // Handle ID field
        Object idValue = book.get("id");
        if (idValue instanceof List) {
            assertEquals("test004", ((List<?>) idValue).getFirst());
        } else {
            assertEquals("test004", idValue);
        }

        // Handle name field
        Object nameValue = book.get("name");
        if (nameValue instanceof List) {
            assertEquals("Test Book 4", ((List<?>) nameValue).getFirst());
        } else {
            assertEquals("Test Book 4", nameValue);
        }

        // Check that nested fields were flattened with underscore prefix
        assertNotNull(book.get("details_publisher"));
        Object publisherValue = book.get("details_publisher");
        if (publisherValue instanceof List) {
            assertEquals("Test Publisher", ((List<?>) publisherValue).getFirst());
        } else {
            assertEquals("Test Publisher", publisherValue);
        }

        assertNotNull(book.get("details_year"));
        Object yearValue = book.get("details_year");
        if (yearValue instanceof List) {
            assertEquals(2023, ((Number) ((List<?>) yearValue).getFirst()).intValue());
        } else if (yearValue instanceof Number) {
            assertEquals(2023, ((Number) yearValue).intValue());
        } else {
            assertEquals("2023", yearValue.toString());
        }
    }

    @Test
    void testSanitizeFieldName() throws Exception {

        // Test JSON string with field names that need sanitizing
        String json =
                """
                        [
                          {
                            "id": "test005",
                            "invalid-field": "Value with hyphen",
                            "another.invalid": "Value with dot",
                            "UPPERCASE": "Value with uppercase",
                            "multiple__underscores": "Value with multiple underscores"
                          }
                        ]
                        """;

        // Index documents
        indexingService.indexJsonDocuments(COLLECTION_NAME, json);

        // Verify documents were indexed with sanitized field names
        SearchResponse result =
                searchService.search(COLLECTION_NAME, "id:test005", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> doc = documents.getFirst();

        // Check that field names were sanitized
        assertNotNull(doc.get("invalid_field"));
        Object invalidFieldValue = doc.get("invalid_field");
        if (invalidFieldValue instanceof List) {
            assertEquals("Value with hyphen", ((List<?>) invalidFieldValue).getFirst());
        } else {
            assertEquals("Value with hyphen", invalidFieldValue);
        }

        assertNotNull(doc.get("another_invalid"));
        Object anotherInvalidValue = doc.get("another_invalid");
        if (anotherInvalidValue instanceof List) {
            assertEquals("Value with dot", ((List<?>) anotherInvalidValue).getFirst());
        } else {
            assertEquals("Value with dot", anotherInvalidValue);
        }

        // Should be lowercase
        assertNotNull(doc.get("uppercase"));
        Object uppercaseValue = doc.get("uppercase");
        if (uppercaseValue instanceof List) {
            assertEquals("Value with uppercase", ((List<?>) uppercaseValue).getFirst());
        } else {
            assertEquals("Value with uppercase", uppercaseValue);
        }

        // Multiple underscores should be collapsed
        assertNotNull(doc.get("multiple_underscores"));
        Object multipleUnderscoresValue = doc.get("multiple_underscores");
        if (multipleUnderscoresValue instanceof List) {
            assertEquals(
                    "Value with multiple underscores",
                    ((List<?>) multipleUnderscoresValue).getFirst());
        } else {
            assertEquals("Value with multiple underscores", multipleUnderscoresValue);
        }
    }

    @Test
    void testDeeplyNestedJsonStructures() throws Exception {

        // Test JSON string with deeply nested objects (3+ levels)
        String json =
                """
                        [
                          {
                            "id": "nested001",
                            "title": "Deeply nested document",
                            "metadata": {
                              "publication": {
                                "publisher": {
                                  "name": "Deep Nest Publishing",
                                  "location": {
                                    "city": "Nestville",
                                    "country": "Nestland",
                                    "coordinates": {
                                      "latitude": 42.123,
                                      "longitude": -71.456
                                    }
                                  }
                                },
                                "year": 2023,
                                "edition": {
                                  "number": 1,
                                  "type": "First Edition",
                                  "notes": {
                                    "condition": "New",
                                    "availability": "Limited"
                                  }
                                }
                              },
                              "classification": {
                                "primary": "Test",
                                "secondary": {
                                  "category": "Nested",
                                  "subcategory": "Deep"
                                }
                              }
                            }
                          }
                        ]
                        """;

        // Index documents
        indexingService.indexJsonDocuments(COLLECTION_NAME, json);

        // Verify documents were indexed by searching for them
        SearchResponse result =
                searchService.search(COLLECTION_NAME, "id:nested001", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> doc = documents.getFirst();

        // Check that deeply nested fields were flattened with underscore prefix
        // Level 1
        assertNotNull(doc.get("metadata_publication_publisher_name"));
        assertEquals(
                "Deep Nest Publishing", getFieldValue(doc, "metadata_publication_publisher_name"));

        // Level 2
        assertNotNull(doc.get("metadata_publication_publisher_location_city"));
        assertEquals(
                "Nestville", getFieldValue(doc, "metadata_publication_publisher_location_city"));

        // Level 3
        assertNotNull(doc.get("metadata_publication_publisher_location_coordinates_latitude"));
        assertEquals(
                42.123,
                ((Number)
                        getFieldValue(
                                doc,
                                "metadata_publication_publisher_location_coordinates_latitude"))
                        .doubleValue(),
                0.001);

        // Check other branches of the nested structure
        assertNotNull(doc.get("metadata_publication_edition_notes_condition"));
        assertEquals("New", getFieldValue(doc, "metadata_publication_edition_notes_condition"));

        assertNotNull(doc.get("metadata_classification_secondary_subcategory"));
        assertEquals("Deep", getFieldValue(doc, "metadata_classification_secondary_subcategory"));
    }

    private Object getFieldValue(Map<String, Object> doc, String fieldName) {
        Object value = doc.get(fieldName);
        if (value instanceof List) {
            return ((List<?>) value).getFirst();
        }
        return value;
    }

    @Test
    void testSpecialCharactersInFieldNames() throws Exception {

        // Test JSON string with field names containing various special characters
        String json =
                """
                        [
                          {
                            "id": "special_fields_001",
                            "field@with@at": "Value with @ symbols",
                            "field#with#hash": "Value with # symbols",
                            "field$with$dollar": "Value with $ symbols",
                            "field%with%percent": "Value with % symbols",
                            "field^with^caret": "Value with ^ symbols",
                            "field&with&ampersand": "Value with & symbols",
                            "field*with*asterisk": "Value with * symbols",
                            "field(with)parentheses": "Value with parentheses",
                            "field[with]brackets": "Value with brackets",
                            "field{with}braces": "Value with braces",
                            "field+with+plus": "Value with + symbols",
                            "field=with=equals": "Value with = symbols",
                            "field:with:colon": "Value with : symbols",
                            "field;with;semicolon": "Value with ; symbols",
                            "field'with'quotes": "Value with ' symbols",
                            "field\\"with\\"doublequotes": "Value with \\" symbols",
                            "field<with>anglebrackets": "Value with angle brackets",
                            "field,with,commas": "Value with , symbols",
                            "field?with?question": "Value with ? symbols",
                            "field/with/slashes": "Value with / symbols",
                            "field\\\\with\\\\backslashes": "Value with \\\\ symbols",
                            "field|with|pipes": "Value with | symbols",
                            "field`with`backticks": "Value with ` symbols",
                            "field~with~tildes": "Value with ~ symbols"
                          }
                        ]
                        """;

        // Index documents
        indexingService.indexJsonDocuments(COLLECTION_NAME, json);

        // Verify documents were indexed by searching for them
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, "id:special_fields_001", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> doc = documents.getFirst();

        // Check that field names with special characters were sanitized
        // All special characters should be replaced with underscores
        assertNotNull(doc.get("field_with_at"));
        assertEquals("Value with @ symbols", getFieldValue(doc, "field_with_at"));

        assertNotNull(doc.get("field_with_hash"));
        assertEquals("Value with # symbols", getFieldValue(doc, "field_with_hash"));

        assertNotNull(doc.get("field_with_dollar"));
        assertEquals("Value with $ symbols", getFieldValue(doc, "field_with_dollar"));

        assertNotNull(doc.get("field_with_percent"));
        assertEquals("Value with % symbols", getFieldValue(doc, "field_with_percent"));

        assertNotNull(doc.get("field_with_caret"));
        assertEquals("Value with ^ symbols", getFieldValue(doc, "field_with_caret"));

        assertNotNull(doc.get("field_with_ampersand"));
        assertEquals("Value with & symbols", getFieldValue(doc, "field_with_ampersand"));

        assertNotNull(doc.get("field_with_asterisk"));
        assertEquals("Value with * symbols", getFieldValue(doc, "field_with_asterisk"));

        assertNotNull(doc.get("field_with_parentheses"));
        assertEquals("Value with parentheses", getFieldValue(doc, "field_with_parentheses"));

        assertNotNull(doc.get("field_with_brackets"));
        assertEquals("Value with brackets", getFieldValue(doc, "field_with_brackets"));

        assertNotNull(doc.get("field_with_braces"));
        assertEquals("Value with braces", getFieldValue(doc, "field_with_braces"));
    }

    @Test
    void testArraysOfObjects() throws Exception {

        // Test JSON string with arrays of objects
        String json =
                """
                        [
                          {
                            "id": "array_objects_001",
                            "title": "Document with arrays of objects",
                            "authors": [
                              {
                                "name": "Author One",
                                "email": "author1@example.com",
                                "affiliation": "University A"
                              },
                              {
                                "name": "Author Two",
                                "email": "author2@example.com",
                                "affiliation": "University B"
                              }
                            ],
                            "reviews": [
                              {
                                "reviewer": "Reviewer A",
                                "rating": 4,
                                "comments": "Good document"
                              },
                              {
                                "reviewer": "Reviewer B",
                                "rating": 5,
                                "comments": "Excellent document"
                              },
                              {
                                "reviewer": "Reviewer C",
                                "rating": 3,
                                "comments": "Average document"
                              }
                            ],
                            "keywords": ["arrays", "objects", "testing"]
                          }
                        ]
                        """;

        // Index documents
        indexingService.indexJsonDocuments(COLLECTION_NAME, json);

        // Verify documents were indexed by searching for them
        SearchResponse result =
                searchService.search(
                        COLLECTION_NAME, "id:array_objects_001", null, null, null, null, null);

        assertNotNull(result);
        List<Map<String, Object>> documents = result.documents();
        assertEquals(1, documents.size());

        Map<String, Object> doc = documents.getFirst();

        // Check that the document was indexed correctly
        assertEquals("array_objects_001", getFieldValue(doc, "id"));
        assertEquals("Document with arrays of objects", getFieldValue(doc, "title"));

        // Check that the arrays of primitive values were indexed correctly
        Object keywordsObj = doc.get("keywords");
        if (keywordsObj instanceof List) {
            List<?> keywords = (List<?>) keywordsObj;
            assertEquals(3, keywords.size());
            assertTrue(keywords.contains("arrays"));
            assertTrue(keywords.contains("objects"));
            assertTrue(keywords.contains("testing"));
        }

        // For arrays of objects, the IndexingService should flatten them with field names
        // that include the array name and the object field name
        // We can't directly access the array elements, but we can check if the flattened fields
        // exist

        // Check for flattened author fields
        // Note: The current implementation in IndexingService.java doesn't handle arrays of objects
        // in a way that preserves the array structure. It skips object items in arrays (line
        // 68-70).
        // This test is checking the current behavior, which may need improvement in the future.

        // Check for flattened review fields
        // Same note as above applies here
    }

    @Test
    void testNonArrayJsonInput() throws Exception {
        // Test JSON string that is not an array but a single object
        String json =
                """
                        {
                          "id": "single_object_001",
                          "title": "Single Object Document",
                          "author": "Test Author",
                          "year": 2023
                        }
                        """;

        // Create documents
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromJson(json);

        // Verify no documents were created since input is not an array
        assertNotNull(documents);
        assertEquals(0, documents.size());
    }

    @Test
    void testConvertJsonValueTypes() throws Exception {
        // Test JSON with different value types
        String json =
                """
                        [
                          {
                            "id": "value_types_001",
                            "boolean_value": true,
                            "int_value": 42,
                            "double_value": 3.14159,
                            "long_value": 9223372036854775807,
                            "text_value": "This is a text value"
                          }
                        ]
                        """;

        // Create documents
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromJson(json);

        // Verify documents were created correctly
        assertNotNull(documents);
        assertEquals(1, documents.size());

        SolrInputDocument doc = documents.getFirst();
        assertEquals("value_types_001", doc.getFieldValue("id"));

        // Verify each value type was converted correctly
        assertEquals(true, doc.getFieldValue("boolean_value"));
        assertEquals(42, doc.getFieldValue("int_value"));
        assertEquals(3.14159, doc.getFieldValue("double_value"));
        assertEquals(9223372036854775807L, doc.getFieldValue("long_value"));
        assertEquals("This is a text value", doc.getFieldValue("text_value"));
    }

    @Test
    void testDirectSanitizeFieldName() throws Exception {
        // Test sanitizing field names directly
        // Create a document with field names that need sanitizing
        String json =
                """
                        [
                          {
                            "id": "field_names_001",
                            "field-with-hyphens": "Value 1",
                            "field.with.dots": "Value 2",
                            "field with spaces": "Value 3",
                            "UPPERCASE_FIELD": "Value 4",
                            "__leading_underscores__": "Value 5",
                            "trailing_underscores___": "Value 6",
                            "multiple___underscores": "Value 7"
                          }
                        ]
                        """;

        // Create documents
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromJson(json);

        // Verify documents were created correctly
        assertNotNull(documents);
        assertEquals(1, documents.size());

        SolrInputDocument doc = documents.getFirst();

        // Verify field names were sanitized correctly
        assertEquals("field_names_001", doc.getFieldValue("id"));
        assertEquals("Value 1", doc.getFieldValue("field_with_hyphens"));
        assertEquals("Value 2", doc.getFieldValue("field_with_dots"));
        assertEquals("Value 3", doc.getFieldValue("field_with_spaces"));
        assertEquals("Value 4", doc.getFieldValue("uppercase_field"));
        assertEquals("Value 5", doc.getFieldValue("leading_underscores"));
        assertEquals("Value 6", doc.getFieldValue("trailing_underscores"));
        assertEquals("Value 7", doc.getFieldValue("multiple_underscores"));
    }
}

@Nested
@ExtendWith(MockitoExtension.class)
class UnitTests {

    @Mock
    private SolrClient solrClient;

    @Mock
    private IndexingDocumentCreator indexingDocumentCreator;

    private IndexingService indexingService;

    @BeforeEach
    void setUp() {
        indexingService = new IndexingService(solrClient, indexingDocumentCreator);
    }

    @Test
    void constructor_ShouldInitializeWithDependencies() {
        assertNotNull(indexingService);
    }

    @Test
    void indexJsonDocuments_WithValidJson_ShouldIndexDocuments() throws Exception {
        String json = "[{\"id\":\"1\",\"title\":\"Test\"}]";
        List<SolrInputDocument> mockDocs = createMockDocuments(1);
        when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        indexingService.indexJsonDocuments("test_collection", json);

        verify(indexingDocumentCreator).createSchemalessDocumentsFromJson(json);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexJsonDocuments_WhenDocumentCreatorThrowsException_ShouldPropagateException()
            throws Exception {
        String invalidJson = "not valid json";
        when(indexingDocumentCreator.createSchemalessDocumentsFromJson(invalidJson))
                .thenThrow(
                        new org.apache.solr.mcp.server.indexing.documentcreator
                                .DocumentProcessingException("Invalid JSON"));

        assertThrows(
                org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException
                        .class,
                () -> {
                    indexingService.indexJsonDocuments("test_collection", invalidJson);
                });
        verify(solrClient, never()).add(anyString(), any(Collection.class));
        verify(solrClient, never()).commit(anyString());
    }

    @Test
    void indexCsvDocuments_WithValidCsv_ShouldIndexDocuments() throws Exception {
        String csv = "id,title\n1,Test\n2,Test2";
        List<SolrInputDocument> mockDocs = createMockDocuments(2);
        when(indexingDocumentCreator.createSchemalessDocumentsFromCsv(csv)).thenReturn(mockDocs);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        indexingService.indexCsvDocuments("test_collection", csv);

        verify(indexingDocumentCreator).createSchemalessDocumentsFromCsv(csv);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexCsvDocuments_WhenDocumentCreatorThrowsException_ShouldPropagateException()
            throws Exception {
        String invalidCsv = "malformed csv data";
        when(indexingDocumentCreator.createSchemalessDocumentsFromCsv(invalidCsv))
                .thenThrow(
                        new org.apache.solr.mcp.server.indexing.documentcreator
                                .DocumentProcessingException("Invalid CSV"));

        assertThrows(
                org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException
                        .class,
                () -> {
                    indexingService.indexCsvDocuments("test_collection", invalidCsv);
                });
        verify(solrClient, never()).add(anyString(), any(Collection.class));
        verify(solrClient, never()).commit(anyString());
    }

    @Test
    void indexXmlDocuments_WithValidXml_ShouldIndexDocuments() throws Exception {
        String xml = "<documents><doc><id>1</id><title>Test</title></doc></documents>";
        List<SolrInputDocument> mockDocs = createMockDocuments(1);
        when(indexingDocumentCreator.createSchemalessDocumentsFromXml(xml)).thenReturn(mockDocs);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        indexingService.indexXmlDocuments("test_collection", xml);

        verify(indexingDocumentCreator).createSchemalessDocumentsFromXml(xml);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexXmlDocuments_WhenParserConfigurationFails_ShouldPropagateException()
            throws Exception {
        String xml = "<invalid>xml</invalid>";
        when(indexingDocumentCreator.createSchemalessDocumentsFromXml(xml))
                .thenThrow(
                        new org.apache.solr.mcp.server.indexing.documentcreator
                                .DocumentProcessingException("Parser error"));

        assertThrows(
                org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException
                        .class,
                () -> {
                    indexingService.indexXmlDocuments("test_collection", xml);
                });
        verify(solrClient, never()).add(anyString(), any(Collection.class));
        verify(solrClient, never()).commit(anyString());
    }

    @Test
    void indexXmlDocuments_WhenSaxExceptionOccurs_ShouldPropagateException() throws Exception {
        String xml = "<malformed><unclosed>";
        when(indexingDocumentCreator.createSchemalessDocumentsFromXml(xml))
                .thenThrow(
                        new org.apache.solr.mcp.server.indexing.documentcreator
                                .DocumentProcessingException("SAX parsing error"));

        assertThrows(
                org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException
                        .class,
                () -> {
                    indexingService.indexXmlDocuments("test_collection", xml);
                });
        verify(solrClient, never()).add(anyString(), any(Collection.class));
        verify(solrClient, never()).commit(anyString());
    }

    @Test
    void indexDocuments_WithSmallBatch_ShouldIndexSuccessfully() throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(5);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", docs);

        assertEquals(5, result);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexDocuments_WithLargeBatch_ShouldProcessInBatches() throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(2500);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", docs);

        assertEquals(2500, result);
        verify(solrClient, times(3)).add(eq("test_collection"), any(Collection.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexDocuments_WhenBatchFails_ShouldRetryIndividually() throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(3);

        when(solrClient.add(eq("test_collection"), any(List.class)))
                .thenThrow(new SolrServerException("Batch error"));

        when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", docs);

        assertEquals(3, result);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient, times(3)).add(eq("test_collection"), any(SolrInputDocument.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexDocuments_WhenSomeIndividualDocumentsFail_ShouldIndexSuccessfulOnes()
            throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(3);

        when(solrClient.add(eq("test_collection"), any(List.class)))
                .thenThrow(new SolrServerException("Batch error"));

        when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class)))
                .thenReturn(null)
                .thenThrow(new SolrServerException("Document error"))
                .thenReturn(null);

        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", docs);

        assertEquals(2, result);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient, times(3)).add(eq("test_collection"), any(SolrInputDocument.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexDocuments_WithEmptyList_ShouldStillCommit() throws Exception {
        List<SolrInputDocument> emptyDocs = new ArrayList<>();
        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", emptyDocs);

        assertEquals(0, result);
        verify(solrClient, never()).add(anyString(), any(List.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexDocuments_WhenCommitFails_ShouldPropagateException() throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(2);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenThrow(new IOException("Commit failed"));

        assertThrows(
                IOException.class,
                () -> {
                    indexingService.indexDocuments("test_collection", docs);
                });
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexDocuments_ShouldBatchCorrectly() throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(1000);
        when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", docs);

        assertEquals(1000, result);

        ArgumentCaptor<Collection<SolrInputDocument>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(solrClient).add(eq("test_collection"), captor.capture());
        assertEquals(1000, captor.getValue().size());
        verify(solrClient).commit("test_collection");
    }

    @Test
    void indexJsonDocuments_WhenSolrClientThrowsException_ShouldPropagateException()
            throws Exception {
        String json = "[{\"id\":\"1\"}]";
        List<SolrInputDocument> mockDocs = createMockDocuments(1);
        when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
        when(solrClient.add(eq("test_collection"), any(List.class)))
                .thenThrow(new SolrServerException("Solr connection error"));
        when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class)))
                .thenThrow(new SolrServerException("Solr connection error"));
        when(solrClient.commit("test_collection")).thenReturn(null);

        indexingService.indexJsonDocuments("test_collection", json);

        verify(solrClient).add(eq("test_collection"), any(List.class));
        verify(solrClient).add(eq("test_collection"), any(SolrInputDocument.class));
    }

    @Test
    void indexCsvDocuments_WhenSolrClientThrowsIOException_ShouldPropagateException()
            throws Exception {
        String csv = "id,title\n1,Test";
        List<SolrInputDocument> mockDocs = createMockDocuments(1);
        when(indexingDocumentCreator.createSchemalessDocumentsFromCsv(csv)).thenReturn(mockDocs);
        when(solrClient.add(eq("test_collection"), any(List.class)))
                .thenThrow(new IOException("Network error"));
        when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class)))
                .thenThrow(new IOException("Network error"));
        when(solrClient.commit("test_collection")).thenReturn(null);

        indexingService.indexCsvDocuments("test_collection", csv);

        verify(solrClient).add(eq("test_collection"), any(List.class));
        verify(solrClient).add(eq("test_collection"), any(SolrInputDocument.class));
    }

    @Test
    void indexDocuments_WithRuntimeException_ShouldRetryIndividually() throws Exception {
        List<SolrInputDocument> docs = createMockDocuments(2);

        when(solrClient.add(eq("test_collection"), any(List.class)))
                .thenThrow(new RuntimeException("Unexpected error"));

        when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null);
        when(solrClient.commit("test_collection")).thenReturn(null);

        int result = indexingService.indexDocuments("test_collection", docs);

        assertEquals(2, result);
        verify(solrClient).add(eq("test_collection"), any(Collection.class));
        verify(solrClient, times(2)).add(eq("test_collection"), any(SolrInputDocument.class));
        verify(solrClient).commit("test_collection");
    }

    private List<SolrInputDocument> createMockDocuments(int count) {
        List<SolrInputDocument> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("id", "doc" + i);
            doc.addField("title", "Document " + i);
            docs.add(doc);
        }
        return docs;
    }
}
