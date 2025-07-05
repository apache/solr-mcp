package org.apache.solr.mcp.server;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

        // Verify it has the @Tool annotation
        assertTrue(searchMethod.isAnnotationPresent(Tool.class),
                "SearchService.search method should have @Tool annotation");

        // Verify the annotation properties
        Tool toolAnnotation = searchMethod.getAnnotation(Tool.class);
        assertEquals("Search", toolAnnotation.name(),
                "Tool name should be 'Search'");
        assertNotNull(toolAnnotation.description(),
                "Tool description should not be null");
    }

    @Test
    void testToolCallbackCreation() {
        // Create a SearchService instance
        SearchService searchService = new SearchService(null);

        // Create a tool callback from the SearchService
        ToolCallback[] callbacks = ToolCallbacks.from(searchService);

        // Verify the callback was created
        assertNotNull(callbacks);
        assertTrue(callbacks.length > 0);
        assertEquals(1, callbacks.length);

        // Verify the callback exists (we can't directly access name/description)
        assertNotNull(callbacks[0]);
    }
}
