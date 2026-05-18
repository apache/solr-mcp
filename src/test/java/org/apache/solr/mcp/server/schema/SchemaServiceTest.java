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
package org.apache.solr.mcp.server.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest.MultiUpdate;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive test suite for the SchemaService class. Tests schema retrieval
 * functionality with various scenarios including success and error cases.
 */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class SchemaServiceTest {

	@Mock
	private SolrClient solrClient;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private SchemaResponse schemaResponse;

	@Mock
	private SchemaRepresentation schemaRepresentation;

	private SchemaService schemaService;

	@BeforeEach
	void setUp() {
		schemaService = new SchemaService(solrClient, objectMapper);
	}

	@Test
	void testSchemaService_InstantiatesCorrectly() {
		// Given/When
		SchemaService service = new SchemaService(solrClient, objectMapper);

		// Then
		assertNotNull(service, "SchemaService should be instantiated correctly");
	}

	@Test
	void testGetSchema_CollectionNotFound() throws Exception {
		// Given
		final String nonExistentCollection = "non_existent_collection";

		// When SolrClient throws an exception for non-existent collection
		when(solrClient.request(any(SchemaRequest.class), eq(nonExistentCollection)))
				.thenThrow(new SolrServerException("Collection not found: " + nonExistentCollection));

		// Then
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(nonExistentCollection);
		});
	}

	@Test
	void testGetSchema_SolrServerException() throws Exception {
		// Given
		final String collectionName = "test_collection";

		// When SolrClient throws a SolrServerException
		when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
				.thenThrow(new SolrServerException("Solr server error"));

		// Then
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(collectionName);
		});
	}

	@Test
	void testGetSchema_IOException() throws Exception {
		// Given
		final String collectionName = "test_collection";

		// When SolrClient throws an IOException
		when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
				.thenThrow(new IOException("Network connection error"));

		// Then
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(collectionName);
		});
	}

	@Test
	void testGetSchema_WithNullCollection() {
		// Given a null collection name
		// Then should throw an exception (NullPointerException or
		// IllegalArgumentException)
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(null);
		});
	}

	@Test
	void testGetSchema_WithEmptyCollection() {
		// Given an empty collection name
		// Then should throw an exception
		assertThrows(Exception.class, () -> {
			schemaService.getSchema("");
		});
	}

	@Test
	void testConstructor() {
		// Test that constructor properly initializes the service
		SchemaService service = new SchemaService(solrClient, objectMapper);
		assertNotNull(service);
	}

	@Test
	void testConstructor_WithNullClient() {
		// Test constructor with null client
		assertDoesNotThrow(() -> {
			new SchemaService(null, objectMapper);
		});
	}

	@Test
	void addFields_blankCollection_throws() {
		assertThrows(IllegalArgumentException.class,
				() -> schemaService.addFields(null, List.of(Map.of("name", "x", "type", "string"))));
		assertThrows(IllegalArgumentException.class,
				() -> schemaService.addFields("", List.of(Map.of("name", "x", "type", "string"))));
		assertThrows(IllegalArgumentException.class,
				() -> schemaService.addFields("   ", List.of(Map.of("name", "x", "type", "string"))));
	}

	@Test
	void addFields_emptyList_throws() {
		assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("col", null));
		assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("col", List.of()));
	}

	@Test
	void addFields_happyPath_buildsMultiUpdateAndEchoesNames() throws Exception {
		List<Map<String, Object>> fields = List.of(
				Map.of("name", "title", "type", "text_general", "stored", true, "indexed", true),
				Map.of("name", "platform", "type", "string", "stored", true, "indexed", true, "docValues", true));

		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

		SchemaUpdateResult result = schemaService.addFields("col", fields);

		assertEquals(List.of("title", "platform"), result.addedNames());
		assertEquals("col", result.collection());

		ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
		verify(solrClient).request(captor.capture(), eq("col"));
		assertInstanceOf(MultiUpdate.class, captor.getValue());
	}

	@Test
	void addFields_solrThrows_propagates() throws Exception {
		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenThrow(new SolrServerException("simulated"));

		assertThrows(SolrServerException.class,
				() -> schemaService.addFields("col", List.of(Map.of("name", "x", "type", "string"))));
	}

	@Test
	void addFieldTypes_blankCollection_throws() {
		assertThrows(IllegalArgumentException.class,
				() -> schemaService.addFieldTypes(null, List.of(Map.of("name", "x", "class", "solr.StrField"))));
		assertThrows(IllegalArgumentException.class,
				() -> schemaService.addFieldTypes("", List.of(Map.of("name", "x", "class", "solr.StrField"))));
		assertThrows(IllegalArgumentException.class,
				() -> schemaService.addFieldTypes("   ", List.of(Map.of("name", "x", "class", "solr.StrField"))));
	}

	@Test
	void addFieldTypes_emptyList_throws() {
		assertThrows(IllegalArgumentException.class, () -> schemaService.addFieldTypes("col", null));
		assertThrows(IllegalArgumentException.class, () -> schemaService.addFieldTypes("col", List.of()));
	}

	@Test
	void addFieldTypes_happyPathWithAnalyzer_buildsCorrectFieldTypeDefinition() throws Exception {
		// Use a real ObjectMapper so convertValue actually works
		ObjectMapper realMapper = new ObjectMapper();
		SchemaService service = new SchemaService(solrClient, realMapper);

		List<Map<String, Object>> types = List.of(Map.of("name", "text_lowercase", "class", "solr.TextField",
				"analyzer", Map.of("tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"), "filters",
						List.of(Map.of("class", "solr.LowerCaseFilterFactory")))));

		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

		SchemaUpdateResult result = service.addFieldTypes("col", types);

		assertEquals(List.of("text_lowercase"), result.addedNames());

		ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
		verify(solrClient).request(captor.capture(), eq("col"));
		assertInstanceOf(MultiUpdate.class, captor.getValue());
	}

	@Test
	void addFieldTypes_separateAnalyzers_buildsCorrectFieldTypeDefinition() throws Exception {
		ObjectMapper realMapper = new ObjectMapper();
		SchemaService service = new SchemaService(solrClient, realMapper);

		List<Map<String, Object>> types = List.of(Map.of("name", "text_autocomplete", "class", "solr.TextField",
				"indexAnalyzer",
				Map.of("tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"), "filters",
						List.of(Map.of("class", "solr.LowerCaseFilterFactory"),
								Map.of("class", "solr.EdgeNGramFilterFactory", "minGramSize", 2, "maxGramSize", 20))),
				"queryAnalyzer", Map.of("tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"), "filters",
						List.of(Map.of("class", "solr.LowerCaseFilterFactory")))));

		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

		SchemaUpdateResult result = service.addFieldTypes("col", types);

		assertEquals(List.of("text_autocomplete"), result.addedNames());
	}

	@Test
	void addFieldTypes_denseVectorField_noAnalyzer() throws Exception {
		ObjectMapper realMapper = new ObjectMapper();
		SchemaService service = new SchemaService(solrClient, realMapper);

		List<Map<String, Object>> types = List.of(Map.of("name", "openai_embedding", "class", "solr.DenseVectorField",
				"vectorDimension", 1536, "similarityFunction", "cosine", "knnAlgorithm", "hnsw"));

		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

		SchemaUpdateResult result = service.addFieldTypes("col", types);

		assertEquals(List.of("openai_embedding"), result.addedNames());
	}

	@Test
	void addFieldTypes_solrThrows_propagates() throws Exception {
		ObjectMapper realMapper = new ObjectMapper();
		SchemaService service = new SchemaService(solrClient, realMapper);

		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenThrow(new SolrServerException("simulated"));

		assertThrows(SolrServerException.class,
				() -> service.addFieldTypes("col", List.of(Map.of("name", "x", "class", "solr.StrField"))));
	}

	/**
	 * Regression test for the single-class analyzer form
	 * <code>{"analyzer":{"class":"solr.WhitespaceAnalyzer"}}</code>. SolrJ's
	 * {@code AnalyzerDefinition} has no top-level setter for {@code class}, so a
	 * naive {@code objectMapper.convertValue} silently drops it and Solr gets a
	 * malformed analyzer. The helper must preserve unknown analyzer-level keys via
	 * {@code setAttributes}. We verify by serializing the captured MultiUpdate's
	 * wire body and asserting the analyzer class is present.
	 */
	@Test
	void addFieldTypes_analyzerWithOnlyClassKey_preservesClassInWireFormat() throws Exception {
		ObjectMapper realMapper = new ObjectMapper();
		SchemaService service = new SchemaService(solrClient, realMapper);

		List<Map<String, Object>> types = List.of(Map.of("name", "text_whitespace", "class", "solr.TextField",
				"analyzer", Map.of("class", "solr.WhitespaceAnalyzer")));

		when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

		service.addFieldTypes("col", types);

		ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
		verify(solrClient).request(captor.capture(), eq("col"));
		SolrRequest<?> req = captor.getValue();

		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		req.getContentWriter("application/json").write(baos);
		String body = baos.toString(java.nio.charset.StandardCharsets.UTF_8);

		assertTrue(body.contains("solr.WhitespaceAnalyzer"),
				"Wire body must preserve the analyzer-level 'class' key: " + body);
		assertTrue(body.contains("solr.TextField"),
				"Wire body must preserve the field-type-level 'class' key: " + body);
	}
}
