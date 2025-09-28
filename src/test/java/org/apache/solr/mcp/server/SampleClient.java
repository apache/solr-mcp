package org.apache.solr.mcp.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Christian Tzolov
 */

public class SampleClient {

    private final McpClientTransport transport;

    public SampleClient(McpClientTransport transport) {
        this.transport = transport;
    }

    public void run() {

        try (var client = McpClient.sync(this.transport)
                .loggingConsumer(message -> System.out.println(">> Client Logging: " + message))
                .build()) {

            // Assert client initialization succeeds
            assertDoesNotThrow(client::initialize, "Client initialization should not throw an exception");

            // Assert ping succeeds
            assertDoesNotThrow(client::ping, "Client ping should not throw an exception");

            // List and validate tools
            ListToolsResult toolsList = client.listTools();
            assertNotNull(toolsList, "Tools list should not be null");
            assertNotNull(toolsList.tools(), "Tools collection should not be null");

            // Validate expected tool count based on MCP server implementation
            assertEquals(8, toolsList.tools().size(), "Expected 8 tools to be available");

            // Define expected tools based on the log output
            Set<String> expectedToolNames = Set.of(
                    "index_json_documents",
                    "index_csv_documents",
                    "getCollectionStats",
                    "Search",
                    "listCollections",
                    "checkHealth",
                    "index_xml_documents",
                    "getSchema"
            );

            // Validate each expected tool is present
            List<String> actualToolNames = toolsList.tools().stream()
                    .map(Tool::name)
                    .toList();

            for (String expectedTool : expectedToolNames) {
                assertTrue(actualToolNames.contains(expectedTool),
                        "Expected tool '" + expectedTool + "' should be available");
            }

            // Validate tool details for key tools
            toolsList.tools().forEach(tool -> {
                assertNotNull(tool.name(), "Tool name should not be null");
                assertNotNull(tool.description(), "Tool description should not be null");
                assertNotNull(tool.inputSchema(), "Tool input schema should not be null");
                assertFalse(tool.name().trim().isEmpty(), "Tool name should not be empty");
                assertFalse(tool.description().trim().isEmpty(), "Tool description should not be empty");

                // Validate specific tools based on expected behavior
                switch (tool.name()) {
                    case "index_json_documents":
                        assertTrue(tool.description().toLowerCase().contains("json"),
                                "JSON indexing tool should mention JSON in description");
                        break;
                    case "index_csv_documents":
                        assertTrue(tool.description().toLowerCase().contains("csv"),
                                "CSV indexing tool should mention CSV in description");
                        break;
                    case "Search":
                        assertTrue(tool.description().toLowerCase().contains("search"),
                                "Search tool should mention search in description");
                        break;
                    case "listCollections":
                        assertTrue(tool.description().toLowerCase().contains("collection"),
                                "List collections tool should mention collections in description");
                        break;
                    case "checkHealth":
                        assertTrue(tool.description().toLowerCase().contains("health"),
                                "Health check tool should mention health in description");
                        break;
                    default:
                        // Additional tools are acceptable
                        break;
                }

                System.out.println("Tool: " + tool.name() + ", description: " + tool.description() + ", schema: "
                        + tool.inputSchema());
            });

        } catch (Exception e) {
            throw new RuntimeException("MCP client operation failed", e);
        }
    }

}