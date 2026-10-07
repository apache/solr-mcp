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
				.addEnvVar("SPRING_DOCKER_COMPOSE_ENABLED", "false").addEnvVar("SOLR_INDEX_URL_ALLOWED_HOSTS", "*")
				.build();

		var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
		return McpClient.sync(transport).build();
	}

	@Test
	@Order(42)
	void indexesFromAUrlThroughStdioMcp() throws Exception {
		var server = serveShowsJson();
		try {
			String collection = "shows-url-copy";
			assertNotError(mcpClient.callTool(new CallToolRequest("create-collection", Map.of("name", collection))));
			var indexed = mcpClient.callTool(
					new CallToolRequest("index-url", Map.of("collection", collection, "url", showsJsonUrl(server))));
			assertNotError(indexed);
			assertTrue(extractText(indexed).startsWith("Solr accepted the JSON document"), extractText(indexed));
			assertFalse(extractText(indexed).contains("Stranger Things"), "payload leaked into the summary");
			var searched = mcpClient.callTool(
					new CallToolRequest("search", Map.of("collection", collection, "query", "*:*", "rows", 0)));
			assertNotError(searched);
			Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(searched), new TypeReference<>() {
			});
			assertEquals(SHOWS_DOC_COUNT, getNumFound(response));
		} finally {
			server.stop(0);
		}
	}

	@Test
	@Order(43)
	void aRefusedAddressIsAnMcpToolError() {
		var result = mcpClient.callTool(new CallToolRequest("index-url",
				Map.of("collection", SHOWS_COLLECTION, "url", "http://169.254.169.254/latest/meta-data/")));
		assertEquals(Boolean.TRUE, result.isError());
		assertTrue(extractText(result).contains("link-local or cloud-metadata"), extractText(result));
	}

}
