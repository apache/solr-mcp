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
import java.util.UUID;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;

/**
 * Creates a SolrInputDocument from text content extracted from a file.
 *
 * <p>
 * This creator handles documents uploaded through AI chat clients, where the
 * client has already extracted the text content from the original file (PDF,
 * Word, etc.). It produces a single SolrInputDocument containing the text
 * content and the original filename as metadata.
 *
 * <p>
 * This class does not implement {@link SolrDocumentCreator} because it requires
 * a filename parameter in addition to the content string.
 *
 * @see IndexingDocumentCreator#createSchemalessDocumentsFromFile(String,
 *      String)
 */
@Component
public class FileDocumentCreator {

	private static final int MAX_INPUT_SIZE_BYTES = 10 * 1024 * 1024;

	/**
	 * Creates a SolrInputDocument from the provided text content and filename.
	 *
	 * @param content
	 *            the text content extracted from the file
	 * @param filename
	 *            the original filename (stored as metadata for search and
	 *            filtering)
	 * @return a list containing a single SolrInputDocument
	 * @throws DocumentProcessingException
	 *             if the content is null, empty, or exceeds the size limit
	 */
	public List<SolrInputDocument> create(String content, String filename) throws DocumentProcessingException {
		if (content == null || content.isBlank()) {
			throw new DocumentProcessingException("File content cannot be null or empty");
		}
		if (filename == null || filename.isBlank()) {
			throw new DocumentProcessingException("Filename cannot be null or empty");
		}
		if (content.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_SIZE_BYTES) {
			throw new DocumentProcessingException(
					"Input too large: exceeds maximum size of " + MAX_INPUT_SIZE_BYTES + " bytes");
		}

		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", UUID.randomUUID().toString());
		doc.addField("content", content);
		doc.addField("filename", filename);
		return List.of(doc);
	}

}
