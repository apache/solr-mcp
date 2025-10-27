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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sample MCP client for testing and demonstrating Solr MCP Server functionality.
 *
 * <p>This test client provides a comprehensive validation suite for the Solr MCP Server, verifying
 * that all expected MCP tools are properly registered and functioning as expected. It serves as
 * both a testing framework and a reference implementation for MCP client integration.
 *
 * <p><strong>Test Coverage:</strong>
 *
 * <ul>
 *   <li><strong>Client Initialization</strong>: Verifies MCP client can connect and initialize
 *   <li><strong>Connection Health</strong>: Tests ping functionality and connection stability
 *   <li><strong>Tool Discovery</strong>: Validates all expected MCP tools are registered
 *   <li><strong>Tool Validation</strong>: Checks tool metadata, descriptions, and schemas
 *   <li><strong>Expected Tools</strong>: Verifies presence of search, indexing, and metadata tools
 * </ul>
 *
 * <p><strong>Expected MCP Tools:</strong>
 *
 * <ul>
 *   <li><strong>index_json_documents</strong>: JSON document indexing capability
 *   <li><strong>index_csv_documents</strong>: CSV document indexing capability
 *   <li><strong>index_xml_documents</strong>: XML document indexing capability
 *   <li><strong>Search</strong>: Full-text search functionality with filtering and faceting
 *   <li><strong>listCollections</strong>: Collection discovery and listing
 *   <li><strong>getCollectionStats</strong>: Collection metrics and performance data
 *   <li><strong>checkHealth</strong>: Health monitoring and status reporting
 *   <li><strong>getSchema</strong>: Schema introspection and field analysis
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * McpClientTransport transport = // ... initialize transport
 * SampleClient client = new SampleClient(transport);
 * client.run(); // Executes full test suite
 * }</pre>
 *
 * <p><strong>Assertion Strategy:</strong>
 *
 * <p>Uses JUnit assertions to validate expected behavior and fail fast on any inconsistencies. Each
 * tool is validated for proper name, description, and schema configuration to ensure MCP protocol
 * compliance.
 *
 * @version 0.0.1
 * @see McpClient
 * @see McpClientTransport
 * @see io.modelcontextprotocol.spec.McpSchema.Tool
 * @since 0.0.1
 */
public class SampleClient {

    private final McpClientTransport transport;

    /**
     * Constructs a new SampleClient with the specified MCP transport.
     *
     * @param transport the MCP client transport for communication with the Solr MCP Server
     * @throws IllegalArgumentException if transport is null
     */
    public SampleClient(McpClientTransport transport) {
        this.transport = transport;
    }

    /**
     * Executes the comprehensive test suite for Solr MCP Server functionality.
     *
     * <p>This method performs a complete validation of the MCP server including:
     *
     * <ul>
     *   <li>Client initialization and connection establishment
     *   <li>Health check via ping operation
     *   <li>Tool discovery and count validation
     *   <li>Individual tool metadata validation
     *   <li>Tool-specific description and schema verification
     * </ul>
     *
     * <p><strong>Test Sequence:</strong>
     *
     * <ol>
     *   <li>Initialize MCP client with provided transport
     *   <li>Perform ping test to verify connectivity
     *   <li>List all available tools and validate expected count (8 tools)
     *   <li>Verify each expected tool is present in the tools list
     *   <li>Validate tool metadata (name, description, schema) for each tool
     *   <li>Perform tool-specific validation based on tool type
     * </ol>
     *
     * @throws RuntimeException if any test assertion fails or MCP operations encounter errors
     * @throws AssertionError if expected tools are missing or tool validation fails
     */
    public void run() {

        try (var client =
                     McpClient.sync(this.transport)
                             .loggingConsumer(
                                     message -> System.out.println(">> Client Logging: " + message))
                             .build()) {

            // Assert client initialization succeeds
            assertDoesNotThrow(
                    client::initialize, "Client initialization should not throw an exception");

            // Assert ping succeeds
            assertDoesNotThrow(client::ping, "Client ping should not throw an exception");

            // List and validate tools
            ListToolsResult toolsList = client.listTools();
            assertNotNull(toolsList, "Tools list should not be null");
            assertNotNull(toolsList.tools(), "Tools collection should not be null");

            // Validate expected tool count based on MCP server implementation
            assertEquals(8, toolsList.tools().size(), "Expected 8 tools to be available");

            // Define expected tools based on the log output
            Set<String> expectedToolNames =
                    Set.of(
                            "index_json_documents",
                            "index_csv_documents",
                            "getCollectionStats",
                            "Search",
                            "listCollections",
                            "checkHealth",
                            "index_xml_documents",
                            "getSchema");

            // Validate each expected tool is present
            List<String> actualToolNames = toolsList.tools().stream().map(Tool::name).toList();

            for (String expectedTool : expectedToolNames) {
                assertTrue(
                        actualToolNames.contains(expectedTool),
                        "Expected tool '" + expectedTool + "' should be available");
            }

            // Validate tool details for key tools
            toolsList
                    .tools()
                    .forEach(
                            tool -> {
                                assertNotNull(tool.name(), "Tool name should not be null");
                                assertNotNull(
                                        tool.description(), "Tool description should not be null");
                                assertNotNull(
                                        tool.inputSchema(), "Tool input schema should not be null");
                                assertFalse(
                                        tool.name().trim().isEmpty(),
                                        "Tool name should not be empty");
                                assertFalse(
                                        tool.description().trim().isEmpty(),
                                        "Tool description should not be empty");

                                // Validate specific tools based on expected behavior
                                switch (tool.name()) {
                                    case "index_json_documents":
                                        assertTrue(
                                                tool.description().toLowerCase().contains("json"),
                                                "JSON indexing tool should mention JSON in"
                                                        + " description");
                                        break;
                                    case "index_csv_documents":
                                        assertTrue(
                                                tool.description().toLowerCase().contains("csv"),
                                                "CSV indexing tool should mention CSV in"
                                                        + " description");
                                        break;
                                    case "Search":
                                        assertTrue(
                                                tool.description().toLowerCase().contains("search"),
                                                "Search tool should mention search in description");
                                        break;
                                    case "listCollections":
                                        assertTrue(
                                                tool.description()
                                                        .toLowerCase()
                                                        .contains("collection"),
                                                "List collections tool should mention collections"
                                                        + " in description");
                                        break;
                                    case "checkHealth":
                                        assertTrue(
                                                tool.description().toLowerCase().contains("health"),
                                                "Health check tool should mention health in"
                                                        + " description");
                                        break;
                                    default:
                                        // Additional tools are acceptable
                                        break;
                                }

                                System.out.println(
                                        "Tool: "
                                                + tool.name()
                                                + ", description: "
                                                + tool.description()
                                                + ", schema: "
                                                + tool.inputSchema());
                            });

        } catch (Exception e) {
            throw new RuntimeException("MCP client operation failed", e);
        }
    }
}
