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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MCP client integration test running against the server in HTTP mode. Boots
 * the full application with a real Solr container and exercises all MCP tools
 * via an HTTP transport.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"http.security.enabled=false", "spring.docker.compose.enabled=false",
				"solr.index-url.allowed-hosts=*"})
@ActiveProfiles("http")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class McpClientIntegrationTest extends McpClientIntegrationTestBase {

	@LocalServerPort
	private int port;

	@Override
	protected McpSyncClient createClient() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build();
		return McpClient.sync(transport).build();
	}

	/**
	 * URL ingestion must work over HTTP exactly as it does over STDIO (#208): the
	 * base class asserts the tool and its hints; this is the round trip.
	 */
	@Test
	@Order(42)
	void indexesFromAUrlThroughHttpMcp() throws Exception {
		var server = serveShowsJson();
		try {
			String collection = "shows-url-copy";
			assertNotError(mcpClient.callTool(new CallToolRequest("create-collection", Map.of("name", collection))));
			var indexed = mcpClient.callTool(
					new CallToolRequest("index-url", Map.of("collection", collection, "url", showsJsonUrl(server))));
			assertNotError(indexed);
			assertTrue(extractText(indexed).contains("61 of 61"), extractText(indexed));
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
