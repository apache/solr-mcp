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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;

/** The contract conformance checks over the legacy (2025) protocol, through the SDK client. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "http.security.enabled=false",
      "spring.docker.compose.enabled=false",
      // Its own context, not McpClientIntegrationTest's: a context Spring paused between test
      // classes and resumed for another class can leave the MCP transport shut down.
      "solr.mcp.test.context=contract-conformance"
    })
@ActiveProfiles("http")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class McpContractConformanceIntegrationTest extends McpContractConformanceTestBase {

  @LocalServerPort private int port;

  private @Nullable McpSyncClient client;

  private McpSyncClient client() {
    McpSyncClient current = client;
    if (current == null) {
      current =
          McpClient.sync(
                  HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build())
              .build();
      current.initialize();
      client = current;
    }
    return current;
  }

  @AfterAll
  void closeClient() {
    if (client != null) {
      client.closeGracefully();
    }
  }

  @Override
  protected List<Map<String, Object>> listTools() {
    return jsonMapper.convertValue(client().listTools().tools(), new TypeReference<>() {});
  }

  @Override
  protected Map<String, Object> callTool(String name, Map<String, Object> arguments) {
    return jsonMapper.convertValue(
        client().callTool(new CallToolRequest(name, arguments)), new TypeReference<>() {});
  }
}
