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

import java.util.List;
import java.util.Map;
import org.apache.solr.mcp.server.McpSolrUnreachableTestBase;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** The Solr-unreachable checks over the modern (2026-07-28) protocol, as raw JSON-RPC. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "http.security.enabled=false",
      "spring.docker.compose.enabled=false",
      "MCP_PROTOCOL=modern",
      // Its own context: Spring Framework 7 pauses a cached context between test classes.
      "solr.mcp.test.context=modern-solr-unreachable"
    })
@ActiveProfiles("http")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class McpModernSolrUnreachableIntegrationTest extends McpSolrUnreachableTestBase {

  @LocalServerPort private int port;

  private final ModernMcpToolClient tools = new ModernMcpToolClient(() -> port);

  @Override
  protected List<Map<String, Object>> listTools() throws Exception {
    return tools.listTools();
  }

  @Override
  protected Map<String, Object> callTool(String name, Map<String, Object> arguments)
      throws Exception {
    return tools.callTool(name, arguments);
  }
}
