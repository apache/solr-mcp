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
package org.apache.solr.mcp.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test that exercises the MCP server through a real MCP client.
 *
 * <p>
 * Boots the application in HTTP mode, connects an MCP client, and tests the
 * full create-collection → index → search workflow via MCP tool calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"http.security.enabled=false",
		"spring.docker.compose.enabled=false"})
@ActiveProfiles("http")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpClientIntegrationTest {

	private static final String COLLECTION = "mcp-client-test";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@LocalServerPort
	private int port;

	private McpSyncClient mcpClient;

	@BeforeAll
	void setupClient() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build();
		mcpClient = McpClient.sync(transport).build();
		mcpClient.initialize();
	}

	@AfterAll
	void tearDown() {
		if (mcpClient != null) {
			mcpClient.close();
		}
	}

	@Test
	@Order(1)
	void pingServer() {
		assertDoesNotThrow(() -> mcpClient.ping(), "MCP ping should succeed");
	}

	@Test
	@Order(2)
	void listToolsReturnsExpectedTools() {
		var toolsResult = mcpClient.listTools();
		assertNotNull(toolsResult);
		List<String> toolNames = toolsResult.tools().stream().map(t -> t.name()).toList();

		assertTrue(toolNames.contains("create-collection"), "Should have create-collection tool");
		assertTrue(toolNames.contains("index-json-documents"), "Should have index-json-documents tool");
		assertTrue(toolNames.contains("search"), "Should have search tool");
		assertTrue(toolNames.contains("list-collections"), "Should have list-collections tool");
		assertTrue(toolNames.contains("check-health"), "Should have check-health tool");
		assertTrue(toolNames.contains("get-collection-stats"), "Should have get-collection-stats tool");
		assertTrue(toolNames.contains("get-schema"), "Should have get-schema tool");
	}

	@Test
	@Order(3)
	void createCollection() {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("create-collection", Map.of("name", COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertTrue(text.contains("success") || text.contains("true"), "Collection creation should succeed: " + text);
	}

	@Test
	@Order(4)
	void listCollectionsContainsCreatedCollection() {
		CallToolResult result = mcpClient.callTool(new CallToolRequest("list-collections", Map.of()));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertTrue(text.contains(COLLECTION), "Created collection should appear in list: " + text);
	}

	@Test
	@Order(5)
	void indexJsonDocuments() {
		String json = """
				[
				  {"id": "1", "title": "Introduction to Solr", "author": "Alice", "category": "search"},
				  {"id": "2", "title": "MCP Protocol Guide", "author": "Bob", "category": "protocol"},
				  {"id": "3", "title": "Spring Boot in Action", "author": "Charlie", "category": "framework"},
				  {"id": "4", "title": "Advanced Solr Techniques", "author": "Alice", "category": "search"},
				  {"id": "5", "title": "Building MCP Servers", "author": "Diana", "category": "protocol"}
				]
				""";

		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("index-json-documents", Map.of("collection", COLLECTION, "json", json)));

		assertNotNull(result);
		assertNotError(result);
	}

	@Test
	@Order(6)
	void checkHealthShowsIndexedDocuments() {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("check-health", Map.of("collection", COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertTrue(text.contains("true") || text.contains("healthy"), "Collection should be healthy: " + text);
		assertTrue(text.contains("5"), "Should report 5 documents: " + text);
	}

	@Test
	@Order(7)
	void searchAllDocuments() throws Exception {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("search", Map.of("collection", COLLECTION, "query", "*:*", "rows", 10)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);

		Map<String, Object> response = OBJECT_MAPPER.readValue(text, new TypeReference<>() {
		});
		assertEquals(5, getNumFound(response), "Should find all 5 documents");
	}

	@Test
	@Order(8)
	void searchWithFilterQuery() throws Exception {
		CallToolResult result = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", COLLECTION, "query", "*:*", "filterQueries", List.of("category:search"))));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);

		Map<String, Object> response = OBJECT_MAPPER.readValue(text, new TypeReference<>() {
		});
		assertEquals(2, getNumFound(response), "Should find 2 search-category documents");
	}

	@Test
	@Order(9)
	void searchWithKeyword() throws Exception {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("search", Map.of("collection", COLLECTION, "query", "title:Solr")));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);

		Map<String, Object> response = OBJECT_MAPPER.readValue(text, new TypeReference<>() {
		});
		int numFound = getNumFound(response);
		assertTrue(numFound >= 1, "Should find at least 1 document with 'Solr' in title: " + numFound);
	}

	@Test
	@Order(10)
	void searchWithPagination() throws Exception {
		CallToolResult page1 = mcpClient.callTool(
				new CallToolRequest("search", Map.of("collection", COLLECTION, "query", "*:*", "start", 0, "rows", 2)));
		CallToolResult page2 = mcpClient.callTool(
				new CallToolRequest("search", Map.of("collection", COLLECTION, "query", "*:*", "start", 2, "rows", 2)));

		Map<String, Object> response1 = OBJECT_MAPPER.readValue(extractText(page1), new TypeReference<>() {
		});
		Map<String, Object> response2 = OBJECT_MAPPER.readValue(extractText(page2), new TypeReference<>() {
		});

		List<Map<String, Object>> docs1 = getDocuments(response1);
		List<Map<String, Object>> docs2 = getDocuments(response2);

		assertEquals(2, docs1.size(), "Page 1 should have 2 documents");
		assertEquals(2, docs2.size(), "Page 2 should have 2 documents");
		assertNotEquals(docs1.get(0).get("id"), docs2.get(0).get("id"), "Pages should return different documents");
	}

	@Test
	@Order(11)
	void getCollectionStats() {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("get-collection-stats", Map.of("collection", COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertTrue(text.contains("5") || text.contains("numDocs"), "Stats should reference indexed documents: " + text);
	}

	@Test
	@Order(12)
	void getSchema() {
		CallToolResult result = mcpClient.callTool(new CallToolRequest("get-schema", Map.of("collection", COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertFalse(text.isEmpty(), "Schema response should not be empty");
	}

	@Test
	@Order(13)
	void searchWithFacets() throws Exception {
		CallToolResult result = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", COLLECTION, "query", "*:*", "facetFields", List.of("id"), "rows", 0)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);

		Map<String, Object> response = OBJECT_MAPPER.readValue(text, new TypeReference<>() {
		});
		@SuppressWarnings("unchecked")
		Map<String, Object> facets = (Map<String, Object>) response.get("facets");
		assertNotNull(facets, "Should have facets in response");
		assertTrue(facets.containsKey("id"), "Should have id facet");
	}

	@Test
	@Order(14)
	void indexCsvDocuments() {
		String csv = """
				id,title,author,category
				6,CSV Document One,Eve,csv-test
				7,CSV Document Two,Frank,csv-test
				""";

		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("index-csv-documents", Map.of("collection", COLLECTION, "csv", csv)));

		assertNotNull(result);
		assertNotError(result);
	}

	@Test
	@Order(15)
	void searchFindsAllDocumentsAfterCsvIndexing() throws Exception {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("search", Map.of("collection", COLLECTION, "query", "*:*", "rows", 0)));

		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});
		assertEquals(7, getNumFound(response), "Should find 7 documents (5 JSON + 2 CSV)");
	}

	private static String extractText(CallToolResult result) {
		assertNotNull(result.content(), "Result content should not be null");
		assertFalse(result.content().isEmpty(), "Result content should not be empty");
		assertInstanceOf(TextContent.class, result.content().get(0), "Content should be TextContent");
		return ((TextContent) result.content().get(0)).text();
	}

	private static void assertNotError(CallToolResult result) {
		if (Boolean.TRUE.equals(result.isError())) {
			String errorText = result.content().isEmpty()
					? "unknown error"
					: ((TextContent) result.content().get(0)).text();
			fail("MCP tool call returned error: " + errorText);
		}
	}

	private static int getNumFound(Map<String, Object> response) {
		Object value = response.get("numFound");
		assertNotNull(value, "numFound should be present in response");
		return ((Number) value).intValue();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> getDocuments(Map<String, Object> response) {
		Object value = response.get("documents");
		assertNotNull(value, "documents should be present in response");
		return (List<Map<String, Object>>) value;
	}

}
