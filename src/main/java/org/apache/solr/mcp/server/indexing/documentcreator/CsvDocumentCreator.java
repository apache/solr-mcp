package org.apache.solr.mcp.server.indexing.documentcreator;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for processing CSV documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of CSV documents into Solr-compatible format
 * using a schema-less approach where Solr automatically detects field types.</p>
 */
@Component
public class CsvDocumentCreator implements SolrDocumentCreator {

    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern LEADING_TRAILING_UNDERSCORES_PATTERN = Pattern.compile("^_+|_+$");
    private static final Pattern MULTIPLE_UNDERSCORES_PATTERN = Pattern.compile("_+");

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
     * @throws IOException if CSV parsing fails or the structure is invalid
     * @see SolrInputDocument
     * @see #sanitizeFieldName(String)
     */
    public List<SolrInputDocument> create(String csv) throws IOException {
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
     * Sanitizes field names to ensure they are compatible with Solr's field naming requirements.
     *
     * <p>This method ensures that field names:</p>
     * <ul>
     *   <li>Contain only alphanumeric characters and underscores</li>
     *   <li>Are lowercase for consistency</li>
     *   <li>Have special characters replaced with underscores</li>
     *   <li>Don't have leading/trailing underscores or multiple consecutive underscores</li>
     * </ul>
     *
     * @param fieldName the original field name to sanitize
     * @return a sanitized field name safe for use in Solr
     */
    private String sanitizeFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return "field";
        }

        // Convert to lowercase and replace invalid characters with underscores
        String sanitized = FIELD_NAME_PATTERN.matcher(fieldName.toLowerCase()).replaceAll("_");

        // Remove leading/trailing underscores and collapse multiple underscores
        sanitized = LEADING_TRAILING_UNDERSCORES_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = MULTIPLE_UNDERSCORES_PATTERN.matcher(sanitized).replaceAll("_");

        // If the result is empty after sanitization, provide a default name
        if (sanitized.isEmpty()) {
            return "field";
        }

        // Ensure the field name doesn't start with a number (Solr requirement)
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "field_" + sanitized;
        }

        return sanitized;
    }
}