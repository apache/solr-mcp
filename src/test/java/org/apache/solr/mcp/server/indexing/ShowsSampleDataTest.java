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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.JsonDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.MarkdownDocumentCreator;
import org.junit.jupiter.api.Test;

/**
 * The {@code shows} sample dataset ships as {@code shows.json} and as
 * {@code shows-markdown.md}. These tests pin that the two representations yield
 * the same 61 documents.
 *
 * <p>
 * The Markdown form is one file holding 61 records, each its own YAML front
 * matter block followed by the show's title and description. Markdown has no
 * record separator of its own, so
 * {@link MarkdownDocumentCreator#create(String)} splits on front matter blocks;
 * this test is what pins that a whole dataset survives the round trip rather
 * than only a document or two.
 */
class ShowsSampleDataTest {

	private static final int SHOWS = 61;

	private final JsonDocumentCreator json = new JsonDocumentCreator(new ObjectMapper());

	@Test
	void jsonHas61ShowsWithUniqueIds() throws Exception {
		Map<String, Map<String, List<String>>> shows = byId(json.create(resource("/shows.json")), "id");

		assertThat(shows).hasSize(SHOWS);
		assertThat(shows.get("netflix-001")).containsEntry("title", List.of("Stranger Things")).containsEntry("genres",
				List.of("Sci-Fi", "Horror", "Drama"));
	}

	@Test
	void theMarkdownFileParsesToTheSameDocumentsAsJson() throws Exception {
		Map<String, Map<String, List<String>>> expected = byId(json.create(resource("/shows.json")), "id");
		Map<String, Map<String, List<String>>> actual = byId(
				new MarkdownDocumentCreator().create(resource("/shows-markdown.md")), "id");

		assertThat(actual).hasSize(SHOWS);
		assertThat(actual.keySet()).isEqualTo(expected.keySet());

		for (Map.Entry<String, Map<String, List<String>>> show : expected.entrySet()) {
			Map<String, List<String>> fields = new TreeMap<>(actual.get(show.getKey()));

			// The description is the document body, so it comes back as content
			// (with the title heading) rather than as a front matter field.
			Map<String, List<String>> frontMatter = new TreeMap<>(show.getValue());
			List<String> description = frontMatter.remove("description");
			assertThat(fields.remove("content")).as("%s content", show.getKey()).hasSize(1).first().asString()
					.contains(description.getFirst());
			assertThat(fields.remove("headings")).as("%s headings", show.getKey())
					.isEqualTo(show.getValue().get("title"));
			assertThat(fields).as(show.getKey()).containsExactlyEntriesOf(frontMatter);
		}
	}

	private static Map<String, Map<String, List<String>>> byId(List<SolrInputDocument> docs, String idField) {
		Map<String, Map<String, List<String>>> byId = new TreeMap<>();
		for (SolrInputDocument doc : docs) {
			Map<String, List<String>> fields = fields(doc, "");
			String id = Objects.requireNonNull(fields.get(idField), "missing " + idField).getFirst();
			assertThat(byId.put(id, fields)).as("duplicate id %s", id).isNull();
		}
		return byId;
	}

	/**
	 * Field name to string values, with {@code prefix} stripped from every name.
	 */
	private static Map<String, List<String>> fields(SolrInputDocument doc, String prefix) {
		Map<String, List<String>> fields = new TreeMap<>();
		for (String name : doc.getFieldNames()) {
			String key = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
			fields.put(key, doc.getFieldValues(name).stream().map(String::valueOf).toList());
		}
		return new LinkedHashMap<>(fields);
	}

	private static String resource(String path) throws IOException {
		try (InputStream in = Objects.requireNonNull(ShowsSampleDataTest.class.getResourceAsStream(path),
				"missing test resource " + path)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
