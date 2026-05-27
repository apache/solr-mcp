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
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Base class for MCP client integration tests. Exercises the full
 * create-collection → index → search workflow via MCP tool calls. Subclasses
 * provide the transport (HTTP or stdio).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class McpClientIntegrationTestBase {

	protected static final String COLLECTION = "mcp-client-test";

	protected static final String SHOWS_COLLECTION = "shows";

	/**
	 * Number of documents in {@code src/test/resources/shows.json} — used by the
	 * end-to-end shows workflow tests (orders 19–27) to assert indexing and search
	 * results.
	 */
	protected static final int SHOWS_DOC_COUNT = 61;

	protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	protected McpSyncClient mcpClient;

	protected abstract McpSyncClient createClient() throws Exception;

	@BeforeAll
	void beforeAll() throws Exception {
		mcpClient = createClient();
		mcpClient.initialize();
	}

	@AfterAll
	void afterAll() {
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
		assertTrue(toolNames.contains("add-fields"), "Should have add-fields tool");
		assertTrue(toolNames.contains("add-field-types"), "Should have add-field-types tool");
	}

	@Test
	@Order(2)
	void toolsExposeBehaviorHints() {
		Map<String, Tool> tools = mcpClient.listTools().tools().stream()
				.collect(Collectors.toMap(Tool::name, Function.identity()));

		// Read-only tools — clients can call without approval prompts.
		assertReadOnly(tools, "search");
		assertReadOnly(tools, "list-collections");
		assertReadOnly(tools, "get-collection-stats");
		assertReadOnly(tools, "check-health");
		assertReadOnly(tools, "get-schema");

		// create-collection: additive write (provisions a new collection, not
		// idempotent because a second call with the same name errors).
		assertHint(tools, "create-collection", /* readOnly */ false, /* destructive */ false, /* idempotent */ false);

		// Indexing: destructive (Solr overwrites by uniqueKey) but idempotent —
		// posting the same JSON/CSV/XML twice leaves the index in the same state.
		assertHint(tools, "index-json-documents", false, true, true);
		assertHint(tools, "index-csv-documents", false, true, true);
		assertHint(tools, "index-xml-documents", false, true, true);
	}

	private static void assertReadOnly(Map<String, Tool> tools, String name) {
		Tool tool = tools.get(name);
		assertNotNull(tool, name + " tool should be present");
		assertNotNull(tool.annotations(), name + " should expose hints");
		assertEquals(Boolean.TRUE, tool.annotations().readOnlyHint(), name + " should be readOnly");
	}

	private static void assertHint(Map<String, Tool> tools, String name, boolean readOnly, boolean destructive,
			boolean idempotent) {
		Tool tool = tools.get(name);
		assertNotNull(tool, name + " tool should be present");
		assertNotNull(tool.annotations(), name + " should expose hints");
		assertEquals(readOnly, tool.annotations().readOnlyHint(), name + " readOnlyHint mismatch");
		assertEquals(destructive, tool.annotations().destructiveHint(), name + " destructiveHint mismatch");
		assertEquals(idempotent, tool.annotations().idempotentHint(), name + " idempotentHint mismatch");
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

	@Test
	@Order(16)
	void addFieldsToTestCollection() throws Exception {
		List<Map<String, Object>> fields = List.of(
				Map.of("name", "platform", "type", "string", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "release_year", "type", "pint", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "genres", "type", "strings", "stored", true, "indexed", true, "docValues", true));

		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("add-fields", Map.of("collection", COLLECTION, "fields", fields)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertTrue(text.contains("platform"), "Result should mention added 'platform': " + text);
		assertTrue(text.contains("release_year"), "Result should mention added 'release_year': " + text);
		assertTrue(text.contains("genres"), "Result should mention added 'genres': " + text);
	}

	@Test
	@Order(17)
	void indexDocumentWithNewFields() {
		String json = """
				[
				  {"id": "show-1", "title": "Breaking Bad", "author": "Vince Gilligan",
				   "category": "show", "platform": "Netflix",
				   "release_year": 2008, "genres": ["drama", "crime"]}
				]
				""";

		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("index-json-documents", Map.of("collection", COLLECTION, "json", json)));

		assertNotNull(result);
		assertNotError(result);
	}

	@Test
	@Order(18)
	void searchWithNewFieldFilters() throws Exception {
		CallToolResult byPlatform = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", COLLECTION, "query", "*:*", "filterQueries", List.of("platform:Netflix"))));
		Map<String, Object> r1 = OBJECT_MAPPER.readValue(extractText(byPlatform), new TypeReference<>() {
		});
		assertEquals(1, getNumFound(r1), "Should find exactly 1 doc with platform=Netflix");

		CallToolResult byGenre = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", COLLECTION, "query", "*:*", "filterQueries", List.of("genres:crime"))));
		Map<String, Object> r2 = OBJECT_MAPPER.readValue(extractText(byGenre), new TypeReference<>() {
		});
		assertEquals(1, getNumFound(r2), "Multi-valued 'genres' should match on 'crime'");
	}

	// ===== End-to-end shows workflow (orders 19–27) =====
	// Exercises the canonical "set up a new collection with a defined schema, index
	// real data, then search and introspect" workflow purely through MCP tool
	// calls. The 61-document dataset lives in src/test/resources/shows.json so the
	// LLM-facing JSON payload is realistic (UTF-8 names, nullable end_year for
	// ongoing shows, multi-valued genres/cast/creators/tags). This works in all
	// four transport × runtime combinations (stdio/HTTP × JVM/native) because each
	// step is a pure MCP tool call against an existing tool.

	@Test
	@Order(19)
	void createShowsCollection() {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("create-collection", Map.of("name", SHOWS_COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		assertTrue(text.contains("success") || text.contains("true"),
				"Shows collection creation should succeed: " + text);
	}

	@Test
	@Order(20)
	void addShowsSchema() throws Exception {
		// Mirrors the user's original curl example: 16 fields covering text_general,
		// string, strings (multi-valued), pint, and pdouble types — the schema the
		// JSON dataset is shaped for.
		//
		// Practical wrinkle: SolrCloud collections sharing the same configset (here
		// _default) share the same managed-schema in ZooKeeper. Earlier tests in
		// this base class indexed docs into mcp-client-test (some via schemaless
		// add-unknown-fields, some via add-fields), and those modifications persist
		// to the shared _default configset. So when the freshly-created shows
		// collection's schema is read, a subset of the desired fields may already
		// exist. The tool description on add-fields tells the LLM to "Call
		// get-schema first to inspect existing field configuration before adding"
		// — this test does exactly that, then only adds the gap.
		List<Map<String, Object>> desiredFields = List.of(
				Map.of("name", "title", "type", "text_general", "stored", true, "indexed", true),
				Map.of("name", "platform", "type", "string", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "genres", "type", "strings", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "release_year", "type", "pint", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "end_year", "type", "pint", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "status", "type", "string", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "seasons", "type", "pint", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "episodes", "type", "pint", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "creators", "type", "strings", "stored", true, "indexed", true),
				Map.of("name", "cast", "type", "strings", "stored", true, "indexed", true),
				Map.of("name", "country", "type", "string", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "language", "type", "string", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "rating", "type", "string", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "imdb_rating", "type", "pdouble", "stored", true, "indexed", true, "docValues", true),
				Map.of("name", "description", "type", "text_general", "stored", true, "indexed", true),
				Map.of("name", "tags", "type", "strings", "stored", true, "indexed", true, "docValues", true));

		java.util.Set<String> existingFieldNames = fetchExistingFieldNames(SHOWS_COLLECTION);

		List<Map<String, Object>> fieldsToAdd = desiredFields.stream()
				.filter(f -> !existingFieldNames.contains(String.valueOf(f.get("name")))).toList();

		// Edge case: every desired field already exists in the shared configset
		// (extremely unlikely given the shows-specific names). Soft-pass; the
		// schema is already in the state we want.
		if (fieldsToAdd.isEmpty()) {
			return;
		}

		CallToolResult result = mcpClient.callTool(
				new CallToolRequest("add-fields", Map.of("collection", SHOWS_COLLECTION, "fields", fieldsToAdd)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		// Spot-check at least one shows-specific field name appears (these won't
		// have leaked from any earlier collection's schema).
		assertTrue(text.contains("seasons") || text.contains("episodes") || text.contains("imdb_rating"),
				"Result should mention at least one shows-specific field that was added: " + text);
	}

	private java.util.Set<String> fetchExistingFieldNames(String collection) throws Exception {
		CallToolResult schemaResult = mcpClient
				.callTool(new CallToolRequest("get-schema", Map.of("collection", collection)));
		assertNotError(schemaResult);
		Map<String, Object> schema = OBJECT_MAPPER.readValue(extractText(schemaResult), new TypeReference<>() {
		});
		Object fields = schema.get("fields");
		java.util.Set<String> names = new java.util.HashSet<>();
		if (fields instanceof List<?> fieldList) {
			for (Object f : fieldList) {
				if (f instanceof Map<?, ?> fieldMap) {
					Object name = fieldMap.get("name");
					if (name instanceof String s) {
						names.add(s);
					}
				}
			}
		}
		return names;
	}

	@Test
	@Order(21)
	void indexShowsFromClasspathResource() throws Exception {
		String showsJson = loadClasspathResource("/shows.json");
		assertFalse(showsJson.isBlank(), "shows.json resource must not be blank");

		CallToolResult result = mcpClient.callTool(
				new CallToolRequest("index-json-documents", Map.of("collection", SHOWS_COLLECTION, "json", showsJson)));

		assertNotNull(result);
		assertNotError(result);
	}

	@Test
	@Order(22)
	void searchAllShowsAndAssertCount() throws Exception {
		CallToolResult result = mcpClient.callTool(
				new CallToolRequest("search", Map.of("collection", SHOWS_COLLECTION, "query", "*:*", "rows", 0)));

		assertNotNull(result);
		assertNotError(result);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});
		assertEquals(SHOWS_DOC_COUNT, getNumFound(response),
				"Should find all " + SHOWS_DOC_COUNT + " shows after indexing");
	}

	@Test
	@Order(23)
	void searchShowsWithFacetByPlatform() throws Exception {
		CallToolResult result = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", SHOWS_COLLECTION, "query", "*:*", "facetFields", List.of("platform"), "rows", 0)));

		assertNotNull(result);
		assertNotError(result);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});

		@SuppressWarnings("unchecked")
		Map<String, Object> facets = (Map<String, Object>) response.get("facets");
		assertNotNull(facets, "Response should contain facets: " + response);

		@SuppressWarnings("unchecked")
		Map<String, Object> platformFacet = (Map<String, Object>) facets.get("platform");
		assertNotNull(platformFacet, "facets.platform should be present: " + facets);

		// The dataset has 20 Netflix shows and 20 Amazon Prime Video shows.
		Object netflixCount = platformFacet.get("Netflix");
		assertNotNull(netflixCount, "Netflix facet count should be present: " + platformFacet);
		assertEquals(20, ((Number) netflixCount).intValue(), "Netflix should facet to 20 shows: " + platformFacet);

		Object primeCount = platformFacet.get("Amazon Prime Video");
		assertNotNull(primeCount, "Amazon Prime Video facet count should be present: " + platformFacet);
		assertEquals(20, ((Number) primeCount).intValue(),
				"Amazon Prime Video should facet to 20 shows: " + platformFacet);

		assertTrue(platformFacet.containsKey("HBO Max"), "HBO Max should appear in facet: " + platformFacet);
	}

	@Test
	@Order(24)
	void searchShowsWithFilterAndKeyword() throws Exception {
		// Filter to one platform, then full-text search the description field.
		CallToolResult result = mcpClient.callTool(
				new CallToolRequest("search", Map.of("collection", SHOWS_COLLECTION, "query", "description:apocalyptic",
						"filterQueries", List.of("platform:\"Amazon Prime Video\""), "rows", 10)));

		assertNotNull(result);
		assertNotError(result);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});
		// "Fallout" on Amazon Prime Video has "post-apocalyptic" in its description.
		assertTrue(getNumFound(response) >= 1,
				"Should find at least one apocalyptic show on Amazon Prime Video: " + getNumFound(response));
	}

	@Test
	@Order(25)
	void searchShowsByMultiValuedGenre() throws Exception {
		// Multi-valued strings field: filter on a single genre value.
		CallToolResult result = mcpClient.callTool(new CallToolRequest("search", Map.of("collection", SHOWS_COLLECTION,
				"query", "*:*", "filterQueries", List.of("genres:Sci-Fi"), "rows", 0)));

		assertNotNull(result);
		assertNotError(result);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});
		// At least Stranger Things, Dark, Black Mirror, The Umbrella Academy, The
		// Boys, The Expanse, Fallout, Upload, The Last of Us, Westworld, WandaVision,
		// The Mandalorian, Andor, Loki, Severance, Foundation, Star Trek SNW — well
		// over 10 docs.
		assertTrue(getNumFound(response) >= 10,
				"Should find at least 10 Sci-Fi shows across the dataset, got " + getNumFound(response));
	}

	@Test
	@Order(26)
	void getShowsSchemaIncludesAllAddedFields() {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("get-schema", Map.of("collection", SHOWS_COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		// All 16 user-defined fields must appear in the schema response. Spot-check a
		// representative selection across types: text_general, string, strings,
		// pint, pdouble.
		for (String name : List.of("title", "platform", "genres", "release_year", "imdb_rating", "description",
				"tags")) {
			assertTrue(text.contains("\"" + name + "\""), "get-schema response should include field '" + name + "'");
		}
	}

	@Test
	@Order(27)
	void getShowsCollectionStats() throws Exception {
		CallToolResult result = mcpClient
				.callTool(new CallToolRequest("get-collection-stats", Map.of("collection", SHOWS_COLLECTION)));

		assertNotNull(result);
		assertNotError(result);
		String text = extractText(result);
		// Stats response is a JSON-serialized SolrMetrics. The indexStats.numDocs
		// field carries the count.
		assertTrue(text.contains(String.valueOf(SHOWS_DOC_COUNT)),
				"Stats should report " + SHOWS_DOC_COUNT + " docs somewhere in the payload: " + text);
	}

	private static String loadClasspathResource(String resourcePath) throws Exception {
		try (InputStream in = McpClientIntegrationTestBase.class.getResourceAsStream(resourcePath)) {
			Objects.requireNonNull(in, "Classpath resource not found: " + resourcePath);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	protected static String extractText(CallToolResult result) {
		assertNotNull(result.content(), "Result content should not be null");
		assertFalse(result.content().isEmpty(), "Result content should not be empty");
		assertInstanceOf(TextContent.class, result.content().get(0), "Content should be TextContent");
		return ((TextContent) result.content().get(0)).text();
	}

	protected static void assertNotError(CallToolResult result) {
		if (Boolean.TRUE.equals(result.isError())) {
			String errorText = result.content().isEmpty()
					? "unknown error"
					: ((TextContent) result.content().get(0)).text();
			fail("MCP tool call returned error: " + errorText);
		}
	}

	protected static int getNumFound(Map<String, Object> response) {
		Object value = response.get("numFound");
		assertNotNull(value, "numFound should be present in response");
		return ((Number) value).intValue();
	}

	@SuppressWarnings("unchecked")
	protected static List<Map<String, Object>> getDocuments(Map<String, Object> response) {
		Object value = response.get("documents");
		assertNotNull(value, "documents should be present in response");
		return (List<Map<String, Object>>) value;
	}

}
