package org.apache.solr.mcp.server.indexing.documentcreator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility class for processing JSON documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of JSON documents into Solr-compatible format
 * using a schema-less approach where Solr automatically detects field types.</p>
 */
@Component
public class JsonDocumentCreator implements SolrDocumentCreator {

    private static final Pattern FIELD_SANITIZATION_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern UNDERSCORE_CLEANUP_PATTERN = Pattern.compile("^_+|_+$");
    private static final Pattern MULTIPLE_UNDERSCORES_PATTERN = Pattern.compile("_{2,}");

    /**
     * Creates a list of schema-less SolrInputDocument objects from a JSON string.
     *
     * <p>This method implements a flexible document conversion strategy that allows Solr
     * to automatically detect field types without requiring predefined schema configuration.
     * It processes complex JSON structures by flattening nested objects and handling arrays
     * appropriately for Solr's multi-valued field support.</p>
     *
     * <p><strong>Schema-less Benefits:</strong></p>
     * <ul>
     *   <li><strong>Flexibility</strong>: No need to predefine field types in schema</li>
     *   <li><strong>Rapid Prototyping</strong>: Quick iteration on document structures</li>
     *   <li><strong>Type Detection</strong>: Solr automatically infers optimal field types</li>
     *   <li><strong>Dynamic Fields</strong>: Support for varying document structures</li>
     * </ul>
     *
     * <p><strong>JSON Processing Rules:</strong></p>
     * <ul>
     *   <li><strong>Nested Objects</strong>: Flattened using underscore notation (e.g., "user.name" → "user_name")</li>
     *   <li><strong>Arrays</strong>: Non-object arrays converted to multi-valued fields</li>
     *   <li><strong>Null Values</strong>: Ignored and not indexed</li>
     *   <li><strong>Object Arrays</strong>: Skipped to avoid complex nested structures</li>
     * </ul>
     *
     * <p><strong>Field Name Sanitization:</strong></p>
     * <p>Field names are automatically sanitized to ensure Solr compatibility by removing
     * special characters and converting to lowercase with underscore separators.</p>
     *
     * <p><strong>Example Transformations:</strong></p>
     * <pre>{@code
     * Input:  {"user":{"name":"John","age":30},"tags":["tech","java"]}
     * Output: {user_name:"John", user_age:30, tags:["tech","java"]}
     * }</pre>
     *
     * @param json JSON string containing document data (must be an array)
     * @return list of SolrInputDocument objects ready for indexing
     * @throws IOException if JSON parsing fails or the structure is invalid
     * @see SolrInputDocument
     * @see #addAllFieldsFlat(SolrInputDocument, JsonNode, String)
     * @see #sanitizeFieldName(String)
     */
    public List<SolrInputDocument> create(String json) throws IOException {
        List<SolrInputDocument> documents = new ArrayList<>();
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

        return documents;
    }

    /**
     * Recursively flattens JSON nodes and adds them as fields to a SolrInputDocument.
     *
     * <p>This method implements the core logic for converting nested JSON structures
     * into flat field names that Solr can efficiently index and search. It handles
     * various JSON node types appropriately while maintaining data integrity.</p>
     *
     * <p><strong>Processing Logic:</strong></p>
     * <ul>
     *   <li><strong>Null Values</strong>: Skipped to avoid indexing empty fields</li>
     *   <li><strong>Arrays</strong>: Non-object items converted to multi-valued fields</li>
     *   <li><strong>Objects</strong>: Recursively flattened with prefix concatenation</li>
     *   <li><strong>Primitives</strong>: Directly added with appropriate type conversion</li>
     * </ul>
     *
     * @param doc    the SolrInputDocument to add fields to
     * @param node   the JSON node to process
     * @param prefix current field name prefix for nested object flattening
     * @see #convertJsonValue(JsonNode)
     * @see #sanitizeFieldName(String)
     */
    private void addAllFieldsFlat(SolrInputDocument doc, JsonNode node, String prefix) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = sanitizeFieldName(prefix + field.getKey());
            processFieldValue(doc, field.getValue(), fieldName);
        }
    }

    /**
     * Processes the provided field value and adds it to the given SolrInputDocument.
     * Handles cases where the field value is an array, object, or a simple value.
     *
     * @param doc       the SolrInputDocument to which the field value will be added
     * @param value     the JsonNode representing the field value to be processed
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
     * Processes a JSON array field and adds its non-object elements to the specified field
     * in the given SolrInputDocument.
     *
     * @param doc        the SolrInputDocument to which the processed field will be added
     * @param arrayValue the JSON array node to process
     * @param fieldName  the name of the field in the SolrInputDocument to which the array values will be added
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
     * <p>This method provides type-aware conversion of JSON values to their corresponding
     * Java types, ensuring that Solr receives properly typed data for optimal field
     * type detection and indexing performance.</p>
     *
     * <p><strong>Supported Type Conversions:</strong></p>
     * <ul>
     *   <li><strong>Boolean</strong>: JSON boolean → Java Boolean</li>
     *   <li><strong>Integer</strong>: JSON number (int range) → Java Integer</li>
     *   <li><strong>Long</strong>: JSON number (long range) → Java Long</li>
     *   <li><strong>Double</strong>: JSON number (decimal) → Java Double</li>
     *   <li><strong>String</strong>: All other values → Java String</li>
     * </ul>
     *
     * @param value the JsonNode value to convert
     * @return the converted Java object with appropriate type
     * @see JsonNode
     */
    private Object convertJsonValue(JsonNode value) {
        if (value.isBoolean()) return value.asBoolean();
        if (value.isInt()) return value.asInt();
        if (value.isDouble()) return value.asDouble();
        if (value.isLong()) return value.asLong();
        return value.asText();
    }

    /**
     * Sanitizes field names to ensure compatibility with Solr field naming requirements.
     *
     * <p>Solr has specific requirements for field names that must be met to ensure proper
     * indexing and searching functionality. This method transforms arbitrary JSON field
     * names into Solr-compliant identifiers.</p>
     *
     * <p><strong>Sanitization Rules:</strong></p>
     * <ul>
     *   <li><strong>Case Conversion</strong>: All characters converted to lowercase</li>
     *   <li><strong>Character Replacement</strong>: Non-alphanumeric characters replaced with underscores</li>
     *   <li><strong>Edge Trimming</strong>: Leading and trailing underscores removed</li>
     *   <li><strong>Duplicate Compression</strong>: Multiple consecutive underscores collapsed to single</li>
     * </ul>
     *
     * <p><strong>Example Transformations:</strong></p>
     * <ul>
     *   <li>"User-Name" → "user_name"</li>
     *   <li>"product.price" → "product_price"</li>
     *   <li>"__field__name__" → "field_name"</li>
     *   <li>"Field123@Test" → "field123_test"</li>
     * </ul>
     *
     * @param fieldName the original field name to sanitize
     * @return sanitized field name compatible with Solr requirements
     * @see <a href="https://solr.apache.org/guide/solr/latest/indexing-guide/fields.html">Solr Field Guide</a>
     */
    private String sanitizeFieldName(String fieldName) {
        // Remove or replace invalid characters for Solr field names
        return MULTIPLE_UNDERSCORES_PATTERN.matcher(
                UNDERSCORE_CLEANUP_PATTERN.matcher(
                        FIELD_SANITIZATION_PATTERN.matcher(fieldName.toLowerCase())
                                .replaceAll("_")
                ).replaceAll("")
        ).replaceAll("_");
    }
}