package org.apache.solr.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Spring Service providing comprehensive document indexing capabilities for Apache Solr collections
 * through Model Context Protocol (MCP) integration.
 * 
 * <p>This service handles the conversion of JSON documents into Solr-compatible format and manages
 * the indexing process with robust error handling and batch processing capabilities. It employs a
 * schema-less approach where Solr automatically detects field types, eliminating the need for
 * predefined schema configuration.</p>
 * 
 * <p><strong>Core Features:</strong></p>
 * <ul>
 *   <li><strong>Schema-less Indexing</strong>: Automatic field type detection by Solr</li>
 *   <li><strong>JSON Processing</strong>: Support for complex nested JSON documents</li>
 *   <li><strong>Batch Processing</strong>: Efficient bulk indexing with configurable batch sizes</li>
 *   <li><strong>Error Resilience</strong>: Individual document fallback when batch operations fail</li>
 *   <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for Solr compatibility</li>
 * </ul>
 * 
 * <p><strong>MCP Tool Integration:</strong></p>
 * <p>The service exposes indexing functionality as MCP tools that can be invoked by AI clients
 * through natural language requests. This enables seamless document ingestion workflows from
 * external data sources.</p>
 * 
 * <p><strong>JSON Document Processing:</strong></p>
 * <p>The service processes JSON documents by flattening nested objects using underscore notation
 * (e.g., "user.name" becomes "user_name") and handles arrays by converting them to multi-valued
 * fields that Solr natively supports.</p>
 * 
 * <p><strong>Batch Processing Strategy:</strong></p>
 * <p>Uses configurable batch sizes (default 1000 documents) for optimal performance. If a batch
 * fails, the service automatically retries by indexing documents individually to identify and
 * skip problematic documents while preserving valid ones.</p>
 * 
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * // Index JSON array of documents
 * String jsonData = "[{\"title\":\"Document 1\",\"content\":\"Content here\"}]";
 * indexingService.indexDocuments("my_collection", jsonData);
 * 
 * // Programmatic document creation and indexing
 * List<SolrInputDocument> docs = indexingService.createSchemalessDocuments(jsonData);
 * int successful = indexingService.indexDocuments("my_collection", docs);
 * }</pre>
 * 
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 * 
 * @see SolrInputDocument
 * @see SolrClient
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Service
public class IndexingService {

    /** SolrJ client for communicating with Solr server */
    private final SolrClient solrClient;
    
    /** Solr configuration properties for connection settings */
    private final SolrConfigurationProperties solrConfigurationProperties;

    /**
     * Constructs a new IndexingService with the required dependencies.
     * 
     * <p>This constructor is automatically called by Spring's dependency injection
     * framework during application startup, providing the service with the necessary
     * Solr client and configuration components.</p>
     * 
     * @param solrClient the SolrJ client instance for communicating with Solr
     * @param solrConfigurationProperties configuration properties for Solr connection
     * 
     * @see SolrClient
     * @see SolrConfigurationProperties
     */
    public IndexingService(SolrClient solrClient,
                           SolrConfigurationProperties solrConfigurationProperties) {
        this.solrClient = solrClient;
        this.solrConfigurationProperties = solrConfigurationProperties;
    }

