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
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.request;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.result;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.apache.solr.mcp.server.McpContractConformanceTestBase;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The contract conformance checks over the modern (2026-07-28) protocol, as raw JSON-RPC. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "http.security.enabled=false",
      "spring.docker.compose.enabled=false",
      "MCP_PROTOCOL=modern",
      // Its own context, not McpModernProtocolHttpIntegrationTest's: Spring pauses a cached
      // context between test classes, and the modern HTTP transport answers 503 "Server is
      // shutting down" to the class that resumes it.
      "solr.mcp.test.context=modern-contract-conformance"
    })
@ActiveProfiles("http")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class McpModernContractConformanceIntegrationTest extends McpContractConformanceTestBase {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  private Map<String, Object> call(String method, Map<String, Object> params, @Nullable String name)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Method", method)
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(request(method, params)));
    if (name != null) {
      builder.header("Mcp-Name", name);
    }
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), () -> method + " failed: " + response.body());
    return result(response.body());
  }

  @Override
  @SuppressWarnings("unchecked")
  protected List<Map<String, Object>> listTools() throws Exception {
    return (List<Map<String, Object>>) call("tools/list", Map.of(), null).get("tools");
  }

  @Override
  protected Map<String, Object> callTool(String name, Map<String, Object> arguments)
      throws Exception {
    return call("tools/call", Map.of("name", name, "arguments", arguments), name);
  }
}
