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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * MCP client integration test running against the server in STDIO mode. Spawns
 * the application jar as a subprocess using {@link StdioClientTransport} and
 * exercises all MCP tools via the stdio JSON-RPC protocol.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class McpClientStdioIntegrationTest extends McpClientIntegrationTestBase {

	@Container
	static final SolrContainer solrContainer = new SolrContainer(
			DockerImageName.parse(System.getProperty("solr.test.image", "solr:9.9-slim")));

	@Override
	protected McpSyncClient createClient() {
		String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/";
		String jarPath = "build/libs/" + BuildInfoReader.getJarFileName();

		var params = ServerParameters.builder("java").args("-jar", jarPath).addEnvVar("SOLR_URL", solrUrl)
				.addEnvVar("SPRING_DOCKER_COMPOSE_ENABLED", "false").build();

		var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
		return McpClient.sync(transport).build();
	}

	@Test
	@Order(39)
	void blankOptionalSearchEntriesAreIgnoredThroughMcp() throws Exception {
		var result = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", SHOWS_COLLECTION, "query", "*:*", "rows", 0, "filterQueries", List.of(" "),
						"facetFields", List.of("", "platform"), "sortClauses",
						List.of(Map.of("field", "", "order", "")))));
		assertNotError(result);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});
		assertEquals(SHOWS_DOC_COUNT, getNumFound(response));
		Map<?, ?> facets = (Map<?, ?>) response.get("facets");
		Map<?, ?> platforms = (Map<?, ?>) facets.get("platform");
		assertEquals(20, ((Number) platforms.get("Netflix")).intValue());
	}

	@Test
	@Order(40)
	void searchFailureIsAnActionableMcpToolError() {
		var result = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", SHOWS_COLLECTION, "facetFields", List.of("nonexistent_field_xyz"))));
		assertEquals(Boolean.TRUE, result.isError());
		assertTrue(extractText(result).contains("get-schema"), extractText(result));
		assertFalse(extractText(result).contains("Exception"), extractText(result));
	}

}
