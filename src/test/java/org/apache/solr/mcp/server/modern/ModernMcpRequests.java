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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and parses JSON-RPC messages for the MCP 2026-07-28 ("modern") protocol revision.
 *
 * <p>The MCP Java SDK that Spring AI 2.1 ships has a modern <em>server</em> but no modern client
 * yet, so these tests speak the wire format directly. Under the 2026-07-28 revision there is no
 * {@code initialize} handshake and no session: every request carries the protocol version and the
 * client capabilities in {@code params._meta}, and clients learn the server's features from {@code
 * server/discover}.
 */
final class ModernMcpRequests {

  /** The spec revision Spring AI 2.1.0-RC2 (MCP Java SDK 2.2.0-M2) serves by default. */
  static final String PROTOCOL_VERSION = "2026-07-28";

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final AtomicInteger IDS = new AtomicInteger();

  private ModernMcpRequests() {}

  /** A JSON-RPC request whose {@code params._meta} carries the version and capabilities. */
  static String request(String method, Map<String, Object> params) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
    meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());
    meta.put(
        "io.modelcontextprotocol/clientInfo", Map.of("name", "solr-mcp-tests", "version", "1"));
    Map<String, Object> withMeta = new HashMap<>(params);
    withMeta.put("_meta", meta);
    return JSON.writeValueAsString(
        Map.of(
            "jsonrpc", "2.0", "id", IDS.incrementAndGet(), "method", method, "params", withMeta));
  }

  static Map<String, Object> parse(String json) {
    return JSON.readValue(json, new TypeReference<>() {});
  }

  /** The {@code result} object of a JSON-RPC response, failing loudly on a JSON-RPC error. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> result(String json) {
    Map<String, Object> response = parse(json);
    if (response.containsKey("error")) {
      throw new AssertionError("Expected a result but got a JSON-RPC error: " + json);
    }
    return (Map<String, Object>) response.get("result");
  }

  /** The text of the first content block of a {@code tools/call} result. */
  @SuppressWarnings("unchecked")
  static String firstText(Map<String, Object> callToolResult) {
    var content = (java.util.List<Map<String, Object>>) callToolResult.get("content");
    return (String) content.getFirst().get("text");
  }
}
