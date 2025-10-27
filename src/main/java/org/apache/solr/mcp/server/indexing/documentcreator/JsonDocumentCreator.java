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
package org.apache.solr.mcp.server.indexing.documentcreator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for processing JSON documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of JSON documents into Solr-compatible format using a
 * schema-less approach where Solr automatically detects field types.
 */
@Component
public class JsonDocumentCreator implements SolrDocumentCreator {

    private static final int MAX_INPUT_SIZE_BYTES = 10 * 1024 * 1024;

    /**
     * Creates a list of schema-less SolrInputDocument objects from a JSON string.
     *
     * <p>This method implements a flexible document conversion strategy that allows Solr to
     * automatically detect field types without requiring predefined schema configuration. It
     * processes complex JSON structures by flattening nested objects and handling arrays
     * appropriately for Solr's multi-valued field support.
     *
     * <p><strong>Schema-less Benefits:</strong>
     *
     * <ul>
     *   <li><strong>Flexibility</strong>: No need to predefine field types in schema
     *   <li><strong>Rapid Prototyping</strong>: Quick iteration on document structures
     *   <li><strong>Type Detection</strong>: Solr automatically infers optimal field types
     *   <li><strong>Dynamic Fields</strong>: Support for varying document structures
     * </ul>
     *
     * <p><strong>JSON Processing Rules:</strong>
     *
     * <ul>
     *   <li><strong>Nested Objects</strong>: Flattened using underscore notation (e.g., "user.name"
     *       → "user_name")
     *   <li><strong>Arrays</strong>: Non-object arrays converted to multi-valued fields
     *   <li><strong>Null Values</strong>: Ignored and not indexed
     *   <li><strong>Object Arrays</strong>: Skipped to avoid complex nested structures
     * </ul>
     *
     * <p><strong>Field Name Sanitization:</strong>
     *
     * <p>Field names are automatically sanitized to ensure Solr compatibility by removing special
     * characters and converting to lowercase with underscore separators.
     *
     * <p><strong>Example Transformations:</strong>
     *
     * <pre>{@code
     * Input:  {"user":{"name":"John","age":30},"tags":["tech","java"]}
     * Output: {user_name:"John", user_age:30, tags:["tech","java"]}
     * }</pre>
     *
     * @param json JSON string containing document data (must be an array)
     * @return list of SolrInputDocument objects ready for indexing
     * @throws DocumentProcessingException if JSON parsing fails, input validation fails, or the
     *     structure is invalid
     * @see SolrInputDocument
     * @see #addAllFieldsFlat(SolrInputDocument, JsonNode, String)
     * @see FieldNameSanitizer#sanitizeFieldName(String)
     */
    public List<SolrInputDocument> create(String json) throws DocumentProcessingException {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_SIZE_BYTES) {
            throw new DocumentProcessingException(
                    "Input too large: exceeds maximum size of " + MAX_INPUT_SIZE_BYTES + " bytes");
        }

        List<SolrInputDocument> documents = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(json);

            if (rootNode.isArray()) {
                for (JsonNode item : rootNode) {
                    SolrInputDocument doc = new SolrInputDocument();

                    // Add all fields without type suffixes - let Solr figure it out
                    addAllFieldsFlat(doc, item, "");
                    documents.add(doc);
                }
            }
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to parse JSON document", e);
        }

        return documents;
    }

    /**
     * Recursively flattens JSON nodes and adds them as fields to a SolrInputDocument.
     *
     * <p>This method implements the core logic for converting nested JSON structures into flat
     * field names that Solr can efficiently index and search. It handles various JSON node types
     * appropriately while maintaining data integrity.
     *
     * <p><strong>Processing Logic:</strong>
     *
     * <ul>
     *   <li><strong>Null Values</strong>: Skipped to avoid indexing empty fields
     *   <li><strong>Arrays</strong>: Non-object items converted to multi-valued fields
     *   <li><strong>Objects</strong>: Recursively flattened with prefix concatenation
     *   <li><strong>Primitives</strong>: Directly added with appropriate type conversion
     * </ul>
     *
     * @param doc the SolrInputDocument to add fields to
     * @param node the JSON node to process
     * @param prefix current field name prefix for nested object flattening
     * @see #convertJsonValue(JsonNode)
     * @see FieldNameSanitizer#sanitizeFieldName(String)
     */
    private void addAllFieldsFlat(SolrInputDocument doc, JsonNode node, String prefix) {
        Set<Map.Entry<String, JsonNode>> fields = node.properties();
        fields.forEach(
                field ->
                        processFieldValue(
                                doc,
                                field.getValue(),
                                FieldNameSanitizer.sanitizeFieldName(prefix + field.getKey())));
    }

    /**
     * Processes the provided field value and adds it to the given SolrInputDocument. Handles cases
     * where the field value is an array, object, or a simple value.
     *
     * @param doc the SolrInputDocument to which the field value will be added
     * @param value the JsonNode representing the field value to be processed
     * @param fieldName the name of the field to be added to the SolrInputDocument
     */
    private void processFieldValue(SolrInputDocument doc, JsonNode value, String fieldName) {
        if (value.isNull()) {
            return;
        }

        if (value.isArray()) {
            processArrayField(doc, value, fieldName);
        } else if (value.isObject()) {
            addAllFieldsFlat(doc, value, fieldName + "_");
        } else {
            doc.addField(fieldName, convertJsonValue(value));
        }
    }

    /**
     * Processes a JSON array field and adds its non-object elements to the specified field in the
     * given SolrInputDocument.
     *
     * @param doc the SolrInputDocument to which the processed field will be added
     * @param arrayValue the JSON array node to process
     * @param fieldName the name of the field in the SolrInputDocument to which the array values
     *     will be added
     */
    private void processArrayField(SolrInputDocument doc, JsonNode arrayValue, String fieldName) {
        List<Object> values = new ArrayList<>();
        for (JsonNode item : arrayValue) {
            if (!item.isObject()) {
                values.add(convertJsonValue(item));
            }
        }
        if (!values.isEmpty()) {
            doc.addField(fieldName, values);
        }
    }

    /**
     * Converts a JsonNode value to the appropriate Java object type for Solr indexing.
     *
     * <p>This method provides type-aware conversion of JSON values to their corresponding Java
     * types, ensuring that Solr receives properly typed data for optimal field type detection and
     * indexing performance.
     *
     * <p><strong>Supported Type Conversions:</strong>
     *
     * <ul>
     *   <li><strong>Boolean</strong>: JSON boolean → Java Boolean
     *   <li><strong>Integer</strong>: JSON number (int range) → Java Integer
     *   <li><strong>Long</strong>: JSON number (long range) → Java Long
     *   <li><strong>Double</strong>: JSON number (decimal) → Java Double
     *   <li><strong>String</strong>: All other values → Java String
     * </ul>
     *
     * @param value the JsonNode value to convert
     * @return the converted Java object with appropriate type
     * @see JsonNode
     */
    private Object convertJsonValue(JsonNode value) {
        if (value.isBoolean()) return value.asBoolean();
        if (value.isLong()) return value.asLong();
        if (value.isDouble()) return value.asDouble();
        if (value.isInt()) return value.asInt();
        return value.asText();
    }
}
