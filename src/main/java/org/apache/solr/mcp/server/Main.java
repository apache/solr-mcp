package org.apache.solr.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public List<ToolCallback> solrTools(
            SearchService searchService,
            IndexingService indexingService,
            CollectionService collectionService,
            SchemaService schemaService) {
        return Arrays.asList(ToolCallbacks.from(
                searchService,
                indexingService,
                collectionService,
                schemaService
        ));
    }
}
