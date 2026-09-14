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

import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.springframework.stereotype.Service;

/**
 * Spring Service responsible for creating SolrInputDocument objects from
 * various data formats.
 *
 * <p>
 * This service handles the conversion of JSON and Markdown documents into
 * Solr-compatible format using a schema-less approach where Solr automatically
 * detects field types, eliminating the need for predefined schema
 * configuration. CSV and XML have no creator: those payloads are forwarded to
 * Solr's own update handlers.
 *
 * <p>
 * <strong>Core Features:</strong>
 *
 * <ul>
 * <li><strong>Schema-less Document Creation</strong>: Automatic field type
 * detection by Solr
 * <li><strong>JSON Processing</strong>: Support for complex nested JSON
 * documents
 * <li><strong>Markdown Processing</strong>: Support for markdown documents with
 * front matter, title, and heading extraction
 * <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for
 * Solr compatibility
 * </ul>
 *
 * @see SolrInputDocument
 * @see IndexingService
 */
@Service
public class IndexingDocumentCreator {

	private final JsonDocumentCreator jsonDocumentCreator;

	private final MarkdownDocumentCreator markdownDocumentCreator;

	/**
	 * Constructs the orchestrator with the per-format document creators. CSV and
	 * XML have no creator: those payloads go to Solr's own update handlers.
	 *
	 * @param jsonDocumentCreator
	 *            converts JSON input into {@code SolrInputDocument} batches
	 * @param markdownDocumentCreator
	 *            converts Markdown input into {@code SolrInputDocument} batches
	 */
	public IndexingDocumentCreator(JsonDocumentCreator jsonDocumentCreator,
			MarkdownDocumentCreator markdownDocumentCreator) {
		this.jsonDocumentCreator = jsonDocumentCreator;
		this.markdownDocumentCreator = markdownDocumentCreator;
	}

	/**
	 * Creates a list of schema-less SolrInputDocument objects from a JSON string.
	 *
	 * <p>
	 * This method delegates JSON processing to the JsonDocumentProcessor utility
	 * class.
	 *
	 * @param json
	 *            JSON string containing document data (must be an array)
	 * @return list of SolrInputDocument objects ready for indexing
	 * @throws DocumentProcessingException
	 *             if JSON parsing fails or the structure is invalid
	 * @see JsonDocumentCreator
	 */
	public List<SolrInputDocument> createSchemalessDocumentsFromJson(String json) throws DocumentProcessingException {
		return jsonDocumentCreator.create(json);
	}

	/**
	 * Creates a list of schema-less SolrInputDocument objects from a markdown
	 * string.
	 *
	 * <p>
	 * This method delegates markdown processing to the MarkdownDocumentCreator
	 * utility class.
	 *
	 * @param markdown
	 *            markdown string containing document content, optionally starting
	 *            with YAML front matter
	 * @return list of SolrInputDocument objects ready for indexing
	 * @throws DocumentProcessingException
	 *             if markdown parsing fails or input validation fails
	 * @see MarkdownDocumentCreator
	 */
	public List<SolrInputDocument> createSchemalessDocumentsFromMarkdown(String markdown)
			throws DocumentProcessingException {

		// Input validation
		if (markdown == null || markdown.trim().isEmpty()) {
			throw new DocumentProcessingException("Markdown input cannot be null or empty");
		}

		return markdownDocumentCreator.create(markdown);
	}
}
