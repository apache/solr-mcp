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

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.springframework.stereotype.Service;

/**
 * Spring Service responsible for creating SolrInputDocument objects from various data formats.
 *
 * <p>This service handles the conversion of JSON, CSV, and XML documents into Solr-compatible
 * format using a schema-less approach where Solr automatically detects field types, eliminating the
 * need for predefined schema configuration.
 *
 * <p><strong>Core Features:</strong>
 *
 * <ul>
 *   <li><strong>Schema-less Document Creation</strong>: Automatic field type detection by Solr
 *   <li><strong>JSON Processing</strong>: Support for complex nested JSON documents
 *   <li><strong>CSV Processing</strong>: Support for comma-separated value files with headers
 *   <li><strong>XML Processing</strong>: Support for XML documents with element flattening and
 *       attribute handling
 *   <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for Solr
 *       compatibility
 * </ul>
 *
 * @version 0.0.1
 * @see SolrInputDocument
 * @see IndexingService
 * @since 0.0.1
 */
@Service
public class IndexingDocumentCreator {

    private static final int MAX_XML_SIZE_BYTES = 10 * 1024 * 1024; // 10MB limit

    private final XmlDocumentCreator xmlDocumentCreator;

    private final CsvDocumentCreator csvDocumentCreator;

    private final JsonDocumentCreator jsonDocumentCreator;

    public IndexingDocumentCreator(
            XmlDocumentCreator xmlDocumentCreator,
            CsvDocumentCreator csvDocumentCreator,
            JsonDocumentCreator jsonDocumentCreator) {
        this.xmlDocumentCreator = xmlDocumentCreator;
        this.csvDocumentCreator = csvDocumentCreator;
        this.jsonDocumentCreator = jsonDocumentCreator;
    }

    /**
     * Creates a list of schema-less SolrInputDocument objects from a JSON string.
     *
     * <p>This method delegates JSON processing to the JsonDocumentProcessor utility class.
     *
     * @param json JSON string containing document data (must be an array)
     * @return list of SolrInputDocument objects ready for indexing
     * @throws DocumentProcessingException if JSON parsing fails or the structure is invalid
     * @see JsonDocumentCreator
     */
    public List<SolrInputDocument> createSchemalessDocumentsFromJson(String json)
            throws DocumentProcessingException {
        return jsonDocumentCreator.create(json);
    }

    /**
     * Creates a list of schema-less SolrInputDocument objects from a CSV string.
     *
     * <p>This method delegates CSV processing to the CsvDocumentProcessor utility class.
     *
     * @param csv CSV string containing document data (first row must be headers)
     * @return list of SolrInputDocument objects ready for indexing
     * @throws DocumentProcessingException if CSV parsing fails or the structure is invalid
     * @see CsvDocumentCreator
     */
    public List<SolrInputDocument> createSchemalessDocumentsFromCsv(String csv)
            throws DocumentProcessingException {
        return csvDocumentCreator.create(csv);
    }

    /**
     * Creates a list of schema-less SolrInputDocument objects from an XML string.
     *
     * <p>This method delegates XML processing to the XmlDocumentProcessor utility class.
     *
     * @param xml XML string containing document data
     * @return list of SolrInputDocument objects ready for indexing
     * @throws DocumentProcessingException if XML parser configuration fails
     * @see XmlDocumentCreator
     */
    public List<SolrInputDocument> createSchemalessDocumentsFromXml(String xml)
            throws DocumentProcessingException {

        // Input validation
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("XML input cannot be null or empty");
        }

        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        if (xmlBytes.length > MAX_XML_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "XML document too large: "
                            + xmlBytes.length
                            + " bytes (max: "
                            + MAX_XML_SIZE_BYTES
                            + ")");
        }

        return xmlDocumentCreator.create(xml);
    }
}
