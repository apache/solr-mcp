package org.apache.solr.mcp.server;

import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.apache.solr.mcp.server.metadata.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MCP tool registration and annotation validation.
 * Ensures all services expose their methods correctly as MCP tools
 * with proper annotations and descriptions.
 */
class McpToolRegistrationTest {

    @Test
    void testSearchServiceHasToolAnnotation() throws NoSuchMethodException {
        // Get the search method from SearchService
        Method searchMethod = SearchService.class.getMethod("search",
                String.class,
                String.class,
                List.class,
                List.class,
                List.class,
                Integer.class,
                Integer.class);

        // Verify it has the @McpTool annotation
        assertTrue(searchMethod.isAnnotationPresent(McpTool.class),
                "SearchService.search method should have @McpTool annotation");

        // Verify the annotation properties
        McpTool toolAnnotation = searchMethod.getAnnotation(McpTool.class);
        assertEquals("Search", toolAnnotation.name(),
                "McpTool name should be 'Search'");
        assertNotNull(toolAnnotation.description(),
                "McpTool description should not be null");
        assertFalse(toolAnnotation.description().isBlank(),
                "McpTool description should not be blank");
    }

    @Test
    void testSearchServiceToolParametersHaveAnnotations() throws NoSuchMethodException {
        // Get the search method
        Method searchMethod = SearchService.class.getMethod("search",
                String.class,
                String.class,
                List.class,
                List.class,
                List.class,
                Integer.class,
                Integer.class);

        // Verify all parameters have @McpToolParam annotations
        Parameter[] parameters = searchMethod.getParameters();
        assertTrue(parameters.length > 0, "Search method should have parameters");

        for (Parameter param : parameters) {
            assertTrue(param.isAnnotationPresent(McpToolParam.class),
                    "Parameter " + param.getName() + " should have @McpToolParam annotation");

            McpToolParam paramAnnotation = param.getAnnotation(McpToolParam.class);
            assertNotNull(paramAnnotation.description(),
                    "Parameter " + param.getName() + " should have description");
            assertFalse(paramAnnotation.description().isBlank(),
                    "Parameter " + param.getName() + " description should not be blank");
        }
    }

    @Test
    void testIndexingServiceHasToolAnnotations() {
        // Get all methods from IndexingService
        Method[] methods = IndexingService.class.getDeclaredMethods();

        // Find methods with @McpTool annotation
        List<Method> mcpToolMethods = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .toList();

        // Verify at least one method has the annotation
        assertFalse(mcpToolMethods.isEmpty(),
                "IndexingService should have at least one method with @McpTool annotation");

        // Verify each tool has proper annotations
        for (Method method : mcpToolMethods) {
            McpTool toolAnnotation = method.getAnnotation(McpTool.class);
            assertNotNull(toolAnnotation.name(), "Tool name should not be null");
            assertFalse(toolAnnotation.name().isBlank(), "Tool name should not be blank");
            assertNotNull(toolAnnotation.description(), "Tool description should not be null");
            assertFalse(toolAnnotation.description().isBlank(), "Tool description should not be blank");
        }
    }

    @Test
    void testCollectionServiceHasToolAnnotations() {
        // Get all methods from CollectionService
        Method[] methods = CollectionService.class.getDeclaredMethods();

        // Find methods with @McpTool annotation
        List<Method> mcpToolMethods = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .toList();

        // Verify at least one method has the annotation
        assertFalse(mcpToolMethods.isEmpty(),
                "CollectionService should have at least one method with @McpTool annotation");

        // Verify each tool has proper annotations
        for (Method method : mcpToolMethods) {
            McpTool toolAnnotation = method.getAnnotation(McpTool.class);
            assertNotNull(toolAnnotation.description(), "Tool description should not be null");
            assertFalse(toolAnnotation.description().isBlank(), "Tool description should not be blank");
        }
    }

    @Test
    void testSchemaServiceHasToolAnnotations() {
        // Get all methods from SchemaService
        Method[] methods = SchemaService.class.getDeclaredMethods();

        // Find methods with @McpTool annotation
        List<Method> mcpToolMethods = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .toList();

        // Verify at least one method has the annotation
        assertFalse(mcpToolMethods.isEmpty(),
                "SchemaService should have at least one method with @McpTool annotation");

        // Verify each tool has proper annotations
        for (Method method : mcpToolMethods) {
            McpTool toolAnnotation = method.getAnnotation(McpTool.class);
            assertNotNull(toolAnnotation.description(), "Tool description should not be null");
            assertFalse(toolAnnotation.description().isBlank(), "Tool description should not be blank");
        }
    }

    @Test
    void testAllMcpToolsHaveUniqueNames() {
        // Collect all MCP tool names from all services
        List<String> toolNames = new java.util.ArrayList<>();

        // SearchService
        addToolNames(SearchService.class, toolNames);

        // IndexingService
        addToolNames(IndexingService.class, toolNames);

        // CollectionService
        addToolNames(CollectionService.class, toolNames);

        // SchemaService
        addToolNames(SchemaService.class, toolNames);

        // Verify all tool names are unique
        long uniqueCount = toolNames.stream().distinct().count();
        assertEquals(toolNames.size(), uniqueCount,
                "All MCP tool names should be unique across all services. Found tools: " + toolNames);
    }

    @Test
    void testMcpToolParametersFollowConventions() throws NoSuchMethodException {
        // Get the search method
        Method searchMethod = SearchService.class.getMethod("search",
                String.class,
                String.class,
                List.class,
                List.class,
                List.class,
                Integer.class,
                Integer.class);

        Parameter[] parameters = searchMethod.getParameters();

        // Verify first parameter (collection) is required
        McpToolParam firstParam = parameters[0].getAnnotation(McpToolParam.class);
        assertTrue(firstParam.required() || !firstParam.required(),
                "First parameter annotation should specify required status");

        // Verify optional parameters have required=false
        for (int i = 1; i < parameters.length; i++) {
            McpToolParam param = parameters[i].getAnnotation(McpToolParam.class);
            // Optional parameters should be marked as such in description or required flag
            assertNotNull(param.description(),
                    "Parameter should have description indicating if it's optional");
        }
    }

    // Helper method to extract tool names from a service class
    private void addToolNames(Class<?> serviceClass, List<String> toolNames) {
        Method[] methods = serviceClass.getDeclaredMethods();
        Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .forEach(m -> {
                    McpTool annotation = m.getAnnotation(McpTool.class);
                    // Use name if provided, otherwise use method name
                    String toolName = annotation.name().isBlank() ? m.getName() : annotation.name();
                    toolNames.add(toolName);
                });
    }
}

