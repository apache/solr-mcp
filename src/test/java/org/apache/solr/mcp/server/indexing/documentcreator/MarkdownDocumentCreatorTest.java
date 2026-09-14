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

import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;

/**
 * One Markdown string may carry many documents: a new document starts at every
 * YAML front-matter block. That lets a client send a whole dataset in one tool
 * call instead of one call per document, while a single file with one front
 * matter block behaves exactly as before.
 */
class MarkdownDocumentCreatorTest {

	private final MarkdownDocumentCreator creator = new MarkdownDocumentCreator();

	@Test
	void eachFrontMatterBlockStartsANewDocument() {
		String markdown = """
				---
				id: show-1
				title: Stranger Things
				genres:
				  - Sci-Fi
				  - Horror
				---

				# Stranger Things

				Kids in Indiana.

				---
				id: show-2
				title: Dark
				---

				# Dark

				A German town.

				---
				id: show-3
				title: Severance
				---
				Office workers.
				""";

		List<SolrInputDocument> documents = creator.create(markdown);

		assertThat(documents).hasSize(3);
		assertThat(documents).extracting(d -> d.getFieldValue("id")).containsExactly("show-1", "show-2", "show-3");
		assertThat(documents).extracting(d -> d.getFieldValue("title")).containsExactly("Stranger Things", "Dark",
				"Severance");
		assertThat(documents.getFirst().getFieldValues("genres")).containsExactly("Sci-Fi", "Horror");
		assertThat(documents.getFirst().getFieldValue("content")).asString().contains("Kids in Indiana")
				.doesNotContain("German");
		assertThat(documents.get(2).getFieldValue("content")).asString().contains("Office workers");
	}

	@Test
	void aThematicBreakInsideTheBodyDoesNotSplit() {
		String markdown = """
				---
				id: one
				---
				# Title

				First part.

				---

				Second part, after a horizontal rule, still the same document.
				""";

		List<SolrInputDocument> documents = creator.create(markdown);

		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("content")).asString().contains("First part")
				.contains("Second part");
	}

	@Test
	void generatedIdsAreDerivedPerDocument() {
		String markdown = """
				---
				title: A
				---
				Body A.

				---
				title: B
				---
				Body B.
				""";

		List<SolrInputDocument> documents = creator.create(markdown);

		assertThat(documents).hasSize(2);
		assertThat(documents.get(0).getFieldValue("id")).isNotEqualTo(documents.get(1).getFieldValue("id"));
		// The same single document on its own gets the same id as inside the batch
		SolrInputDocument alone = creator.create("---\ntitle: A\n---\nBody A.\n").getFirst();
		assertThat(alone.getFieldValue("id")).isEqualTo(documents.get(0).getFieldValue("id"));
	}

	@Test
	void textBeforeTheFirstFrontMatterIsItsOwnDocument() {
		String markdown = """
				# Preface

				Untitled notes.

				---
				id: two
				---
				Second.
				""";

		List<SolrInputDocument> documents = creator.create(markdown);

		assertThat(documents).hasSize(2);
		assertThat(documents.get(0).getFieldValue("title")).isEqualTo("Preface");
		assertThat(documents.get(1).getFieldValue("id")).isEqualTo("two");
	}
}
