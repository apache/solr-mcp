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
package org.apache.solr.mcp.server.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test for file document indexing through
 * {@link IndexingDocumentCreator}.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class FileIndexingTest {

	@Autowired
	private IndexingDocumentCreator indexingDocumentCreator;

	@Test
	void testCreateSchemalessDocumentsFromFile() throws Exception {
		String content = "This is the text extracted from a PDF about Apache Solr full-text search.";

		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromFile(content,
				"test-document.pdf");

		assertThat(documents).hasSize(1);

		SolrInputDocument doc = documents.getFirst();
		assertThat(doc.getFieldValue("content")).isEqualTo(content);
		assertThat(doc.getFieldValue("filename")).isEqualTo("test-document.pdf");
		assertThat(doc.getFieldValue("id")).isNotNull();
	}

	@Test
	void testCreateSchemalessDocumentsFromFileWithNullContent() {
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromFile(null, "test.txt"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null or empty");
	}

	@Test
	void testCreateSchemalessDocumentsFromFileWithNullFilename() {
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromFile("content", null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null or empty");
	}

	@Test
	void testCreateSchemalessDocumentsFromFileWithInvalidContent() {
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromFile("   ", "test.txt"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null or empty");
	}

	@Test
	void testCreateSchemalessDocumentsFromFilePreservesMultilineContent() throws Exception {
		String content = """
				Chapter 1: Introduction to Search

				Apache Solr provides distributed indexing, replication, and
				load-balanced querying. It is designed for scalability and
				fault tolerance.

				Chapter 2: Getting Started
				""";

		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromFile(content,
				"guide.docx");

		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("content").toString()).contains("Chapter 1");
		assertThat(documents.getFirst().getFieldValue("content").toString()).contains("scalability");
	}

}