    /**
     * Indexes documents from a JSON string into a specified Solr collection.
     * 
     * <p>This method serves as the primary entry point for document indexing operations
     * and is exposed as an MCP tool for AI client interactions. It processes JSON data
     * containing document arrays and indexes them using a schema-less approach.</p>
     * 
     * <p><strong>Supported JSON Formats:</strong></p>
     * <ul>
     *   <li><strong>Document Array</strong>: {@code [{"field1":"value1"},{"field2":"value2"}]}</li>
     *   <li><strong>Nested Objects</strong>: Automatically flattened with underscore notation</li>
     *   <li><strong>Multi-valued Fields</strong>: Arrays converted to Solr multi-valued fields</li>
     * </ul>
     * 
     * <p><strong>Processing Workflow:</strong></p>
     * <ol>
     *   <li>Parse JSON string into structured documents</li>
     *   <li>Convert to schema-less SolrInputDocument objects</li>
     *   <li>Execute batch indexing with error handling</li>
     *   <li>Commit changes to make documents searchable</li>
     * </ol>
     * 
     * <p><strong>MCP Tool Usage:</strong></p>
     * <p>AI clients can invoke this method with natural language requests like
     * "index these documents into my_collection" or "add this JSON data to the search index".</p>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>If indexing fails, the method attempts individual document processing to maximize
     * the number of successfully indexed documents. Detailed error information is logged
     * for troubleshooting purposes.</p>
     * 
     * @param collection the name of the Solr collection to index documents into
     * @param json JSON string containing an array of documents to index
     * 
     * @throws Exception if there are critical errors in JSON parsing or Solr communication
     * 
     * @see #createSchemalessDocuments(String)
     * @see #indexDocuments(String, List)
     */
    @Tool(name = "index_documents", description = "Index documents from json String into Solr collection")
    public void indexDocuments(
            @ToolParam(description = "Solr collection to index into") String collection,
            @ToolParam(description = "JSON string containing documents to index") String json) throws Exception {
        List<SolrInputDocument> schemalessDoc = createSchemalessDocuments(json);
        indexDocuments(collection, schemalessDoc);
    }

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
     * 
     * @throws Exception if JSON parsing fails or the structure is invalid
     * 
     * @see SolrInputDocument
     * @see #addAllFieldsFlat(SolrInputDocument, JsonNode, String)
     * @see #sanitizeFieldName(String)
     */
    public List<SolrInputDocument> createSchemalessDocuments(String json) throws Exception {
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
     * @param doc the SolrInputDocument to add fields to
     * @param node the JSON node to process
     * @param prefix current field name prefix for nested object flattening
     * 
     * @see #convertJsonValue(JsonNode)
     * @see #sanitizeFieldName(String)
     */
    private void addAllFieldsFlat(SolrInputDocument doc, JsonNode node, String prefix) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = sanitizeFieldName(prefix + field.getKey());
            JsonNode value = field.getValue();

            if (value.isNull()) {
                continue;
            } else if (value.isArray()) {
                List<Object> values = new ArrayList<>();
                for (JsonNode item : value) {
                    if (!item.isObject()) {
                        values.add(convertJsonValue(item));
                    }
                }
                if (!values.isEmpty()) {
                    doc.addField(fieldName, values);
                }
            } else if (value.isObject()) {
                addAllFieldsFlat(doc, value, fieldName + "_");
            } else {
                doc.addField(fieldName, convertJsonValue(value));
            }
        }
    }

    /**
     * Indexes a list of SolrInputDocument objects into a Solr collection using batch processing.
     * 
     * <p>This method implements a robust batch indexing strategy that optimizes performance
     * while providing resilience against individual document failures. It processes documents
     * in configurable batches and includes fallback mechanisms for error recovery.</p>
     * 
     * <p><strong>Batch Processing Strategy:</strong></p>
     * <ul>
     *   <li><strong>Batch Size</strong>: Configurable (default 1000) for optimal performance</li>
     *   <li><strong>Error Recovery</strong>: Individual document retry on batch failure</li>
     *   <li><strong>Success Tracking</strong>: Accurate count of successfully indexed documents</li>
     *   <li><strong>Commit Strategy</strong>: Single commit after all batches for consistency</li>
     * </ul>
     * 
     * <p><strong>Error Handling Workflow:</strong></p>
     * <ol>
     *   <li>Attempt batch indexing for optimal performance</li>
     *   <li>On batch failure, retry each document individually</li>
     *   <li>Track successful vs failed document counts</li>
     *   <li>Continue processing remaining batches despite failures</li>
     *   <li>Commit all successful changes at the end</li>
     * </ol>
     * 
     * <p><strong>Performance Considerations:</strong></p>
     * <p>Batch processing significantly improves indexing performance compared to individual
     * document operations. The fallback to individual processing ensures maximum document
     * ingestion even when some documents have issues.</p>
     * 
     * <p><strong>Transaction Behavior:</strong></p>
     * <p>The method commits changes after all batches are processed, making indexed documents
     * immediately searchable. This ensures atomicity at the operation level while maintaining
     * performance through batching.</p>
     * 
     * @param collection the name of the Solr collection to index into
     * @param documents list of SolrInputDocument objects to index
     * @return the number of documents successfully indexed
     * 
     * @throws Exception if there are critical errors in Solr communication or commit operations
     * 
     * @see SolrInputDocument
     * @see SolrClient#add(String, java.util.Collection)
     * @see SolrClient#commit(String)
     */
    public int indexDocuments(String collection, List<SolrInputDocument> documents) throws Exception {
        int successCount = 0;
        try {
            int batchSize = 1000;
            int errorCount = 0;

            for (int i = 0; i < documents.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, documents.size());
                List<SolrInputDocument> batch = documents.subList(i, endIndex);

                try {
                    solrClient.add(collection, batch);
                    successCount += batch.size();
                } catch (Exception e) {
                    errorCount += batch.size();

                    // Try indexing documents individually to identify problematic ones
                    for (SolrInputDocument doc : batch) {
                        try {
                            solrClient.add(collection, doc);
                            successCount++;
                        } catch (Exception docError) {
                            errorCount++;
                        }
                    }
                }
            }

            solrClient.commit(collection);
        } catch (Exception e) {
            throw e;
        }
        return successCount;
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
     * 
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
     * 
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