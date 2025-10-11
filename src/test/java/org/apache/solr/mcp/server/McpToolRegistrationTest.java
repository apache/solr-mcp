package org.apache.solr.mcp.server;

import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }

}
