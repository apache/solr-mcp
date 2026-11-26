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
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Lists and calls tools over the modern (2026-07-28) protocol as raw JSON-RPC, since the SDK has no
 * modern client yet.
 */
final class ModernMcpToolClient {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final IntSupplier port;

  /**
   * @param port the test server's port, read on every request
   */
  ModernMcpToolClient(IntSupplier port) {
    this.port = port;
  }

  /**
   * The result object of one request, failing on any HTTP status but 200 and on a JSON-RPC error.
   */
  private Map<String, Object> call(String method, Map<String, Object> params, @Nullable String name)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port.getAsInt() + "/mcp"))
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

  /** The {@code tools} array of a {@code tools/list} result. */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> listTools() throws Exception {
    return (List<Map<String, Object>>) call("tools/list", Map.of(), null).get("tools");
  }

  /** The result object of a {@code tools/call}. */
  Map<String, Object> callTool(String name, Map<String, Object> arguments) throws Exception {
    return call("tools/call", Map.of("name", name, "arguments", arguments), name);
  }
}
