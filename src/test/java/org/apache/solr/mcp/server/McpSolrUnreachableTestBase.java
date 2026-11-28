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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Calls every tool while Solr cannot be reached, once per protocol: {@code solr.url} points at a
 * local port nothing listens on, so every request is refused. Each call must come back as a {@code
 * tools/call} result with {@code isError: true}, never as a JSON-RPC error, with text that quotes
 * no URL and none of SolrJ's wording ({@link McpToolFailures#assertReportsFailure}). {@code
 * check-health} reports {@code SOLR_UNREACHABLE} as its result instead.
 *
 * <p>The application starts without Solr: no bean calls Solr during startup.
 *
 * <p>Subclasses supply the transport, as in {@link McpContractConformanceTestBase}.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class McpSolrUnreachableTestBase {

  /** A local port that was free a moment ago; nothing in the test listens on it. */
  private static final int CLOSED_PORT = freePort();

  private static final String SOLR_UNREACHABLE = "SOLR_UNREACHABLE";

  @DynamicPropertySource
  static void pointSolrAtAClosedPort(DynamicPropertyRegistry registry) {
    registry.add("solr.url", () -> "http://localhost:" + CLOSED_PORT + "/solr/");
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The {@code tools} array of a {@code tools/list} result. */
  protected abstract List<Map<String, Object>> listTools() throws Exception;

  /** The result object of a {@code tools/call}. */
  protected abstract Map<String, Object> callTool(String name, Map<String, Object> arguments)
      throws Exception;

  /** Every listed tool has arguments to call it with, so none drops out of the dynamic tests. */
  @Test
  void everyToolHasArgumentsForTheFailureChecks() throws Exception {
    Set<String> covered = new TreeSet<>(McpToolFailures.MISSING_TARGET_ARGUMENTS.keySet());
    covered.addAll(McpToolFailures.TOOLS_WITHOUT_A_TARGET);
    assertEquals(McpToolFailures.toolNames(listTools()), covered, "tools with failure arguments");
  }

  @TestFactory
  Stream<DynamicTest> everyToolReportsUnreachableSolrAsAToolError() throws Exception {
    return McpToolFailures.toolNames(listTools()).stream()
        .map(
            tool ->
                DynamicTest.dynamicTest(
                    tool,
                    () ->
                        McpToolFailures.assertReportsFailure(
                            tool,
                            callTool(tool, McpToolFailures.unreachableArguments(tool)),
                            SOLR_UNREACHABLE,
                            McpToolFailures.unreachableText())));
  }
}
