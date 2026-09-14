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

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

/**
 * Front matter is YAML, so it is parsed by a YAML parser. These cases are the
 * ones a line-by-line reader gets wrong: quoted scalars containing the
 * delimiters, flow and block sequences, and nested mappings. Scalars stay the
 * text as written; Solr's schema guessing types them.
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
}
