package org.apache.solr.mcp.server;

import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class McpIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SearchService searchService;

    @Test
    void testToolCallbacksRegistration() {
        // Verify that the SearchService is registered as a tool
        @SuppressWarnings("unchecked")
        List<ToolCallback> toolCallbacks = context.getBean("solrTools", List.class);

        assertNotNull(toolCallbacks);
        assertFalse(toolCallbacks.isEmpty());

        // Verify that the SearchService is properly registered
        boolean searchServiceRegistered = toolCallbacks.stream()
                .anyMatch(callback -> {
                    try {
                        // Check if the callback's string representation contains the tool name "Search"
                        return callback.toString().contains("name=Search");
                    } catch (Exception e) {
                        return false;
                    }
                });

        assertTrue(searchServiceRegistered, "SearchService should be registered as a tool callback");
    }

    @Test
    void testSearchServiceAnnotation() throws NoSuchMethodException {
        // Verify that the SearchService has the @Tool annotation
        assertTrue(searchService.getClass().getMethod("search",
                        String.class,
                        String.class,
                        List.class,
                        List.class,
                        List.class,
                        Integer.class,
                        Integer.class)
                        .isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class),
                "SearchService.search method should have @Tool annotation");
    }
}
