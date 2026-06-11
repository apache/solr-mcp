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
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Test class for markdown indexing functionality in IndexingService.
 *
 * <p>
 * This test verifies that the IndexingService can correctly parse markdown
 * content — including YAML front matter, headings, and body text — and convert
 * it into SolrInputDocument objects using the schema-less approach.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class MarkdownIndexingTest {

	@Autowired
	private IndexingDocumentCreator indexingDocumentCreator;

	@Test
	void testCreateSchemalessDocumentsFromMarkdownWithFrontMatter() throws Exception {
		// Given
		String markdown = """
				---
				author: George R.R. Martin
				genre: fantasy
				published: 1996
				---
				# A Game of Thrones

				## Synopsis

				The first book in A Song of Ice and Fire.

				## Reception

				Widely acclaimed.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);

		SolrInputDocument doc = documents.getFirst();
		assertThat(doc.getFieldValue("author")).isEqualTo("George R.R. Martin");
		assertThat(doc.getFieldValue("genre")).isEqualTo("fantasy");
		assertThat(doc.getFieldValue("published")).isEqualTo("1996");

		// Title comes from the first level-1 heading (no front matter title)
		assertThat(doc.getFieldValue("title")).isEqualTo("A Game of Thrones");

		// All heading texts are collected into a multi-valued field
		assertThat(doc.getFieldValues("headings")).containsExactly("A Game of Thrones", "Synopsis", "Reception");

		// Body text is searchable and excludes front matter
		String content = (String) doc.getFieldValue("content");
		assertThat(content).contains("The first book in A Song of Ice and Fire.");
		assertThat(content).contains("Widely acclaimed.");
		assertThat(content).doesNotContain("George R.R. Martin");
	}

	@Test
	void testFrontMatterTitleWinsOverFirstHeading() throws Exception {
		// Given
		String markdown = """
				---
				title: Front Matter Title
				---
				# Heading Title

				Some body text.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("title")).isEqualTo("Front Matter Title");
	}

	@Test
	void testCreateSchemalessDocumentsFromMarkdownWithoutFrontMatter() throws Exception {
		// Given
		String markdown = """
				# Getting Started

				Install the package and run the server.

				## Configuration

				Set the `SOLR_URL` environment variable.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);

		SolrInputDocument doc = documents.getFirst();
		assertThat(doc.getFieldValue("title")).isEqualTo("Getting Started");
		assertThat(doc.getFieldValues("headings")).containsExactly("Getting Started", "Configuration");

		String content = (String) doc.getFieldValue("content");
		assertThat(content).contains("Install the package and run the server.");
		assertThat(content).contains("SOLR_URL");
	}

	@Test
	void testPlainTextMarkdownWithoutHeadings() throws Exception {
		// Given
		String markdown = "Just a plain paragraph of text without any structure.";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);

		SolrInputDocument doc = documents.getFirst();
		assertThat(doc.getFieldValue("title")).isNull();
		assertThat(doc.getFieldValues("headings")).isNull();
		assertThat(doc.getFieldValue("content")).isEqualTo("Just a plain paragraph of text without any structure.");
	}

	@Test
	void testFrontMatterFieldNamesAreSanitized() throws Exception {
		// Given
		String markdown = """
				---
				Created-By: Jane Doe
				last.updated: 2026-01-01
				---
				# Doc

				Body.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);

		SolrInputDocument doc = documents.getFirst();
		assertThat(doc.getFieldValue("created_by")).isEqualTo("Jane Doe");
		assertThat(doc.getFieldValue("last_updated")).isEqualTo("2026-01-01");
	}

	@Test
	void testFrontMatterListValuesBecomeMultiValuedFields() throws Exception {
		// Given
		String markdown = """
				---
				tags: [search, solr, mcp]
				---
				# Tagged Document

				Body.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValues("tags")).containsExactly("search", "solr", "mcp");
	}

	@Test
	void testFrontMatterBlockListValuesBecomeMultiValuedFields() throws Exception {
		// Given
		String markdown = """
				---
				tags:
				  - search
				  - solr
				---
				# Tagged Document

				Body.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValues("tags")).containsExactly("search", "solr");
	}

	@Test
	void testFrontMatterIdIsUsedAsDocumentId() throws Exception {
		// Given
		String markdown = """
				---
				id: doc-42
				---
				# Identified Document

				Body.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("id")).isEqualTo("doc-42");
	}

	@Test
	void testGeneratedIdIsStableForSameContent() throws Exception {
		// Given
		String markdown = "# Stable\n\nSame content, same id.";

		// When
		List<SolrInputDocument> first = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);
		List<SolrInputDocument> second = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);
		List<SolrInputDocument> other = indexingDocumentCreator
				.createSchemalessDocumentsFromMarkdown("# Different\n\nOther content.");

		// Then: re-indexing the same markdown must overwrite the same document
		Object firstId = first.getFirst().getFieldValue("id");
		assertThat(firstId).isNotNull();
		assertThat(firstId).isEqualTo(second.getFirst().getFieldValue("id"));
		assertThat(firstId).isNotEqualTo(other.getFirst().getFieldValue("id"));
	}

	@Test
	void testEmptyMarkdownThrowsException() {
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(""))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("cannot be null or empty");
	}

	@Test
	void testMarkdownFormattingIsStrippedFromContent() throws Exception {
		// Given
		String markdown = """
				# Formatted

				Some **bold** and *italic* text with a [link](https://solr.apache.org) and `inline code`.
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);

		// Then
		assertThat(documents).hasSize(1);

		String content = (String) documents.getFirst().getFieldValue("content");
		assertThat(content).contains("bold");
		assertThat(content).contains("italic");
		assertThat(content).contains("inline code");
		assertThat(content).doesNotContain("**");
		assertThat(content).doesNotContain("](");
	}
}
