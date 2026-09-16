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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON creator has two entry points: a JSON string (files, tests) and a
 * list of already-parsed objects (the {@code index-json-documents} tool, whose
 * {@code documents} argument is a typed JSON array so the model never has to
 * escape JSON inside a string). Both must produce identical documents.
 */
class JsonDocumentCreatorTest {

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private final JsonDocumentCreator creator = new JsonDocumentCreator(objectMapper);

	@Test
	void objectsAndStringProduceTheSameDocuments() throws Exception {
		List<Map<String, Object>> objects = List.of(
				show("netflix-001", "Stranger Things", 8.7, true, "Sci-Fi", "Horror"),
				show("hbo-001", "Game of Thrones", 9.2, false, "Fantasy"));
		String json = objectMapper.writeValueAsString(objects);

		List<SolrInputDocument> fromObjects = creator.create(objects);
		List<SolrInputDocument> fromString = creator.create(json);

		assertThat(fromObjects).hasSize(2);
		assertThat(fields(fromObjects)).isEqualTo(fields(fromString));
	}

	@Test
	void nestedObjectsFlattenAndArraysBecomeMultiValued() {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("id", "1");
		doc.put("Author.Name", Map.of("first", "Ann", "last", "Lee"));
		doc.put("tags", List.of("a", "b"));
		doc.put("missing", null);

		SolrInputDocument result = creator.create(List.of(doc)).getFirst();

		assertThat(result.getFieldValue("id")).isEqualTo("1");
		assertThat(result.getFieldValue("author_name_first")).isEqualTo("Ann");
		assertThat(result.getFieldValues("tags")).containsExactly("a", "b");
		assertThat(result.getFieldNames()).doesNotContain("missing");
	}

	@Test
	void emptyListIsRejected() {
		assertThatThrownBy(() -> creator.create(List.of())).isInstanceOf(DocumentProcessingException.class)
				.hasMessage("JSON input cannot be empty");
	}

	/**
	 * A missing {@code documents} argument arrives as null. The creator owns the
	 * "nothing to index" policy for both entry points, so it reports null the same
	 * way it reports empty rather than letting the service NPE.
	 */
	@Test
	void nullListIsRejected() {
		assertThatThrownBy(() -> creator.create((List<Map<String, Object>>) null))
				.isInstanceOf(DocumentProcessingException.class).hasMessage("JSON input cannot be empty");
	}

	private static Map<String, Object> show(String id, String title, double rating, boolean ongoing, String... genres) {
		Map<String, Object> show = new LinkedHashMap<>();
		show.put("id", id);
		show.put("title", title);
		show.put("imdb_rating", rating);
		show.put("ongoing", ongoing);
		show.put("seasons", 3);
		show.put("genres", List.of(genres));
		show.put("end_year", null);
		return show;
	}

	private static List<Map<String, List<String>>> fields(List<SolrInputDocument> docs) {
		List<Map<String, List<String>>> all = new ArrayList<>();
		for (SolrInputDocument doc : docs) {
			Map<String, List<String>> fields = new TreeMap<>();
			for (String name : doc.getFieldNames()) {
				fields.put(name, doc.getFieldValues(name).stream().map(String::valueOf).toList());
			}
			all.add(fields);
		}
		return all;
	}
}
