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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Calls every Solr-backed MCP tool while Solr is unreachable and checks what the client receives.
 * SolrJ reports a refused connection as a checked {@code SolrServerException}, which is the path
 * this test pins: the client must get a readable tool error naming the tool, not a bare JSON-RPC
 * protocol error, and the text must not reveal the internal Solr address.
 *
 * <p>No Testcontainers import: {@code solr.url} points at a port nothing listens on, and {@code
 * SolrConfig} makes no Solr call at startup, so the context starts without Solr.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "http.security.enabled=false",
      "spring.docker.compose.enabled=false",
      "solr.url=" + McpToolBackendUnavailableIntegrationTest.UNREACHABLE_SOLR_URL
    })
@ActiveProfiles("http")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolBackendUnavailableIntegrationTest {

  static final String UNREACHABLE_SOLR_URL = "http://127.0.0.1:1/solr/";

  private static final String COLLECTION = "unreachable";

  @LocalServerPort private int port;

  private McpSyncClient mcpClient;

  @BeforeAll
  void beforeAll() {
    var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build();
    mcpClient = McpClient.sync(transport).build();
    mcpClient.initialize();
  }

  @AfterAll
  void afterAll() {
    if (mcpClient != null) {
      mcpClient.close();
    }
  }

  static Stream<Arguments> solrBackedTools() {
    return Stream.of(
        Arguments.of("list-collections", Map.of()),
        Arguments.of("get-collection-stats", Map.of("collection", COLLECTION)),
        Arguments.of("create-collection", Map.of("name", COLLECTION)),
        Arguments.of("list-aliases", Map.of()),
        Arguments.of("create-alias", Map.of("aliasName", "alias", "collections", COLLECTION)),
        Arguments.of("delete-alias", Map.of("aliasName", "alias")),
        Arguments.of("get-schema", Map.of("collection", COLLECTION)),
        Arguments.of(
            "add-fields",
            Map.of(
                "collection",
                COLLECTION,
                "fields",
                List.of(Map.of("name", "f", "type", "string")))),
        Arguments.of(
            "add-field-types",
            Map.of(
                "collection",
                COLLECTION,
                "fieldTypes",
                List.of(Map.of("name", "t", "class", "solr.StrField")))),
        Arguments.of(
            "index-json-documents",
            Map.of("collection", COLLECTION, "documents", List.of(Map.of("id", "1")))),
        Arguments.of("index-csv-documents", Map.of("collection", COLLECTION, "csv", "id\n1\n")),
        Arguments.of(
            "index-xml-documents",
            Map.of(
                "collection",
                COLLECTION,
                "xml",
                "<add><doc><field name=\"id\">1</field></doc></add>")),
        Arguments.of(
            "index-markdown-documents",
            Map.of("collection", COLLECTION, "documents", List.of("# Title\n\nBody"))),
        Arguments.of("search", Map.of("collection", COLLECTION)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("solrBackedTools")
  void unreachableSolrIsAReadableToolError(String tool, Map<String, Object> arguments) {
    CallToolResult result = mcpClient.callTool(new CallToolRequest(tool, arguments));

    StringBuilder text = new StringBuilder();
    for (Content content : result.content()) {
      if (content instanceof TextContent textContent) {
        text.append(textContent.text()).append('\n');
      }
    }
    assertToolError(tool, result, text.toString());
  }

  private static void assertToolError(String tool, CallToolResult result, String text) {
    assertEquals(Boolean.TRUE, result.isError(), text);
    assertTrue(text.startsWith(tool + " failed"), text);
    assertTrue(text.contains("unable to communicate with Solr"), text);
    assertFalse(text.contains("127.0.0.1:1"), text);
  }
}
