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
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.request;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.solr.mcp.server.BuildInfoReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The packaged jar over STDIO on the 2026-07-28 revision ({@code MCP_PROTOCOL=modern}), the way a
 * desktop client launches it: one JSON-RPC message per line on stdin and stdout, no {@code
 * initialize} handshake.
 *
 * <p>Also guards the STDIO invariant that nothing but JSON-RPC reaches stdout: every line read back
 * must parse as a response.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpModernProtocolStdioIntegrationTest {

  @Container
  static final SolrContainer SOLR =
      new SolrContainer(
          DockerImageName.parse(System.getProperty("solr.test.image", "solr:9.9.0-slim")));

  @SuppressWarnings("NotNullFieldNotInitialized") // set by start()
  private Process server;

  @SuppressWarnings("NotNullFieldNotInitialized") // set by start()
  private BufferedWriter stdin;

  @SuppressWarnings("NotNullFieldNotInitialized") // set by start()
  private BufferedReader stdout;

  @BeforeAll
  void start() throws Exception {
    String solrUrl = "http://" + SOLR.getHost() + ":" + SOLR.getMappedPort(8983) + "/solr/";
    ProcessBuilder builder =
        new ProcessBuilder("java", "-jar", "build/libs/" + BuildInfoReader.getJarFileName())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("SOLR_URL", solrUrl);
    builder.environment().put("MCP_PROTOCOL", "modern");
    builder.environment().put("SPRING_DOCKER_COMPOSE_ENABLED", "false");
    server = builder.start();
    stdin =
        new BufferedWriter(
            new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
    stdout =
        new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
  }

  @AfterAll
  void stop() throws Exception {
    server.destroy();
    if (!server.waitFor(10, TimeUnit.SECONDS)) {
      server.destroyForcibly();
    }
  }

  private Map<String, Object> send(String method, Map<String, Object> params) throws Exception {
    stdin.write(request(method, params));
    stdin.newLine();
    stdin.flush();
    String line =
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return stdout.readLine();
                  } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
                })
            .get(90, TimeUnit.SECONDS);
    assertNotNull(line, "the server closed stdout instead of answering " + method);
    return result(line);
  }

  /**
   * The first request goes out the moment the process starts, before Spring has finished starting,
   * because that is what desktop clients do. On Spring AI 2.1.0-RC2's own modern STDIO transport
   * this request got a random answer (or none); see {@code ModernStdioTransportConfiguration}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void firstRequestRightAfterLaunchThenDiscoverAndList() throws Exception {
    Map<String, Object> collections =
        send(
            "tools/call",
            Map.of("name", "list-collections", "arguments", Map.of())); // no Mcp-Name over STDIO
    assertEquals(
        Boolean.FALSE, collections.get("isError"), () -> "list-collections failed: " + collections);
    assertTrue(firstText(collections).startsWith("["), "list-collections returns a JSON array");

    Map<String, Object> discover = send("server/discover", Map.of());
    assertEquals(List.of(PROTOCOL_VERSION), discover.get("supportedVersions"));

    List<Map<String, Object>> tools =
        (List<Map<String, Object>>) send("tools/list", Map.of()).get("tools");
    assertTrue(tools.stream().anyMatch(t -> "search".equals(t.get("name"))));
  }
}
