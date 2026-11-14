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
package org.apache.solr.mcp.server.modern;

import static org.apache.solr.mcp.server.modern.ModernMcpRequests.PROTOCOL_VERSION;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.firstText;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.parse;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.request;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.apache.solr.mcp.server.TestDocuments;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The HTTP server on the MCP 2026-07-28 revision, selected with {@code MCP_PROTOCOL=modern}.
 *
 * <p>Covers every primitive the server exposes (tools, resources, resource templates, prompts and
 * completions) over the single POST endpoint, plus the parts of the revision that differ from the
 * stateless transport: {@code server/discover} instead of {@code initialize}, and the {@code
 * Mcp-Method} / {@code Mcp-Name} headers that must match the body.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "http.security.enabled=false",
      "spring.docker.compose.enabled=false",
      "MCP_PROTOCOL=modern"
    })
@ActiveProfiles("http")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpModernProtocolHttpIntegrationTest {

  private static final String COLLECTION = "modern-protocol-test";

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  private HttpResponse<String> post(
      String method, Map<String, Object> params, @Nullable String mcpName) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Method", method)
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(request(method, params)));
    if (mcpName != null) {
      builder.header("Mcp-Name", mcpName);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> call(
      String method, Map<String, Object> params, @Nullable String mcpName) throws Exception {
    HttpResponse<String> response = post(method, params, mcpName);
    assertEquals(200, response.statusCode(), () -> method + " failed: " + response.body());
    return result(response.body());
  }

  private Map<String, Object> callTool(String name, Map<String, Object> arguments)
      throws Exception {
    Map<String, Object> result =
        call("tools/call", Map.of("name", name, "arguments", arguments), name);
    assertEquals(
        Boolean.FALSE, result.get("isError"), () -> name + " returned an error: " + result);
    return result;
  }

  @Test
  @Order(1)
  @SuppressWarnings("unchecked")
  void discoverAdvertisesTheRevisionAndEveryCapability() throws Exception {
    Map<String, Object> discover = call("server/discover", Map.of(), null);

    assertEquals(List.of(PROTOCOL_VERSION), discover.get("supportedVersions"));
    Map<String, Object> capabilities = (Map<String, Object>) discover.get("capabilities");
    for (String capability : List.of("tools", "resources", "prompts", "completions")) {
      assertTrue(
          capabilities.containsKey(capability),
          () -> "server/discover must advertise " + capability + ": " + capabilities);
    }
    Map<String, Object> serverInfo =
        (Map<String, Object>)
            ((Map<String, Object>) discover.get("_meta")).get("io.modelcontextprotocol/serverInfo");
    assertEquals("solr-mcp", serverInfo.get("name"));
    assertTrue(
        ((String) discover.get("instructions")).contains("list-collections"),
        "the server instructions must reach the client through server/discover");
  }

  @Test
  @Order(2)
  @SuppressWarnings("unchecked")
  void toolsListKeepsEveryToolAndItsHints() throws Exception {
    List<Map<String, Object>> tools =
        (List<Map<String, Object>>) call("tools/list", Map.of(), null).get("tools");
    List<Object> names = tools.stream().map(t -> t.get("name")).toList();

    for (String tool :
        List.of(
            "search",
            "list-collections",
            "get-collection-stats",
            "check-health",
            "create-collection",
            "index-json-documents",
            "index-markdown-documents",
            "get-schema",
            "add-fields",
            "add-field-types")) {
      assertTrue(names.contains(tool), () -> "missing tool " + tool + " in " + names);
    }
    Map<String, Object> search =
        tools.stream().filter(t -> "search".equals(t.get("name"))).findFirst().orElseThrow();
    assertEquals(
        Boolean.TRUE,
        ((Map<String, Object>) search.get("annotations")).get("readOnlyHint"),
        "behavior hints must survive the conversion to the modern schema");
  }

  @Test
  @Order(3)
  void toolsCallCreatesIndexesAndSearches() throws Exception {
    callTool("create-collection", Map.of("name", COLLECTION));
    callTool(
        "index-json-documents",
        Map.of(
            "collection",
            COLLECTION,
            "documents",
            TestDocuments.json(
                """
                [
                  {"id": "1", "title": "Modern MCP", "category": "protocol"},
                  {"id": "2", "title": "Stateless servers", "category": "protocol"},
                  {"id": "3", "title": "Solr basics", "category": "search"}
                ]
                """)));

    String text =
        firstText(
            callTool(
                "search",
                Map.of(
                    "collection",
                    COLLECTION,
                    "query",
                    "*:*",
                    "filterQueries",
                    List.of("category:protocol"))));

    Map<String, Object> response = parse(text);
    assertEquals(2, ((Number) response.get("numFound")).intValue(), text);
  }

  @Test
  @Order(4)
  @SuppressWarnings("unchecked")
  void resourcesAndTemplatesAreReadable() throws Exception {
    List<Map<String, Object>> resources =
        (List<Map<String, Object>>) call("resources/list", Map.of(), null).get("resources");
    assertTrue(resources.stream().anyMatch(r -> "solr://collections".equals(r.get("uri"))));

    List<Map<String, Object>> templates =
        (List<Map<String, Object>>)
            call("resources/templates/list", Map.of(), null).get("resourceTemplates");
    assertTrue(
        templates.stream()
            .anyMatch(t -> "solr://{collection}/schema".equals(t.get("uriTemplate"))));

    String schemaUri = "solr://" + COLLECTION + "/schema";
    List<Map<String, Object>> contents =
        (List<Map<String, Object>>)
            call("resources/read", Map.of("uri", schemaUri), schemaUri).get("contents");
    assertTrue(
        ((String) contents.getFirst().get("text")).contains("fields"),
        "the schema resource template must resolve against the live collection");
  }

  @Test
  @Order(5)
  @SuppressWarnings("unchecked")
  void promptsAndCompletionsWork() throws Exception {
    List<Map<String, Object>> prompts =
        (List<Map<String, Object>>) call("prompts/list", Map.of(), null).get("prompts");
    assertTrue(prompts.stream().anyMatch(p -> "search-collection".equals(p.get("name"))));

    Map<String, Object> prompt =
        call(
            "prompts/get",
            Map.of(
                "name",
                "search-collection",
                "arguments",
                Map.of("collection", COLLECTION, "question", "protocol docs")),
            "search-collection");
    assertFalse(((List<Object>) prompt.get("messages")).isEmpty());

    Map<String, Object> completion =
        (Map<String, Object>)
            call(
                    "completion/complete",
                    Map.of(
                        "ref",
                        Map.of("type", "ref/prompt", "name", "search-collection"),
                        "argument",
                        Map.of("name", "collection", "value", "modern")),
                    null)
                .get("completion");
    assertTrue(
        ((List<Object>) completion.get("values")).contains(COLLECTION),
        () -> "collection-name completion must offer the new collection: " + completion);
  }

  @Test
  @Order(6)
  void mcpNameHeaderMustMatchTheBody() throws Exception {
    HttpResponse<String> response =
        post(
            "tools/call",
            Map.of("name", "list-collections", "arguments", Map.of()),
            "search" /* deliberately not the tool in the body */);

    assertEquals(400, response.statusCode(), response.body());
    assertEquals(-32020, errorCode(response.body()), "the revision's header-mismatch error code");
  }

  @Test
  @Order(7)
  void legacyInitializeIsNotServed() throws Exception {
    HttpResponse<String> response = post("initialize", Map.of(), null);

    // The 2026-07-28 revision removes the handshake, so a pre-2026 client cannot connect to a
    // server in modern mode. That is why MCP_PROTOCOL defaults to stateless.
    assertNotEquals(200, response.statusCode(), response.body());
  }

  @SuppressWarnings("unchecked")
  private static int errorCode(String body) {
    return ((Number) ((Map<String, Object>) parse(body).get("error")).get("code")).intValue();
  }
}
