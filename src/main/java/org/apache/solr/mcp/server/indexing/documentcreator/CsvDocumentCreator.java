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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for processing CSV documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of CSV documents into Solr-compatible format using a
 * schema-less approach where Solr automatically detects field types.
 */
@Component
public class CsvDocumentCreator implements SolrDocumentCreator {

    private static final int MAX_INPUT_SIZE_BYTES = 10 * 1024 * 1024;

    /**
     * Creates a list of schema-less SolrInputDocument objects from a CSV string.
     *
     * <p>This method implements a flexible document conversion strategy that allows Solr to
     * automatically detect field types without requiring predefined schema configuration. It
     * processes CSV data by using the first row as field headers and converting each subsequent row
     * into a document.
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
     * <p><strong>CSV Processing Rules:</strong>
     *
     * <ul>
     *   <li><strong>Header Row</strong>: First row defines field names, automatically sanitized
     *   <li><strong>Empty Values</strong>: Ignored and not indexed
     *   <li><strong>Type Detection</strong>: Solr handles numeric, boolean, and string types
     *       automatically
     *   <li><strong>Field Sanitization</strong>: Column names cleaned for Solr compatibility
     * </ul>
     *
     * <p><strong>Field Name Sanitization:</strong>
     *
     * <p>Field names are automatically sanitized to ensure Solr compatibility by removing special
     * characters and converting to lowercase with underscore separators.
     *
     * <p><strong>Example Transformation:</strong>
     *
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
     * @throws DocumentProcessingException if CSV parsing fails, input validation fails, or the
     *     structure is invalid
     * @see SolrInputDocument
     * @see FieldNameSanitizer#sanitizeFieldName(String)
     */
    public List<SolrInputDocument> create(String csv) throws DocumentProcessingException {
        if (csv.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_SIZE_BYTES) {
            throw new DocumentProcessingException(
                    "Input too large: exceeds maximum size of " + MAX_INPUT_SIZE_BYTES + " bytes");
        }

        List<SolrInputDocument> documents = new ArrayList<>();

        try (CSVParser parser =
                     new CSVParser(
                             new StringReader(csv),
                             CSVFormat.Builder.create().setHeader().setTrim(true).build())) {
            List<String> headers = new ArrayList<>(parser.getHeaderNames());
            headers.replaceAll(FieldNameSanitizer::sanitizeFieldName);

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
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to parse CSV document", e);
        }

        return documents;
    }
}
