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
import org.junit.jupiter.api.Test;

/**
 * Front matter is YAML, so it is parsed by a YAML parser. These cases are the
 * ones a line-by-line reader gets wrong: quoted scalars containing the
 * delimiters, flow and block sequences, and nested mappings. Scalars stay the
 * text as written; Solr's schema guessing types them.
 *
 * <p>
 * The remaining cases pin record splitting: a file holding several front matter
 * blocks yields one document per block, while everything that is not
 * unambiguously a second record keeps yielding exactly one document.
 */
class MarkdownDocumentCreatorTest {

	private final MarkdownDocumentCreator creator = new MarkdownDocumentCreator();

	@Test
	void frontMatterIsParsedAsYaml() {
		SolrInputDocument doc = creator.create("""
				---
				id: show-1
				title: "Star Wars: Andor, Season 2"
				genres: [Sci-Fi, "Drama, Political"]
				cast:
				  - Diego Luna
				  - Stellan Skarsgård
				ratings:
				  imdb: 8.4
				  age: TV-14
				seasons: 2
				ongoing: false
				released: 2022-09-21
				empty:
				---
				# Andor

				Prequel to Rogue One.
				""").getFirst();

		assertThat(doc.getFieldValue("id")).isEqualTo("show-1");
		assertThat(doc.getFieldValue("title")).isEqualTo("Star Wars: Andor, Season 2");
		assertThat(doc.getFieldValues("genres")).containsExactly("Sci-Fi", "Drama, Political");
		assertThat(doc.getFieldValues("cast")).containsExactly("Diego Luna", "Stellan Skarsgård");
		assertThat(doc.getFieldValue("ratings_imdb")).isEqualTo("8.4");
		assertThat(doc.getFieldValue("ratings_age")).isEqualTo("TV-14");
		assertThat(doc.getFieldValue("seasons")).isEqualTo("2");
		assertThat(doc.getFieldValue("ongoing")).isEqualTo("false");
		assertThat(doc.getFieldValue("released")).isEqualTo("2022-09-21");
		assertThat(doc.getFieldNames()).doesNotContain("empty");
		assertThat(doc.getFieldValue("content")).asString().contains("Prequel").doesNotContain("show-1");
	}

	@Test
	void invalidYamlIsReported() {
		assertThatThrownBy(() -> creator.create("---\ntitle: [unclosed\n---\nbody\n"))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("YAML");
	}

	@Test
	void documentWithoutFrontMatterIsUnchanged() {
		SolrInputDocument doc = creator.create("# Just a heading\n\nBody text.\n").getFirst();

		assertThat(doc.getFieldValue("title")).isEqualTo("Just a heading");
		assertThat(doc.getFieldValue("content")).asString().contains("Body text");
		assertThat(doc.getFieldValue("id")).isNotNull();
	}

	@Test
	void eachFrontMatterBlockBecomesItsOwnDocument() {
		List<SolrInputDocument> docs = creator.create("""
				---
				id: show-1
				title: First
				---

				# First

				Body of the first show.

				---
				id: show-2
				title: Second
				genres: [Drama]
				---

				# Second

				Body of the second show.
				""");

		assertThat(docs).hasSize(2);
		assertThat(docs.getFirst().getFieldValue("id")).isEqualTo("show-1");
		assertThat(docs.getFirst().getFieldValue("title")).isEqualTo("First");
		assertThat(docs.getFirst().getFieldValue("content")).asString().contains("first show")
				.doesNotContain("second show").doesNotContain("show-2");
		assertThat(docs.getLast().getFieldValue("id")).isEqualTo("show-2");
		assertThat(docs.getLast().getFieldValues("genres")).containsExactly("Drama");
		assertThat(docs.getLast().getFieldValue("content")).asString().contains("second show");
	}

	@Test
	void aSingleRecordStillYieldsOneDocument() {
		assertThat(creator.create("---\nid: only\n---\n\n# Only\n\nBody.\n")).hasSize(1);
	}

	/**
	 * A thematic break is not a record boundary: splitting only ever engages when a
	 * {@code ---} line opens something that parses as a YAML mapping.
	 */
	@Test
	void aThematicBreakDoesNotSplitTheDocument() {
		List<SolrInputDocument> docs = creator.create("""
				---
				id: show-1
				title: First
				---

				# First

				Body above the rule.

				---

				Body below the rule.
				""");

		assertThat(docs).hasSize(1);
		assertThat(docs.getFirst().getFieldValue("content")).asString().contains("above the rule")
				.contains("below the rule");
	}

	/**
	 * Splitting engages only for documents that themselves open with front matter,
	 * so prose that happens to contain a rule is never split.
	 */
	@Test
	void aDocumentWithoutFrontMatterIsNeverSplit() {
		List<SolrInputDocument> docs = creator.create("""
				# Heading

				Body text.

				---
				id: not-front-matter
				---

				More body text.
				""");

		assertThat(docs).hasSize(1);
	}
}
