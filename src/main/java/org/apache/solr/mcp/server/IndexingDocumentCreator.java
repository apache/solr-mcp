package org.apache.solr.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Spring Service responsible for creating SolrInputDocument objects from various data formats.
 *
 * <p>This service handles the conversion of JSON and CSV documents into Solr-compatible format
 * using a schema-less approach where Solr automatically detects field types, eliminating the need for
 * predefined schema configuration.</p>
 *
 * <p><strong>Core Features:</strong></p>
 * <ul>
 *   <li><strong>Schema-less Document Creation</strong>: Automatic field type detection by Solr</li>
 *   <li><strong>JSON Processing</strong>: Support for complex nested JSON documents</li>
 *   <li><strong>CSV Processing</strong>: Support for comma-separated value files with headers</li>
 *   <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for Solr compatibility</li>
 * </ul>
 *
 * @author Solr MCP Server
 * @version 1.0
 * @see SolrInputDocument
 * @see IndexingService
 * @since 1.0
 */
@Service
public class IndexingDocumentCreator {

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
     * @throws Exception if JSON parsing fails or the structure is invalid
     * @see SolrInputDocument
     * @see #addAllFieldsFlat(SolrInputDocument, JsonNode, String)
     * @see #sanitizeFieldName(String)
     */
    public List<SolrInputDocument> createSchemalessDocumentsFromJson(String json) throws Exception {
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
     * Creates a list of schema-less SolrInputDocument objects from a CSV string.
     *
     * <p>This method implements a flexible document conversion strategy that allows Solr
     * to automatically detect field types without requiring predefined schema configuration.
     * It processes CSV data by using the first row as field headers and converting each
     * subsequent row into a document.</p>
     *
     * <p><strong>Schema-less Benefits:</strong></p>
     * <ul>
     *   <li><strong>Flexibility</strong>: No need to predefine field types in schema</li>
     *   <li><strong>Rapid Prototyping</strong>: Quick iteration on document structures</li>
     *   <li><strong>Type Detection</strong>: Solr automatically infers optimal field types</li>
     *   <li><strong>Dynamic Fields</strong>: Support for varying document structures</li>
     * </ul>
     *
     * <p><strong>CSV Processing Rules:</strong></p>
     * <ul>
     *   <li><strong>Header Row</strong>: First row defines field names, automatically sanitized</li>
     *   <li><strong>Empty Values</strong>: Ignored and not indexed</li>
     *   <li><strong>Type Detection</strong>: Solr handles numeric, boolean, and string types automatically</li>
     *   <li><strong>Field Sanitization</strong>: Column names cleaned for Solr compatibility</li>
     * </ul>
     *
     * <p><strong>Field Name Sanitization:</strong></p>
     * <p>Field names are automatically sanitized to ensure Solr compatibility by removing
     * special characters and converting to lowercase with underscore separators.</p>
     *
     * <p><strong>Example Transformation:</strong></p>
     * <pre>{@code
     * Input CSV:
     * id,name,price,inStock
     * 123,Product A,19.99,true
     *
     * Output Document:
     * {id:"123", name:"Product A", price:"19.99", instock:"true"}
     * }</pre>
     *
     * @param csv CSV string containing document data (first row must be headers)
     * @return list of SolrInputDocument objects ready for indexing
     * @throws Exception if CSV parsing fails or the structure is invalid
     * @see SolrInputDocument
     * @see #sanitizeFieldName(String)
     */
    public List<SolrInputDocument> createSchemalessDocumentsFromCsv(String csv) throws Exception {
        List<SolrInputDocument> documents = new ArrayList<>();

        CSVParser parser = new CSVParser(new StringReader(csv),
                CSVFormat.Builder.create().setHeader().setTrim(true).build());
        List<String> headers = new ArrayList<>(parser.getHeaderNames());
        headers.replaceAll(this::sanitizeFieldName);

        for (CSVRecord csvRecord : parser) {
            if (csvRecord.size() == 0) {
                continue; // Skip empty lines
            }

            SolrInputDocument doc = new SolrInputDocument();

            for (int i = 0; i < headers.size() && i < csvRecord.size(); i++) {
                String value = csvRecord.get(i);
                if (!value.isEmpty()) {
                    doc.addField(headers.get(i), value);
                }
            }

            documents.add(doc);
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
        return fieldName.toLowerCase()
                .replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_{2,}", "_");
    }
}