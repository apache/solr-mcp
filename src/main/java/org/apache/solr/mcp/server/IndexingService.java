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

@Service
public class IndexingService {

    private final SolrClient solrClient;
    private final SolrConfigurationProperties solrConfigurationProperties;

    public IndexingService(SolrClient solrClient,
                           SolrConfigurationProperties solrConfigurationProperties) {
        this.solrClient = solrClient;
        this.solrConfigurationProperties = solrConfigurationProperties;
    }

    @Tool(name = "index_documents", description = "Index documents from json String into Solr collection")
    public void indexDocuments(
            @ToolParam(description = "Solr collection to index into")String collection,
            @ToolParam(description = "JSON string containing documents to index") String json) throws Exception {
        List<SolrInputDocument> schemalessDoc = createSchemalessDocuments(json);
        indexDocuments(collection, schemalessDoc);
    }

    // 5. SCHEMA-LESS APPROACH - Let Solr auto-detect everything
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

    // METHOD FOR INDEXING MAP OBJECTS (for tests)
    public boolean indexMapDocuments(String collection, List<Map<String, Object>> documents) throws Exception {
        List<SolrInputDocument> solrDocs = new ArrayList<>();
        for (Map<String, Object> doc : documents) {
            SolrInputDocument solrDoc = new SolrInputDocument();
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                solrDoc.addField(entry.getKey(), entry.getValue());
            }
            solrDocs.add(solrDoc);
        }
        int successCount = indexDocuments(collection, solrDocs);
        return successCount > 0;
    }

    // BATCH INDEXING WITH ERROR HANDLING
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

    private Object convertJsonValue(JsonNode value) {
        if (value.isBoolean()) return value.asBoolean();
        if (value.isInt()) return value.asInt();
        if (value.isDouble()) return value.asDouble();
        if (value.isLong()) return value.asLong();
        return value.asText();
    }

    private String sanitizeFieldName(String fieldName) {
        // Remove or replace invalid characters for Solr field names
        return fieldName.toLowerCase()
                .replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_{2,}", "_");
    }
}
