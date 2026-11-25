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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.json.JsonContent;

/**
 * Base class for MCP client integration tests. Exercises the full create-collection → index →
 * search workflow via MCP tool calls. Subclasses provide the transport (HTTP or stdio).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class McpClientIntegrationTestBase {

  protected static final String COLLECTION = "mcp-client-test";

  protected static final String SHOWS_COLLECTION = "shows";

  /**
   * Number of documents in {@code src/test/resources/shows.json} — used by the end-to-end shows
   * workflow tests (orders 19–27) to assert indexing and search results.
   */
  protected static final int SHOWS_DOC_COUNT = 61;

  @AutoClose
  @SuppressWarnings("NotNullFieldNotInitialized") // set by beforeAll()
  protected McpSyncClient mcpClient;

  protected abstract McpSyncClient createClient() throws Exception;

  @BeforeAll
  void beforeAll() throws Exception {
    mcpClient = createClient();
    mcpClient.initialize();
  }

  @Test
  @Order(1)
  void pingServer() {
    assertDoesNotThrow(() -> mcpClient.ping(), "MCP ping should succeed");
  }

  @Test
  @Order(1)
  void serverInstructionsStateTheInfrastructureBoundary() {
    String instructions = mcpClient.getServerInstructions();
    assertNotNull(instructions, "initialize should return server instructions");
    assertTrue(
        instructions.contains("Surface boundaries"),
        "Instructions should keep the tool-surface boundaries");
    assertTrue(
        instructions.contains("does not manage cluster infrastructure"),
        "Instructions should state that cluster infrastructure is out of scope");
  }

  @Test
  @Order(2)
  void listToolsReturnsExpectedTools() {
    var toolsResult = mcpClient.listTools();
    assertNotNull(toolsResult);
    List<String> toolNames = toolsResult.tools().stream().map(t -> t.name()).toList();

    assertTrue(toolNames.contains("create-collection"), "Should have create-collection tool");
    assertTrue(toolNames.contains("index-json-documents"), "Should have index-json-documents tool");
    assertTrue(
        toolNames.contains("index-markdown-documents"),
        "Should have index-markdown-documents tool");
    assertTrue(toolNames.contains("search"), "Should have search tool");
    assertTrue(toolNames.contains("list-collections"), "Should have list-collections tool");
    assertTrue(toolNames.contains("check-health"), "Should have check-health tool");
    assertTrue(toolNames.contains("get-collection-stats"), "Should have get-collection-stats tool");
    assertTrue(toolNames.contains("get-schema"), "Should have get-schema tool");
    assertTrue(toolNames.contains("list-fields"), "Should have list-fields tool");
    assertTrue(toolNames.contains("add-fields"), "Should have add-fields tool");
    assertTrue(toolNames.contains("add-field-types"), "Should have add-field-types tool");
  }

  @Test
  @Order(2)
  void toolsExposeBehaviorHints() {
    Map<String, Tool> tools =
        mcpClient.listTools().tools().stream()
            .collect(Collectors.toMap(Tool::name, Function.identity()));

    // Read-only tools — clients can call without approval prompts.
    assertReadOnly(tools, "search");
    assertReadOnly(tools, "list-collections");
    assertReadOnly(tools, "get-collection-stats");
    assertReadOnly(tools, "check-health");
    assertReadOnly(tools, "get-schema");
    assertReadOnly(tools, "list-fields");

    // create-collection: additive write (provisions a new collection, not
    // idempotent because a second call with the same name errors).
    assertHint(
        tools,
        "create-collection", /* readOnly */
        false, /* destructive */
        false, /* idempotent */
        false);

    // Indexing: destructive (Solr overwrites by uniqueKey) but idempotent —
    // posting the same JSON/CSV/XML twice leaves the index in the same state.
    assertHint(tools, "index-json-documents", false, true, true);
    assertHint(tools, "index-csv-documents", false, true, true);
    assertHint(tools, "index-xml-documents", false, true, true);
    assertHint(tools, "index-markdown-documents", false, true, true);
  }

  private static void assertReadOnly(Map<String, Tool> tools, String name) {
    Tool tool = tools.get(name);
    assertNotNull(tool, name + " tool should be present");
    assertNotNull(tool.annotations(), name + " should expose hints");
    assertEquals(Boolean.TRUE, tool.annotations().readOnlyHint(), name + " should be readOnly");
  }

  private static void assertHint(
      Map<String, Tool> tools,
      String name,
      boolean readOnly,
      boolean destructive,
      boolean idempotent) {
    Tool tool = tools.get(name);
    assertNotNull(tool, name + " tool should be present");
    assertNotNull(tool.annotations(), name + " should expose hints");
    assertEquals(readOnly, tool.annotations().readOnlyHint(), name + " readOnlyHint mismatch");
    assertEquals(
        destructive, tool.annotations().destructiveHint(), name + " destructiveHint mismatch");
    assertEquals(
        idempotent, tool.annotations().idempotentHint(), name + " idempotentHint mismatch");
  }

  @Test
  @Order(3)
  void createCollection() {
    CallToolResult result =
        mcpClient.callTool(new CallToolRequest("create-collection", Map.of("name", COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).extractingPath("$.name").isEqualTo(COLLECTION);
    assertThat(json(result)).extractingPath("$.configSet").isEqualTo(COLLECTION + ".AUTOCREATED");
  }

  @Test
  @Order(4)
  void listCollectionsContainsCreatedCollection() {
    CallToolResult result = mcpClient.callTool(new CallToolRequest("list-collections", Map.of()));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).extractingPath("$[*].name").asArray().contains(COLLECTION);
  }

  @Test
  @Order(5)
  void indexJsonDocuments() {
    String json =
        """
				[
				  {"id": "1", "title": "Introduction to Solr", "author": "Alice", "category": "search"},
				  {"id": "2", "title": "MCP Protocol Guide", "author": "Bob", "category": "protocol"},
				  {"id": "3", "title": "Spring Boot in Action", "author": "Charlie", "category": "framework"},
				  {"id": "4", "title": "Advanced Solr Techniques", "author": "Alice", "category": "search"},
				  {"id": "5", "title": "Building MCP Servers", "author": "Diana", "category": "protocol"}
				]
				""";

    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "index-json-documents",
                Map.of("collection", COLLECTION, "documents", TestDocuments.json(json))));

    assertNotNull(result);
    assertNotError(result);
  }

  @Test
  @Order(6)
  void checkHealthShowsIndexedDocuments() {
    CallToolResult result =
        mcpClient.callTool(new CallToolRequest("check-health", Map.of("collection", COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).extractingPath("$.healthy").isEqualTo(true);
    assertThat(json(result)).extractingPath("$.numDocs").isEqualTo(5);
    // a healthy collection has no reason
    assertThat(json(result)).doesNotHavePath("$.reason");
  }

  @Test
  @Order(7)
  void searchAllDocuments() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search", Map.of("collection", COLLECTION, "query", "*:*", "rows", 10)));

    assertNotNull(result);
    assertNotError(result);
    assertEquals(5, getNumFound(result), "Should find all 5 documents");
  }

  @Test
  @Order(8)
  void searchWithFilterQuery() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    COLLECTION,
                    "query",
                    "*:*",
                    "filterQueries",
                    List.of("category:search"))));

    assertNotNull(result);
    assertNotError(result);
    assertEquals(2, getNumFound(result), "Should find 2 search-category documents");
  }

  @Test
  @Order(9)
  void searchWithKeyword() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("search", Map.of("collection", COLLECTION, "query", "title:Solr")));

    assertNotNull(result);
    assertNotError(result);
    int numFound = getNumFound(result);
    assertTrue(numFound >= 1, "Should find at least 1 document with 'Solr' in title: " + numFound);
  }

  @Test
  @Order(10)
  void searchWithPagination() {
    CallToolResult page1 =
        mcpClient.callTool(
            new CallToolRequest(
                "search", Map.of("collection", COLLECTION, "query", "*:*", "start", 0, "rows", 2)));
    CallToolResult page2 =
        mcpClient.callTool(
            new CallToolRequest(
                "search", Map.of("collection", COLLECTION, "query", "*:*", "start", 2, "rows", 2)));

    List<String> ids1 = read(page1, "$.documents[*].id");
    List<String> ids2 = read(page2, "$.documents[*].id");

    assertThat(ids1).as("page 1").hasSize(2);
    assertThat(ids2).as("page 2").hasSize(2).doesNotContainAnyElementsOf(ids1);
  }

  @Test
  @Order(11)
  void getCollectionStats() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("get-collection-stats", Map.of("collection", COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).extractingPath("$.index.numDocs").isEqualTo(5);
  }

  @Test
  @Order(12)
  void getSchema() {
    CallToolResult result =
        mcpClient.callTool(new CallToolRequest("get-schema", Map.of("collection", COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    String text = extractText(result);
    assertFalse(text.isEmpty(), "Schema response should not be empty");

    // Spring AI serializes the CollectionSchema record: declared schema properties as booleans,
    // a field type's class as className with its class-specific settings in parameters, no SolrJ
    // "attributes" wrapper and no catch-all, and the arrays present (they go missing in a native
    // image when the records lack reflection hints).
    JsonContent schema = json(result);
    String id = "$.fields[?(@.name == 'id')]";
    String textGeneral = "$.fieldTypes[?(@.name == 'text_general')]";
    // A deep scan never throws PathNotFoundException, so doesNotHavePath would always fail on it
    assertThat(schema).extractingPath("$..other").asArray().isEmpty();
    assertThat(schema).extractingPath(id + ".indexed").asArray().containsExactly(true);
    assertThat(schema).extractingPath(id + ".stored").asArray().containsExactly(true);
    assertThat(schema).extractingPath(id + ".flags").asArray().isEmpty();
    assertThat(schema)
        .extractingPath(textGeneral + ".className")
        .asArray()
        .containsExactly("solr.TextField");
    assertThat(schema)
        .extractingPath(textGeneral + ".parameters")
        .asArray()
        .containsExactly(Map.of("positionIncrementGap", "100"));
    assertThat(schema).extractingPath(textGeneral + ".attributes").asArray().isEmpty();
    assertThat(schema).extractingPath(textGeneral + ".class").asArray().isEmpty();
  }

  @Test
  @Order(13)
  void searchWithFacets() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    COLLECTION,
                    "query",
                    "*:*",
                    "facetFields",
                    List.of("id"),
                    "rows",
                    0)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).hasPath("$.facets.id");
  }

  @Test
  @Order(14)
  void indexCsvDocuments() {
    String csv =
        """
				id,title,author,category
				6,CSV Document One,Eve,csv-test
				7,CSV Document Two,Frank,csv-test
				""";

    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "index-csv-documents", Map.of("collection", COLLECTION, "csv", csv)));

    assertNotNull(result);
    assertNotError(result);
  }

  @Test
  @Order(15)
  void searchFindsAllDocumentsAfterCsvIndexing() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search", Map.of("collection", COLLECTION, "query", "*:*", "rows", 0)));

    assertEquals(7, getNumFound(result), "Should find 7 documents (5 JSON + 2 CSV)");
  }

  @Test
  @Order(16)
  void addFieldsToTestCollection() {
    List<Map<String, Object>> fields =
        List.of(
            Map.of(
                "name",
                "platform",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "release_year",
                "type",
                "pint",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "genres",
                "type",
                "strings",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true));

    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("add-fields", Map.of("collection", COLLECTION, "fields", fields)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result))
        .extractingPath("$.addedNames")
        .asArray()
        .contains("platform", "release_year", "genres");
  }

  @Test
  @Order(17)
  void indexDocumentWithNewFields() {
    String json =
        """
				[
				  {"id": "show-1", "title": "Breaking Bad", "author": "Vince Gilligan",
				   "category": "show", "platform": "Netflix",
				   "release_year": 2008, "genres": ["drama", "crime"]}
				]
				""";

    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "index-json-documents",
                Map.of("collection", COLLECTION, "documents", TestDocuments.json(json))));

    assertNotNull(result);
    assertNotError(result);
  }

  @Test
  @Order(18)
  void searchWithNewFieldFilters() {
    CallToolResult byPlatform =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    COLLECTION,
                    "query",
                    "*:*",
                    "filterQueries",
                    List.of("platform:Netflix"))));
    assertEquals(1, getNumFound(byPlatform), "Should find exactly 1 doc with platform=Netflix");

    CallToolResult byGenre =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    COLLECTION,
                    "query",
                    "*:*",
                    "filterQueries",
                    List.of("genres:crime"))));
    assertEquals(1, getNumFound(byGenre), "Multi-valued 'genres' should match on 'crime'");
  }

  // Self-contained markdown round-trip: independent of the other order-18 test
  // (the markdown document carries none of the fields its filter queries touch).
  @Test
  @Order(18)
  void indexMarkdownDocumentAndFindItById() {
    String markdown =
        """
				---
				id: md-doc-1
				author: Markdown Author
				---
				# Markdown Indexing Guide

				## Installation

				Index markdown documents through the MCP server.
				""";

    CallToolResult indexResult =
        mcpClient.callTool(
            new CallToolRequest(
                "index-markdown-documents",
                Map.of("collection", COLLECTION, "documents", List.of(markdown))));

    assertNotNull(indexResult);
    assertNotError(indexResult);
    assertTrue(
        extractText(indexResult).contains("Successfully indexed 1"),
        "Markdown indexing should report one indexed document: " + extractText(indexResult));

    CallToolResult searchResult =
        mcpClient.callTool(
            new CallToolRequest(
                "search", Map.of("collection", COLLECTION, "query", "id:md-doc-1")));
    assertEquals(
        1, getNumFound(searchResult), "Should find the markdown document by its front matter id");
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
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("create-collection", Map.of("name", SHOWS_COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).extractingPath("$.name").isEqualTo(SHOWS_COLLECTION);
    assertThat(json(result))
        .extractingPath("$.configSet")
        .isEqualTo(SHOWS_COLLECTION + ".AUTOCREATED");
  }

  @Test
  @Order(20)
  void addShowsSchema() throws Exception {
    // Mirrors the user's original curl example: 16 fields covering text_general,
    // string, strings (multi-valued), pint, and pdouble types — the schema the
    // JSON dataset is shaped for.
    //
    // Practical wrinkle: create-collection gives shows a private copy of
    // _default, but other test classes in the same JVM create collections
    // bound to _default itself through SolrJ, and their schemaless guesses
    // persist there. The copy inherits them, so when the freshly-created shows
    // collection's schema is read, a subset of the desired fields may already
    // exist. The tool description on add-fields tells the LLM to "Call
    // get-schema first to inspect existing field configuration before adding"
    // — this test does exactly that, then only adds the gap.
    List<Map<String, Object>> desiredFields =
        List.of(
            Map.of("name", "title", "type", "text_general", "stored", true, "indexed", true),
            Map.of(
                "name",
                "platform",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "genres",
                "type",
                "strings",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "release_year",
                "type",
                "pint",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "end_year",
                "type",
                "pint",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "status",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "seasons",
                "type",
                "pint",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "episodes",
                "type",
                "pint",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of("name", "creators", "type", "strings", "stored", true, "indexed", true),
            Map.of("name", "cast", "type", "strings", "stored", true, "indexed", true),
            Map.of(
                "name",
                "country",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "language",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "rating",
                "type",
                "string",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of(
                "name",
                "imdb_rating",
                "type",
                "pdouble",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true),
            Map.of("name", "description", "type", "text_general", "stored", true, "indexed", true),
            Map.of(
                "name",
                "tags",
                "type",
                "strings",
                "stored",
                true,
                "indexed",
                true,
                "docValues",
                true));

    Set<String> existingFieldNames = fetchExistingFieldNames(SHOWS_COLLECTION);

    List<Map<String, Object>> fieldsToAdd =
        desiredFields.stream()
            .filter(f -> !existingFieldNames.contains(String.valueOf(f.get("name"))))
            .toList();

    // Edge case: every desired field already exists in the shared configset
    // (extremely unlikely given the shows-specific names). Soft-pass; the
    // schema is already in the state we want.
    if (fieldsToAdd.isEmpty()) {
      return;
    }

    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "add-fields", Map.of("collection", SHOWS_COLLECTION, "fields", fieldsToAdd)));

    assertNotNull(result);
    assertNotError(result);
    // Spot-check that at least one shows-specific field was added (these won't
    // have leaked from any earlier collection's schema).
    assertThat(json(result))
        .extractingPath("$.addedNames")
        .asArray()
        .containsAnyOf("seasons", "episodes", "imdb_rating");
  }

  private Set<String> fetchExistingFieldNames(String collection) {
    CallToolResult schemaResult =
        mcpClient.callTool(new CallToolRequest("get-schema", Map.of("collection", collection)));
    assertNotError(schemaResult);
    List<String> names = read(schemaResult, "$.fields[*].name");
    return Set.copyOf(names);
  }

  @Test
  @Order(21)
  void indexShowsFromClasspathResource() throws Exception {
    String showsJson = loadClasspathResource("/shows.json");
    assertFalse(showsJson.isBlank(), "shows.json resource must not be blank");

    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "index-json-documents",
                Map.of(
                    "collection", SHOWS_COLLECTION, "documents", TestDocuments.json(showsJson))));

    assertNotNull(result);
    assertNotError(result);
  }

  @Test
  @Order(22)
  void searchAllShowsAndAssertCount() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search", Map.of("collection", SHOWS_COLLECTION, "query", "*:*", "rows", 0)));

    assertNotNull(result);
    assertNotError(result);
    assertEquals(
        SHOWS_DOC_COUNT,
        getNumFound(result),
        "Should find all " + SHOWS_DOC_COUNT + " shows after indexing");
  }

  @Test
  @Order(23)
  void searchShowsWithFacetByPlatform() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "query",
                    "*:*",
                    "facetFields",
                    List.of("platform"),
                    "rows",
                    0)));

    assertNotNull(result);
    assertNotError(result);
    JsonContent response = json(result);
    // The dataset has 20 Netflix shows and 20 Amazon Prime Video shows.
    assertThat(response).extractingPath("$.facets.platform.Netflix").isEqualTo(20);
    assertThat(response).extractingPath("$.facets.platform['Amazon Prime Video']").isEqualTo(20);
    assertThat(response).hasPath("$.facets.platform['HBO Max']");
  }

  @Test
  @Order(24)
  void searchShowsWithFilterAndKeyword() {
    // Filter to one platform, then full-text search the description field.
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "query",
                    "description:apocalyptic",
                    "filterQueries",
                    List.of("platform:\"Amazon Prime Video\""),
                    "rows",
                    10)));

    assertNotNull(result);
    assertNotError(result);
    // "Fallout" on Amazon Prime Video has "post-apocalyptic" in its description.
    assertThat(getNumFound(result))
        .as("apocalyptic shows on Amazon Prime Video")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  @Order(25)
  void searchShowsByMultiValuedGenre() {
    // Multi-valued strings field: filter on a single genre value.
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "query",
                    "*:*",
                    "filterQueries",
                    List.of("genres:Sci-Fi"),
                    "rows",
                    0)));

    assertNotNull(result);
    assertNotError(result);
    // At least Stranger Things, Dark, Black Mirror, The Umbrella Academy, The
    // Boys, The Expanse, Fallout, Upload, The Last of Us, Westworld, WandaVision,
    // The Mandalorian, Andor, Loki, Severance, Foundation, Star Trek SNW — well
    // over 10 docs.
    assertThat(getNumFound(result)).as("Sci-Fi shows").isGreaterThanOrEqualTo(10);
  }

  @Test
  @Order(26)
  void getShowsSchemaIncludesAllAddedFields() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("get-schema", Map.of("collection", SHOWS_COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    // All 16 user-defined fields must appear in the schema response. Spot-check a
    // representative selection across types: text_general, string, strings,
    // pint, pdouble.
    assertThat(json(result))
        .extractingPath("$.fields[*].name")
        .asArray()
        .contains(
            "title", "platform", "genres", "release_year", "imdb_rating", "description", "tags");
  }

  @Test
  @Order(27)
  void getShowsCollectionStats() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("get-collection-stats", Map.of("collection", SHOWS_COLLECTION)));

    assertNotNull(result);
    assertNotError(result);
    assertThat(json(result)).extractingPath("$.index.numDocs").isEqualTo(SHOWS_DOC_COUNT);
  }

  // ===== Prompt workflow (orders 28–34) =====
  // Verifies the six @McpPrompt endpoints are discovered, listable, and return
  // non-empty guidance referencing the right tools when fetched. Prompts are
  // LLM-facing instruction templates — the framework wraps the String returned by
  // each @McpPrompt method as a single user-role PromptMessage.

  @Test
  @Order(28)
  void listPromptsReturnsExpectedPrompts() {
    var promptsResult = mcpClient.listPrompts();
    assertNotNull(promptsResult);
    List<String> promptNames = promptsResult.prompts().stream().map(p -> p.name()).toList();

    for (String expected :
        List.of(
            "explore-collections",
            "setup-collection",
            "view-schema",
            "design-schema",
            "index-data",
            "search-collection")) {
      assertTrue(
          promptNames.contains(expected), "Should expose " + expected + " prompt: " + promptNames);
    }
  }

  @Test
  @Order(29)
  void getExploreCollectionsPromptReturnsGuidance() {
    GetPromptResult result =
        mcpClient.getPrompt(new GetPromptRequest("explore-collections", Map.of()));

    String text = extractFirstMessageText(result);
    assertTrue(
        text.contains("list-collections"),
        "Prompt body should reference list-collections: " + text);
    assertTrue(
        text.contains("get-collection-stats"),
        "Prompt body should reference get-collection-stats: " + text);
    assertFalse(
        text.contains("create-collection"),
        "Explore prompt is read-only; should not reference create-collection: " + text);
  }

  @Test
  @Order(30)
  void getSetupCollectionPromptReturnsGuidance() {
    GetPromptResult result =
        mcpClient.getPrompt(
            new GetPromptRequest(
                "setup-collection",
                Map.of(
                    "name", "scratch_collection", "purpose", "Testing setup-collection prompt")));

    String text = extractFirstMessageText(result);
    assertTrue(
        text.contains("scratch_collection"),
        "Prompt body should embed the collection name: " + text);
    assertTrue(
        text.contains("Testing setup-collection prompt"),
        "Prompt body should embed the purpose: " + text);
    assertTrue(
        text.contains("create-collection"),
        "Prompt body should reference create-collection tool: " + text);
    assertTrue(
        text.contains("_default"), "Prompt body should mention the default configset: " + text);
  }

  @Test
  @Order(31)
  void getViewSchemaPromptReturnsGuidance() {
    GetPromptResult result =
        mcpClient.getPrompt(
            new GetPromptRequest("view-schema", Map.of("collection", SHOWS_COLLECTION)));

    String text = extractFirstMessageText(result);
    assertTrue(
        text.contains(SHOWS_COLLECTION), "Prompt body should embed the collection name: " + text);
    assertTrue(text.contains("get-schema"), "Prompt body should reference get-schema: " + text);
    assertFalse(
        text.contains("add-fields"),
        "View prompt is read-only; should not reference add-fields: " + text);
  }

  @Test
  @Order(32)
  void getDesignSchemaPromptReturnsGuidance() {
    GetPromptResult result =
        mcpClient.getPrompt(
            new GetPromptRequest(
                "design-schema",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "datasetDescription",
                    "TV shows with title, platform, genres")));

    String text = extractFirstMessageText(result);
    assertTrue(
        text.contains(SHOWS_COLLECTION), "Prompt body should embed the collection name: " + text);
    assertTrue(text.contains("add-fields"), "Prompt body should reference add-fields: " + text);
    assertTrue(
        text.contains("add-field-types"), "Prompt body should reference add-field-types: " + text);
  }

  @Test
  @Order(33)
  void getIndexDataPromptReturnsGuidance() {
    GetPromptResult result =
        mcpClient.getPrompt(
            new GetPromptRequest(
                "index-data", Map.of("collection", SHOWS_COLLECTION, "format", "json")));

    String text = extractFirstMessageText(result);
    assertTrue(
        text.contains("index-json-documents"),
        "Prompt body should select index-json-documents for json format: " + text);
    assertTrue(
        text.contains("get-schema"),
        "Prompt body should reference get-schema verification: " + text);
  }

  @Test
  @Order(34)
  void getSearchCollectionPromptReturnsGuidance() {
    GetPromptResult result =
        mcpClient.getPrompt(
            new GetPromptRequest(
                "search-collection",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "question",
                    "What sci-fi shows are on Netflix?")));

    String text = extractFirstMessageText(result);
    assertTrue(
        text.contains(SHOWS_COLLECTION), "Prompt body should embed the collection name: " + text);
    assertTrue(
        text.contains("What sci-fi shows are on Netflix?"),
        "Prompt body should embed the user question: " + text);
    assertTrue(text.contains("filterQueries"), "Prompt body should explain filterQueries: " + text);
  }

  // ===== Collection-completion workflow (orders 35–36) =====

  @Test
  @Order(35)
  void completeCollection_ReturnsCreatedCollection() {
    ResourceReference ref = new ResourceReference("solr://{collection}/schema");
    CompleteRequest request =
        new CompleteRequest(
            ref, new CompleteRequest.CompleteArgument("collection", COLLECTION.substring(0, 3)));

    CompleteResult result = mcpClient.completeCompletion(request);

    assertNotNull(result);
    assertNotNull(result.completion());
    List<String> values = result.completion().values();
    assertNotNull(values);
    assertTrue(
        values.contains(COLLECTION),
        "Completion should include the previously created collection: " + values);
  }

  @Test
  @Order(36)
  void completeCollection_NoMatchesReturnsEmptyValues() {
    ResourceReference ref = new ResourceReference("solr://{collection}/schema");
    CompleteRequest request =
        new CompleteRequest(
            ref, new CompleteRequest.CompleteArgument("collection", "no-such-prefix-zzz"));

    CompleteResult result = mcpClient.completeCompletion(request);

    assertNotNull(result);
    assertNotNull(result.completion());
    assertTrue(
        result.completion().values().isEmpty(),
        "No collections should match an unknown prefix: " + result.completion().values());
  }

  // ===== Prompt-arg completion (orders 37–38) =====
  //
  // Regression coverage for the MCP Inspector bug where opening a prompt that
  // takes a `collection` argument raised "-32602: AsyncCompletionSpecification
  // not found: PromptReference[...]". The fix registers @McpComplete(prompt=...)
  // handlers in addition to the existing resource-template handler; without
  // them, completion/complete with a ref/prompt reference has no binding.

  @Test
  @Order(37)
  void completePromptArg_SearchCollection_ReturnsCreatedCollection() {
    PromptReference ref = new PromptReference("search-collection");
    CompleteRequest request =
        new CompleteRequest(
            ref, new CompleteRequest.CompleteArgument("collection", COLLECTION.substring(0, 3)));

    CompleteResult result = mcpClient.completeCompletion(request);

    assertNotNull(result);
    assertNotNull(result.completion());
    List<String> values = result.completion().values();
    assertNotNull(values);
    assertTrue(
        values.contains(COLLECTION),
        "Prompt completion should include the previously created collection: " + values);
  }

  @Test
  @Order(38)
  void completePromptArg_ViewSchema_ReturnsCreatedCollection() {
    PromptReference ref = new PromptReference("view-schema");
    CompleteRequest request =
        new CompleteRequest(
            ref, new CompleteRequest.CompleteArgument("collection", COLLECTION.substring(0, 3)));

    CompleteResult result = mcpClient.completeCompletion(request);

    assertNotNull(result);
    assertNotNull(result.completion());
    assertTrue(
        result.completion().values().contains(COLLECTION),
        "view-schema prompt completion should resolve the created collection: "
            + result.completion().values());
  }

  @Test
  @Order(39)
  void blankOptionalSearchEntriesAreIgnoredThroughMcp() {
    var result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "query",
                    "*:*",
                    "rows",
                    0,
                    "filterQueries",
                    List.of(" "),
                    "facetFields",
                    List.of("", "platform"),
                    "sortClauses",
                    List.of(Map.of("field", "", "order", "")))));
    assertNotError(result);
    assertEquals(SHOWS_DOC_COUNT, getNumFound(result));
    assertThat(json(result)).extractingPath("$.facets.platform.Netflix").isEqualTo(20);
  }

  @Test
  @Order(40)
  void searchFailureIsAnActionableMcpToolError() {
    var result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "facetFields",
                    List.of("nonexistent_field_xyz"))));
    assertEquals(Boolean.TRUE, result.isError());
    assertTrue(extractText(result).contains("list-fields"), extractText(result));
    assertFalse(extractText(result).contains("Exception"), extractText(result));
  }

  // ===== list-fields (orders 41–43) =====

  @Test
  @Order(41)
  void listShowsFieldsReturnsIndexedFields() {
    CallToolResult result =
        mcpClient.callTool(
            new CallToolRequest("list-fields", Map.of("collection", SHOWS_COLLECTION)));

    assertNotError(result);
    JsonContent listing = json(result);
    assertThat(listing)
        .extractingPath("$[*].name")
        .asArray()
        .contains("id", "title", "platform", "genres", "release_year");

    // Through Spring AI's serialization: Luke's flag letters arrive decoded as the contract's
    // property names, with neither the letters nor Luke's info legend left over.
    String id = "$[?(@.name == 'id')]";
    assertThat(listing).extractingPath(id + ".flags[*]").asArray().contains("indexed", "stored");
    assertThat(listing).extractingPath(id + ".schema").asArray().isEmpty();
  }

  @Test
  @Order(42)
  void fieldsResourceMatchesListFieldsTool() {
    CallToolResult tool =
        mcpClient.callTool(
            new CallToolRequest("list-fields", Map.of("collection", SHOWS_COLLECTION)));
    ReadResourceResult resource =
        mcpClient.readResource(new ReadResourceRequest("solr://" + SHOWS_COLLECTION + "/fields"));

    assertInstanceOf(TextResourceContents.class, resource.contents().getFirst());
    String resourceText = ((TextResourceContents) resource.contents().getFirst()).text();
    List<String> toolFields = read(tool, "$[*].name");
    List<String> resourceFields = JsonPath.read(resourceText, "$[*].name");
    assertThat(toolFields).containsExactlyInAnyOrderElementsOf(resourceFields);
  }

  @Test
  @Order(43)
  void completeFieldsResourceCollection_ReturnsCreatedCollection() {
    ResourceReference ref = new ResourceReference("solr://{collection}/fields");
    CompleteRequest request =
        new CompleteRequest(
            ref, new CompleteRequest.CompleteArgument("collection", COLLECTION.substring(0, 3)));

    List<String> values = mcpClient.completeCompletion(request).completion().values();

    assertTrue(
        values.contains(COLLECTION),
        "Fields-resource completion should include the created collection: " + values);
  }

  @Test
  @Order(41)
  void searchWithQueryFieldsAndFieldsThroughMcp() {
    // Plain words across two text fields, returning only the title.
    var result =
        mcpClient.callTool(
            new CallToolRequest(
                "search",
                Map.of(
                    "collection",
                    SHOWS_COLLECTION,
                    "query",
                    "apocalyptic",
                    "queryFields",
                    List.of("title", "description"),
                    "fields",
                    List.of("title"),
                    "rows",
                    5)));
    assertNotError(result);
    // "Fallout" has "post-apocalyptic" in its description.
    assertThat(getNumFound(result)).as("apocalyptic shows").isGreaterThanOrEqualTo(1);
    List<Map<String, Object>> documents = read(result, "$.documents[*]");
    // fields limits each document to title plus score
    assertThat(documents)
        .allSatisfy(document -> assertThat(document).containsOnlyKeys("title", "score"));
  }

  @Test
  @Order(45)
  void schemaResourceFailureIsAnMcpErrorWithoutTheSolrUrl() {
    // An unknown collection makes SolrJ raise "Error from server at <solr url>:
    // ...".
    McpError e =
        assertThrows(
            McpError.class,
            () ->
                mcpClient.readResource(
                    new ReadResourceRequest("solr://no_such_collection_xyz/schema")));
    String text = e.getMessage() + " " + e.getJsonRpcError();
    assertTrue(text.contains("Failed to retrieve the schema"), text);
    assertFalse(text.contains("Error from server"), text);
    assertFalse(text.contains("http://") || text.contains("https://"), text);
  }

  private static String extractFirstMessageText(GetPromptResult result) {
    List<PromptMessage> messages = result.messages();
    assertFalse(messages.isEmpty(), "messages must not be empty");
    Content content = messages.getFirst().content();
    assertInstanceOf(
        TextContent.class, content, "first prompt message content should be TextContent");
    String text = ((TextContent) content).text();
    assertFalse(text.isBlank(), "prompt message text should not be blank");
    return text;
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
    Content first = result.content().getFirst();
    assertInstanceOf(TextContent.class, first, "Content should be TextContent");
    return ((TextContent) first).text();
  }

  protected static void assertNotError(CallToolResult result) {
    if (Boolean.TRUE.equals(result.isError())) {
      // Do not cast blindly: a non-text error payload would raise
      // ClassCastException here and hide the actual failure message.
      String errorText =
          result.content().isEmpty()
              ? "unknown error"
              : switch (result.content().getFirst()) {
                case TextContent text -> text.text();
                case Object other -> "non-text error content: " + other;
              };
      fail("MCP tool call returned error: " + errorText);
    }
  }

  /** The tool result's text as JSON, for JsonPath assertions. */
  protected static JsonContent json(CallToolResult result) {
    return new JsonContent(extractText(result));
  }

  /** The value at a JsonPath in the tool result's JSON text. */
  protected static <T> T read(CallToolResult result, String path) {
    return JsonPath.read(extractText(result), path);
  }

  protected static int getNumFound(CallToolResult result) {
    return read(result, "$.numFound");
  }
}
