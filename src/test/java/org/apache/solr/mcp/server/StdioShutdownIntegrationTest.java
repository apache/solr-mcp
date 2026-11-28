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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The packaged jar over STDIO must exit when the client closes stdin, which is how the MCP spec has
 * a client shut down a STDIO server, including after a tool has called Solr.
 *
 * <p>The tool call matters: SolrJ's HTTP client starts its worker threads on the first request, and
 * a session that never reaches Solr exits even when those threads would keep the JVM alive.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class StdioShutdownIntegrationTest {

  private static final int EXIT_TIMEOUT_SECONDS = 30;

  private static final int RESPONSE_TIMEOUT_SECONDS = 90;

  private static final String LIST_COLLECTIONS_ID = "2";

  private static final String MODERN_META =
      """
      "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",\
      "io.modelcontextprotocol/clientCapabilities":{},\
      "io.modelcontextprotocol/clientInfo":{"name":"stdio-shutdown-test","version":"1"}}""";

  /** The 2025 revisions: initialize handshake first, then the tool call. */
  private static final List<String> STREAMABLE_SESSION =
      List.of(
          """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
          "capabilities":{},"clientInfo":{"name":"stdio-shutdown-test","version":"1"}}}""",
          """
          {"jsonrpc":"2.0","method":"notifications/initialized"}""",
          """
          {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list-collections",\
          "arguments":{}}}""");

  /** The 2026-07-28 revision: no handshake, every request carries {@code _meta}. */
  private static final List<String> MODERN_SESSION =
      List.of(
          """
          {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list-collections",\
          "arguments":{},%s}}"""
              .formatted(MODERN_META));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final SolrContainer SOLR =
      new SolrContainer(
          DockerImageName.parse(System.getProperty("solr.test.image", "solr:9.9.0-slim")));

  @ParameterizedTest(name = "MCP_PROTOCOL={0}")
  @ValueSource(strings = {"streamable", "modern"})
  void exitsWhenStdinClosesAfterASolrCall(String protocol) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("java", "-jar", "build/libs/" + BuildInfoReader.getJarFileName())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder
        .environment()
        .put("SOLR_URL", "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr/");
    builder.environment().put("MCP_PROTOCOL", protocol);
    builder.environment().put("SPRING_DOCKER_COMPOSE_ENABLED", "false");
    Process server = builder.start();
    try {
      BufferedWriter stdin =
          new BufferedWriter(
              new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
      BufferedReader stdout =
          new BufferedReader(
              new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

      for (String message : "modern".equals(protocol) ? MODERN_SESSION : STREAMABLE_SESSION) {
        stdin.write(message);
        stdin.newLine();
      }
      stdin.flush();

      JsonNode toolResult = awaitResponse(stdout, LIST_COLLECTIONS_ID).get("result");
      assertNotNull(toolResult, "list-collections returned an error instead of a result");
      assertFalse(toolResult.get("isError").asBoolean(), () -> "list-collections: " + toolResult);

      stdin.close();

      assertTrue(
          server.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "the server must exit once the client closes stdin");
      assertEquals(0, server.exitValue());
    } finally {
      server.destroyForcibly();
    }
  }

  /** Reads stdout until the response with the given id, skipping earlier responses. */
  private static JsonNode awaitResponse(BufferedReader stdout, String id) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                String line;
                while ((line = stdout.readLine()) != null) {
                  JsonNode message = JSON.readTree(line);
                  if (id.equals(message.path("id").asString())) {
                    return message;
                  }
                }
                throw new AssertionError("the server closed stdout before answering id " + id);
              } catch (java.io.IOException readFailure) {
                throw new UncheckedIOException(readFailure);
              }
            })
        .get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
}
