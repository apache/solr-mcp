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
package org.apache.solr.mcp.server.eval;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.solr.mcp.server.TestDocuments;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The evals' Spring context. Auto-configuration supplies the model, with Spring AI's OpenAI
 * defaults, and an MCP client that starts solr-mcp over STDIO, as configured in {@code
 * application-integration-test.properties}. Solr runs in Testcontainers.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(TestcontainersConfiguration.class)
class EvalConfiguration {

  static final String COLLECTION = "devnexus";

  static final Path SESSIONS = Path.of("mydata/devnexus-2026.json");

  /**
   * The agent: the server's instructions and tools, and nothing else. {@link ToolCallAuditAdvisor}
   * records the tool calls it makes.
   */
  @Bean
  ChatClient chatClient(
      ChatClient.Builder chatClientBuilder,
      List<McpSyncClient> mcpClients,
      SyncMcpToolCallbackProvider mcpTools) {
    String instructions =
        Objects.requireNonNull(
            mcpClients.getFirst().getServerInstructions(), "solr-mcp sent no instructions");
    return chatClientBuilder
        .defaultSystem(instructions)
        .defaultTools(mcpTools)
        .defaultAdvisors(new ToolCallAuditAdvisor())
        .build();
  }

  /** Loads the DevNexus 2026 sessions through the server's own tools. */
  @Bean
  ApplicationRunner indexSessions(List<McpSyncClient> mcpClients) {
    return args -> {
      McpSyncClient solrMcp = mcpClients.getFirst();
      callTool(solrMcp, "create-collection", Map.of("name", COLLECTION));
      callTool(
          solrMcp,
          "index-json-documents",
          Map.of(
              "collection",
              COLLECTION,
              "documents",
              TestDocuments.json(Files.readString(SESSIONS))));
    };
  }

  /** Calls a tool outside any model run, failing if the tool reports an error. */
  static CallToolResult callTool(McpSyncClient client, String tool, Map<String, Object> arguments) {
    CallToolResult result = client.callTool(new CallToolRequest(tool, arguments));
    if (Boolean.TRUE.equals(result.isError())) {
      throw new IllegalStateException(tool + " failed: " + result.content());
    }
    return result;
  }
}
