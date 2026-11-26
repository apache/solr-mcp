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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** The Solr-unreachable checks over the legacy (2025) protocol, through the SDK client. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "http.security.enabled=false",
      "spring.docker.compose.enabled=false",
      // Its own context: Spring Framework 7 pauses a cached context between test classes.
      "solr.mcp.test.context=solr-unreachable"
    })
@ActiveProfiles("http")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class McpSolrUnreachableIntegrationTest extends McpSolrUnreachableTestBase {

  @LocalServerPort private int port;

  private final LegacyMcpToolClient tools = new LegacyMcpToolClient(() -> port);

  @AfterAll
  void closeClient() {
    tools.close();
  }

  @Override
  protected List<Map<String, Object>> listTools() {
    return tools.listTools();
  }

  @Override
  protected Map<String, Object> callTool(String name, Map<String, Object> arguments) {
    return tools.callTool(name, arguments);
  }
}
