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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileDocumentCreatorTest {

	private FileDocumentCreator fileDocumentCreator;

	@BeforeEach
	void setUp() {
		fileDocumentCreator = new FileDocumentCreator();
	}

	@Test
	void create_withValidInput_shouldReturnDocumentWithContentAndFilename() {
		String content = "This is the text extracted from a PDF about Apache Solr.";

		List<SolrInputDocument> docs = fileDocumentCreator.create(content, "report.pdf");

		assertThat(docs).hasSize(1);
		SolrInputDocument doc = docs.getFirst();
		assertThat(doc.getFieldValue("content")).isEqualTo(content);
		assertThat(doc.getFieldValue("filename")).isEqualTo("report.pdf");
		assertThat(doc.getFieldValue("id")).isNotNull();
	}

	@Test
	void create_shouldGenerateUniqueIds() {
		String content = "Some document content.";

		List<SolrInputDocument> docs1 = fileDocumentCreator.create(content, "file1.txt");
		List<SolrInputDocument> docs2 = fileDocumentCreator.create(content, "file2.txt");

		assertThat(docs1.getFirst().getFieldValue("id")).isNotEqualTo(docs2.getFirst().getFieldValue("id"));
	}

	@Test
	void create_withNullContent_shouldThrowException() {
		assertThatThrownBy(() -> fileDocumentCreator.create(null, "test.txt"))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("null or empty");
	}

	@Test
	void create_withEmptyContent_shouldThrowException() {
		assertThatThrownBy(() -> fileDocumentCreator.create("", "test.txt"))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("null or empty");
	}

	@Test
	void create_withBlankContent_shouldThrowException() {
		assertThatThrownBy(() -> fileDocumentCreator.create("   ", "test.txt"))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("null or empty");
	}

	@Test
	void create_withNullFilename_shouldThrowException() {
		assertThatThrownBy(() -> fileDocumentCreator.create("Some content", null))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("null or empty");
	}

	@Test
	void create_withEmptyFilename_shouldThrowException() {
		assertThatThrownBy(() -> fileDocumentCreator.create("Some content", ""))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("null or empty");
	}

	@Test
	void create_withOversizedContent_shouldThrowException() {
		String largeContent = "x".repeat(11 * 1024 * 1024);

		assertThatThrownBy(() -> fileDocumentCreator.create(largeContent, "large.pdf"))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("too large");
	}

	@Test
	void create_withMultilineContent_shouldPreserveContent() {
		String content = """
				Chapter 1: Introduction

				Apache Solr is an open source search platform.
				It provides full-text search, faceting, and more.

				Chapter 2: Architecture
				""";

		List<SolrInputDocument> docs = fileDocumentCreator.create(content, "book.docx");

		assertThat(docs).hasSize(1);
		assertThat(docs.getFirst().getFieldValue("content").toString()).contains("Chapter 1");
		assertThat(docs.getFirst().getFieldValue("content").toString()).contains("Architecture");
	}

}
