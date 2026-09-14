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
 * The {@code shows} sample dataset ships in every format the indexing tools
 * accept: {@code shows.json}, {@code shows.csv}, {@code shows.xml} and one
 * Markdown file per show under {@code shows-markdown/}. These tests pin that
 * the representations the server parses itself (JSON and Markdown) yield the
 * same 61 documents; CSV and XML are forwarded to Solr and checked end to end
 * in {@code ShowsSampleDataIntegrationTest}.
 *
 * <p>
 * Two representation choices are worth knowing:
 * <ul>
 * <li>CSV carries multi-valued fields as <em>repeated column headers</em>
 * ({@code genres,genres,genres}); Solr's CSV handler adds one value per
 * non-empty cell under the same field name.</li>
 * <li>XML is Solr's own update format ({@code <add><doc><field name=...>}); it
 * is forwarded to Solr rather than parsed here, so its equality with the JSON
 * documents is checked end to end in
 * {@code ShowsSampleDataIntegrationTest}.</li>
 * </ul>
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
	void markdownFilesParseToTheSameDocumentsAsJson() throws Exception {
		Map<String, Map<String, List<String>>> expected = byId(json.create(resource("/shows.json")), "id");
		MarkdownDocumentCreator markdown = new MarkdownDocumentCreator();

		for (Map.Entry<String, Map<String, List<String>>> show : expected.entrySet()) {
			List<SolrInputDocument> docs = markdown.create(resource("/shows-markdown/" + show.getKey() + ".md"));
			assertThat(docs).as(show.getKey()).hasSize(1);
			Map<String, List<String>> fields = fields(docs.getFirst(), "");

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
