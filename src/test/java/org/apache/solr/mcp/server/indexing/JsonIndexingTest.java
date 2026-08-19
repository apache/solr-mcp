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
 * Test class for JSON indexing functionality in IndexingService.
 *
 * <p>
 * This test verifies that the IndexingService can correctly parse JSON data and
 * convert it into SolrInputDocument objects using the schema-less approach, and
 * that null or blank input is rejected with the same message shape used by the
 * CSV and XML creators.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class JsonIndexingTest {

	@Autowired
	private IndexingDocumentCreator indexingDocumentCreator;

	@Test
	void testCreateSchemalessDocumentsFromJson() throws Exception {
		// Given

		String jsonData = """
				[
				  {"id":"0553573403","name":"A Game of Thrones","price":7.99,"genre_s":"fantasy"},
				  {"id":"0553293354","name":"Foundation","price":7.99,"genre_s":"scifi"}
				]
				""";

		// When
		List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromJson(jsonData);

		// Then
		assertThat(documents).hasSize(2);

		SolrInputDocument firstDoc = documents.getFirst();
		assertThat(firstDoc.getFieldValue("id")).isEqualTo("0553573403");
		assertThat(firstDoc.getFieldValue("name")).isEqualTo("A Game of Thrones");
		assertThat(firstDoc.getFieldValue("genre_s")).isEqualTo("fantasy");

		SolrInputDocument secondDoc = documents.get(1);
		assertThat(secondDoc.getFieldValue("id")).isEqualTo("0553293354");
		assertThat(secondDoc.getFieldValue("name")).isEqualTo("Foundation");
		assertThat(secondDoc.getFieldValue("genre_s")).isEqualTo("scifi");
	}

	@Test
	void testCreateSchemalessDocumentsFromJsonWithNullInput() {
		// Given

		// When/Then
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromJson(null))
				.isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("JSON input cannot be null or empty");
	}

	@Test
	void testCreateSchemalessDocumentsFromJsonWithEmptyInput() {
		// Given

		// When/Then
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromJson(""))
				.isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("JSON input cannot be null or empty");
	}

	@Test
	void testCreateSchemalessDocumentsFromJsonWithWhitespaceOnlyInput() {
		// Given

		// When/Then
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromJson("   \n\t  "))
				.isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("JSON input cannot be null or empty");
	}
}
