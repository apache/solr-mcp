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
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lists and calls tools over the legacy (2025) protocol through the MCP SDK's client, returning the
 * raw JSON-RPC shapes so legacy and modern tests check the same expectations.
 *
 * <p>The client connects on first use, because the test server's port is injected only after the
 * test instance that owns this client is built.
 */
public final class LegacyMcpToolClient implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final IntSupplier port;

  private @Nullable McpSyncClient client;

  /**
   * @param port the test server's port, read on first use
   */
  public LegacyMcpToolClient(IntSupplier port) {
    this.port = port;
  }

  private McpSyncClient client() {
    McpSyncClient current = client;
    if (current == null) {
      current =
          McpClient.sync(
                  HttpClientStreamableHttpTransport.builder("http://localhost:" + port.getAsInt())
                      .build())
              .build();
      current.initialize();
      client = current;
    }
    return current;
  }

  /** The {@code tools} array of a {@code tools/list} result. */
  public List<Map<String, Object>> listTools() {
    return JSON.convertValue(client().listTools().tools(), new TypeReference<>() {});
  }

  /**
   * The result object of a {@code tools/call}. A JSON-RPC error throws the SDK's {@code McpError},
   * so a test that expects a result fails on one.
   */
  public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
    return JSON.convertValue(
        client().callTool(new CallToolRequest(name, arguments)), new TypeReference<>() {});
  }

  @Override
  public void close() {
    if (client != null) {
      client.closeGracefully();
    }
  }
}
