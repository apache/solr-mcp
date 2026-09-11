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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
	void reusesJsonFileThroughMcpAndVerifiesCountsAndFacets() throws Exception {
		String copyCollection = "shows-file-copy";
		assertNotError(mcpClient.callTool(new CallToolRequest("create-collection", Map.of("name", copyCollection))));
		// Both collections share the explicitly prepared shows schema from the base
		// workflow.
		for (String collection : List.of(SHOWS_COLLECTION, copyCollection, copyCollection)) {
			var indexed = mcpClient.callTool(new CallToolRequest("index-file", Map.of("collection", collection, "path",
					Path.of("src/test/resources/shows.json").toAbsolutePath().toString())));
			assertNotError(indexed);
			assertTrue(extractText(indexed).contains("61 of 61"), extractText(indexed));
			assertFalse(extractText(indexed).contains("Stranger Things"));
			var searched = mcpClient.callTool(new CallToolRequest("search",
					Map.of("collection", collection, "query", "*:*", "rows", 0, "facetFields", List.of("platform"))));
			assertNotError(searched);
			Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(searched), new TypeReference<>() {
			});
			assertEquals(SHOWS_DOC_COUNT, getNumFound(response));
			Map<?, ?> facets = (Map<?, ?>) response.get("facets");
			Map<?, ?> platforms = (Map<?, ?>) facets.get("platform");
			assertEquals(20, ((Number) platforms.get("Netflix")).intValue());
		}
	}

	@Test
	@Order(40)
	void fileReadFailureIsAnMcpToolError() {
		var result = mcpClient.callTool(
				new CallToolRequest("index-file", Map.of("collection", SHOWS_COLLECTION, "path", "missing.json")));
		assertEquals(Boolean.TRUE, result.isError());
		assertTrue(extractText(result).contains("Cannot read the file"));
		assertFalse(extractText(result).contains("NoSuchFileException"));
	}

	@Test
	@Order(2)
	void advertisesLocalFileToolWithOptionalFormatAndWriteHints() {
		var tool = mcpClient.listTools().tools().stream().filter(item -> item.name().equals("index-file")).findFirst()
				.orElseThrow();
		assertEquals(Boolean.FALSE, tool.annotations().readOnlyHint());
		assertEquals(Boolean.TRUE, tool.annotations().destructiveHint());
		assertEquals(Boolean.TRUE, tool.annotations().idempotentHint());
		assertTrue(tool.inputSchema().properties().containsKey("format"));
		assertFalse(tool.inputSchema().required().contains("format"));
	}

	@Test
	@Order(41)
	void indexesEveryFileFormatThroughMcp(@TempDir Path directory) throws Exception {
		String collection = "file-formats";
		assertNotError(mcpClient.callTool(new CallToolRequest("create-collection", Map.of("name", collection))));
		for (String format : List.of("json", "csv", "xml", "md")) {
			String id = "file-" + format;
			String content = switch (format) {
				case "json" -> "{\"id\":\"" + id + "\",\"title\":\"File input\"}";
				case "csv" -> "id,title\n" + id + ",File input\n";
				case "xml" -> "<id>" + id + "</id>";
				default -> "---\nid: " + id + "\n---\n# File input\nBody text.\n";
			};
			Path file = Files.writeString(directory.resolve("input." + format), content);
			for (int attempt = 0; attempt < 2; attempt++) {
				var result = mcpClient.callTool(
						new CallToolRequest("index-file", Map.of("collection", collection, "path", file.toString())));
				assertNotError(result);
				assertTrue(extractText(result).contains("1 of 1"), extractText(result));
			}
			var byId = mcpClient.callTool(
					new CallToolRequest("search", Map.of("collection", collection, "query", "id:" + id, "rows", 0)));
			assertNotError(byId);
			Map<String, Object> idResponse = OBJECT_MAPPER.readValue(extractText(byId), new TypeReference<>() {
			});
			assertEquals(1, getNumFound(idResponse), "Reusing a file must retain the stable ID for " + format);
		}
		var result = mcpClient
				.callTool(new CallToolRequest("search", Map.of("collection", collection, "query", "*:*", "rows", 0)));
		assertNotError(result);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(result), new TypeReference<>() {
		});
		assertEquals(4, getNumFound(response));
	}

	@Test
	@Order(42)
	void indexesNestedXmlWithTheExistingFieldMapping(@TempDir Path directory) throws Exception {
		String collection = "xml-file-fields";
		assertNotError(mcpClient.callTool(new CallToolRequest("create-collection", Map.of("name", collection))));
		assertNotError(mcpClient.callTool(new CallToolRequest("add-fields",
				Map.of("collection", collection, "fields",
						List.of(Map.of("name", "document_id", "type", "string", "stored", true, "indexed", true,
								"multiValued", false),
								Map.of("name", "document_title", "type", "text_general", "stored", true, "indexed",
										true, "multiValued", false))))));
		String xml = "<documents><document><id>xml-one</id><title>First XML</title></document>"
				+ "<document><id>xml-two</id><title>Second XML</title></document></documents>";
		Path file = Files.writeString(directory.resolve("nested.xml"), xml);
		var indexed = mcpClient
				.callTool(new CallToolRequest("index-file", Map.of("collection", collection, "path", file.toString())));
		assertNotError(indexed);
		assertTrue(extractText(indexed).contains("2 of 2"), extractText(indexed));
		assertTrue(extractText(indexed).contains("document_id"), extractText(indexed));
		assertTrue(extractText(indexed).contains("document_title"), extractText(indexed));
		var found = mcpClient.callTool(new CallToolRequest("search",
				Map.of("collection", collection, "query", "document_id:xml-one", "rows", 1)));
		assertNotError(found);
		Map<String, Object> response = OBJECT_MAPPER.readValue(extractText(found), new TypeReference<>() {
		});
		assertEquals(1, getNumFound(response));
		assertTrue(extractText(found).contains("First XML"), extractText(found));
	}

}
