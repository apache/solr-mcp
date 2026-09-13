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

/**
 * Interface defining the contract for creating SolrInputDocument objects from
 * various data formats.
 *
 * <p>
 * This interface provides a unified abstraction for converting different
 * document formats (JSON, CSV, XML, etc.) into Solr-compatible
 * SolrInputDocument objects. Implementations handle format-specific parsing and
 * field sanitization to ensure proper Solr indexing.
 *
 * <p>
 * <strong>Design Principles:</strong>
 *
 * <ul>
 * <li><strong>Format Agnostic</strong>: Common interface for all document types
 * <li><strong>Schema-less Processing</strong>: Supports dynamic field creation
 * without predefined schema
 * <li><strong>Error Handling</strong>: Consistent exception handling across
 * implementations
 * <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for
 * Solr compatibility
 * </ul>
 *
 * <p>
 * <strong>Implementation Guidelines:</strong>
 *
 * <ul>
 * <li>Reject blank input via {@link #requireContent(String, String)} before
 * parsing
 * <li>Sanitize field names using {@link FieldNameSanitizer}
 * <li>Preserve original data types where possible
 * <li>Throw {@link DocumentProcessingException} for processing errors
 * </ul>
 *
 * <p>
 * <strong>Usage Example:</strong>
 *
 * <pre>{@code
 * SolrDocumentCreator creator = new JsonDocumentCreator(new ObjectMapper());
 * String jsonData = "[{\"title\":\"Document 1\",\"content\":\"Content here\"}]";
 * List<SolrInputDocument> documents = creator.create(jsonData);
 * }</pre>
 *
 * @see SolrInputDocument
 * @see DocumentProcessingException
 * @see FieldNameSanitizer
 */
public interface SolrDocumentCreator {

	/**
	 * Creates a list of SolrInputDocument objects from the provided content string.
	 *
	 * <p>
	 * This method parses the input content according to the specific format handled
	 * by the implementing class (JSON, CSV, XML, etc.) and converts it into a list
	 * of SolrInputDocument objects ready for indexing.
	 *
	 * <p>
	 * <strong>Processing Behavior:</strong>
	 *
	 * <ul>
	 * <li><strong>Field Sanitization</strong>: All field names are sanitized for
	 * Solr compatibility
	 * <li><strong>Type Preservation</strong>: Original data types are maintained
	 * where possible
	 * <li><strong>Multiple Documents</strong>: Single content string may produce
	 * multiple documents
	 * <li><strong>Error Handling</strong>: Invalid content results in
	 * DocumentProcessingException
	 * </ul>
	 *
	 * <p>
	 * <strong>Input Validation:</strong>
	 *
	 * <ul>
	 * <li>Blank content throws DocumentProcessingException (see
	 * {@link #requireContent(String, String)})
	 * <li>Malformed content throws DocumentProcessingException
	 * </ul>
	 *
	 * @param content
	 *            the content string to be parsed and converted to SolrInputDocument
	 *            objects. The format depends on the implementing class (JSON array,
	 *            CSV data, XML, etc.)
	 * @return a list of SolrInputDocument objects created from the parsed content
	 * @throws DocumentProcessingException
	 *             if the content is blank, or cannot be parsed or converted due to
	 *             format errors, invalid structure, or processing failures
	 */
	List<SolrInputDocument> create(String content) throws DocumentProcessingException;

	/**
	 * Rejects blank content with one message shape shared by every format.
	 *
	 * <p>
	 * Only blankness is checked. The creators are {@code @NullMarked}, so a null
	 * argument is a caller's contract violation, not an input to validate.
	 *
	 * @param content
	 *            the raw input
	 * @param format
	 *            the format name used in the message, for example {@code "JSON"}
	 * @throws DocumentProcessingException
	 *             if {@code content} is empty or whitespace only
	 */
	static void requireContent(String content, String format) throws DocumentProcessingException {
		if (content.isBlank()) {
			throw new DocumentProcessingException(format + " input cannot be empty");
		}
	}
}
