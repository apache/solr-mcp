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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import org.junit.jupiter.api.Tag;
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

}
