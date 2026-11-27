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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Utility class for processing JSON documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of JSON documents into Solr-compatible format using a
 * schema-less approach where Solr automatically detects field types.
 */
@Component
public class JsonDocumentCreator implements SolrDocumentCreator {

  private static final String EMPTY_INPUT_ERROR = "JSON input cannot be empty";

  /** Joins a nested object's key to its parent's, so {@code user.name} indexes as user_name. */
  private static final String NESTED_FIELD_SEPARATOR = "_";

  private final JsonMapper jsonMapper;

  /**
   * Constructs the creator with the Jackson {@link JsonMapper} used to parse incoming JSON.
   *
   * @param jsonMapper the JSON parser injected by Spring
   */
  public JsonDocumentCreator(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

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
   *   <li><strong>Nested Objects</strong>: Flattened using underscore notation (e.g., "user.name" →
   *       "user_name")
   *   <li><strong>Arrays</strong>: Non-object arrays converted to multi-valued fields
   *   <li><strong>Null Values</strong>: Ignored and not indexed
   *   <li><strong>Object Arrays</strong>: Skipped to avoid complex nested structures
   * </ul>
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
   */
  @Override
  public List<SolrInputDocument> create(String json) throws DocumentProcessingException {
    if (json.isBlank()) {
      throw new DocumentProcessingException(EMPTY_INPUT_ERROR);
    }

    JsonNode rootNode;
    try {
      rootNode = jsonMapper.readTree(json);
    } catch (JacksonException e) {
      throw new DocumentProcessingException("Failed to parse JSON document", e);
    }
    if (!rootNode.isArray() && !rootNode.isObject()) {
      throw new DocumentProcessingException("JSON input must be an object or an array of objects");
    }
    return flatten(rootNode);
  }

  /**
   * Creates schema-less documents from already-parsed JSON objects, one document per map. This is
   * the entry point for the {@code index-json-documents} tool, whose {@code documents} argument is
   * a typed JSON array: the MCP client parses it, so the model emits native JSON instead of JSON
   * escaped inside a string.
   *
   * <p>The maps are converted to a {@link JsonNode} tree and handed to the same {@link
   * #flatten(JsonNode)} walk {@link #create(String)} uses, so both entry points flatten identically
   * by construction.
   *
   * @param documents the documents, each a map of field name to value
   * @return list of SolrInputDocument objects ready for indexing
   * @throws DocumentProcessingException if the list is empty
   */
  public List<SolrInputDocument> create(List<Map<String, Object>> documents)
      throws DocumentProcessingException {
    if (documents.isEmpty()) {
      throw new DocumentProcessingException(EMPTY_INPUT_ERROR);
    }
    return flatten(jsonMapper.valueToTree(documents));
  }

  /**
   * Builds one document per element of an array node, or a single document from an object node.
   *
   * <p>A lone object is a single document: it previously fell through and returned an empty list,
   * so indexing one object silently indexed nothing.
   *
   * @param rootNode an array of document objects, or one document object
   * @return list of SolrInputDocument objects ready for indexing
   */
  private List<SolrInputDocument> flatten(JsonNode rootNode) {
    List<SolrInputDocument> documents = new ArrayList<>(rootNode.size());
    if (rootNode.isArray()) {
      for (JsonNode item : rootNode) {
        documents.add(toDocument(item));
      }
    } else {
      documents.add(toDocument(rootNode));
    }
    return documents;
  }

  /**
   * Flattens one JSON object node into a SolrInputDocument. Field names are added without type
   * suffixes; Solr infers the types.
   *
   * @param node the JSON object to flatten
   * @return the document, with nested objects flattened and arrays multi-valued
   */
  private SolrInputDocument toDocument(JsonNode node) {
    SolrInputDocument document = new SolrInputDocument();
    addAllFieldsFlat(document, node, "");
    return document;
  }

  /**
   * Adds every property of {@code node} to {@code document}, recursing into nested objects with
   * {@code prefix} prepended to the field names.
   */
  private void addAllFieldsFlat(SolrInputDocument document, JsonNode node, String prefix) {
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      addFieldValue(document, property.getValue(), prefix + property.getKey());
    }
  }

  /** Adds one value: nulls are skipped, arrays become multi-valued, objects are flattened. */
  private void addFieldValue(SolrInputDocument document, JsonNode value, String fieldName) {
    if (value.isNull()) {
      return;
    }
    if (value.isArray()) {
      addArrayField(document, value, fieldName);
    } else if (value.isObject()) {
      addAllFieldsFlat(document, value, fieldName + NESTED_FIELD_SEPARATOR);
    } else {
      document.addField(fieldName, convertJsonValue(value));
    }
  }

  private void addArrayField(SolrInputDocument document, JsonNode arrayValue, String fieldName) {
    // Skip objects and nested arrays alike: neither has a scalar
    // representation, and asString() on a container node throws.
    List<Object> values =
        arrayValue
            .valueStream()
            .filter(item -> !item.isContainer())
            .map(JsonDocumentCreator::convertJsonValue)
            .toList();
    if (!values.isEmpty()) {
      document.addField(fieldName, values);
    }
  }

  /**
   * Converts a JsonNode value to the appropriate Java object type for Solr indexing.
   *
   * <p>This method provides type-aware conversion of JSON values to their corresponding Java types,
   * ensuring that Solr receives properly typed data for optimal field type detection and indexing
   * performance.
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
  private static Object convertJsonValue(JsonNode value) {
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isLong()) {
      return value.asLong();
    }
    if (value.isDouble()) {
      return value.asDouble();
    }
    if (value.isInt()) {
      return value.asInt();
    }
    return value.asString();
  }
}
