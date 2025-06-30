package org.apache.solr.mcp.server;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {Main.class, MainBeanTest.TestConfig.class})
class MainBeanTest {

    @Autowired
    private List<ToolCallback> solrTools;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public SearchService searchService() {
            return Mockito.mock(SearchService.class);
        }
    }

    @Test
    void testSolrToolsBean() {
        // Verify that the solrTools bean is created
        assertNotNull(solrTools);
        assertFalse(solrTools.isEmpty());
    }
}
