package org.apache.solr.mcp.server;

import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.apache.solr.mcp.server.metadata.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;

/**
 * Main Spring Boot application class for the Apache Solr Model Context Protocol (MCP) Server.
 * 
 * <p>This class serves as the entry point for the Solr MCP Server application, which provides
 * a bridge between AI clients (such as Claude Desktop) and Apache Solr search and indexing
 * capabilities through the Model Context Protocol specification.</p>
 * 
 * <p><strong>Application Architecture:</strong></p>
 * <p>The application follows a service-oriented architecture where each major Solr operation
 * category is encapsulated in its own service class:</p>
 * <ul>
 *   <li><strong>SearchService</strong>: Search operations, faceting, sorting, pagination</li>
 *   <li><strong>IndexingService</strong>: Document indexing, schema-less ingestion, batch processing</li>
 *   <li><strong>CollectionService</strong>: Collection management, metrics, health monitoring</li>
 *   <li><strong>SchemaService</strong>: Schema introspection and field management</li>
 * </ul>
 * 
 * <p><strong>MCP Tool Registration:</strong></p>
 * <p>The application automatically registers all service methods annotated with {@code @Tool}
 * as available MCP tools that AI clients can invoke. This registration happens through the
 * {@link #solrTools(SearchService, IndexingService, CollectionService, SchemaService)} method
 * which creates {@code ToolCallback} instances for each service.</p>
 * 
 * <p><strong>Spring Boot Features:</strong></p>
 * <ul>
 *   <li><strong>Auto-Configuration</strong>: Automatic setup of Solr client and service beans</li>
 *   <li><strong>Property Management</strong>: Externalized configuration through application.properties</li>
 *   <li><strong>Dependency Injection</strong>: Automatic wiring of service dependencies</li>
 *   <li><strong>Component Scanning</strong>: Automatic discovery of service classes</li>
 * </ul>
 * 
 * <p><strong>Communication Flow:</strong></p>
 * <ol>
 *   <li>AI client connects to MCP server via stdio</li>
 *   <li>Client discovers available tools through MCP protocol</li>
 *   <li>Client invokes tools with natural language parameters</li>
 *   <li>Server routes requests to appropriate service methods</li>
 *   <li>Services interact with Solr via SolrJ client library</li>
 *   <li>Results are serialized and returned to AI client</li>
 * </ol>
 * 
 * <p><strong>Configuration Requirements:</strong></p>
 * <p>The application requires the following configuration properties:</p>
 * <pre>{@code
 * # application.properties
 * solr.url=http://localhost:8983
 * }</pre>
 * 
 * <p><strong>Deployment Considerations:</strong></p>
 * <ul>
 *   <li>Ensure Solr server is running and accessible at configured URL</li>
 *   <li>Verify network connectivity between MCP server and Solr</li>
 *   <li>Configure appropriate timeouts for production workloads</li>
 *   <li>Monitor application logs for connection and performance issues</li>
 * </ul>
 * 
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 * 
 * @see SearchService
 * @see IndexingService
 * @see CollectionService
 * @see SchemaService
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public class Main {

    /**
     * Main application entry point that starts the Spring Boot application.
     * 
     * <p>This method initializes the Spring application context, configures all
     * service beans, establishes Solr connectivity, and begins listening for
     * MCP client connections via standard input/output.</p>
     * 
     * <p><strong>Startup Process:</strong></p>
     * <ol>
     *   <li>Initialize Spring Boot application context</li>
     *   <li>Load configuration properties from various sources</li>
     *   <li>Create and configure SolrClient bean</li>
     *   <li>Initialize all service beans with dependency injection</li>
     *   <li>Register MCP tools from service methods</li>
     *   <li>Start MCP server listening on stdio</li>
     * </ol>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Startup failures typically indicate configuration issues such as:</p>
     * <ul>
     *   <li>Missing or invalid Solr URL configuration</li>
     *   <li>Network connectivity issues to Solr server</li>
     *   <li>Missing required dependencies or classpath issues</li>
     * </ul>
     * 
     * @param args command-line arguments passed to the application
     * 
     * @see SpringApplication#run(Class, String...)
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    /**
     * Creates and configures the list of MCP tool callbacks from all Solr service classes.
     * 
     * <p>This method serves as the central registry for all MCP tools exposed by the
     * application. It automatically converts service methods annotated with {@code @Tool}
     * into {@code ToolCallback} instances that can be invoked by AI clients through
     * the Model Context Protocol.</p>
     * 
     * <p><strong>Tool Registration Process:</strong></p>
     * <ol>
     *   <li>Spring AI framework scans service classes for {@code @Tool} annotations</li>
     *   <li>Method signatures and descriptions are extracted for tool metadata</li>
     *   <li>Callback wrappers are created for each annotated method</li>
     *   <li>Tools are made available to MCP clients for invocation</li>
     * </ol>
     * 
     * <p><strong>Service Coverage:</strong></p>
     * <ul>
     *   <li><strong>SearchService</strong>: search operations with filtering and faceting</li>
     *   <li><strong>IndexingService</strong>: document indexing and schema-less ingestion</li>
     *   <li><strong>CollectionService</strong>: collection metrics, health checks, and management</li>
     *   <li><strong>SchemaService</strong>: schema introspection and field information</li>
     * </ul>
     * 
     * <p><strong>Tool Discoverability:</strong></p>
     * <p>AI clients can discover available tools and their parameters through MCP
     * protocol introspection, enabling natural language interactions with Solr
     * without requiring knowledge of the underlying API structure.</p>
     * 
     * <p><strong>Bean Lifecycle:</strong></p>
     * <p>This method is called during Spring context initialization after all
     * service dependencies have been resolved and injected, ensuring that tools
     * are fully functional when registered.</p>
     * 
     * @param searchService injected service for Solr search operations
     * @param indexingService injected service for document indexing operations  
     * @param collectionService injected service for collection management operations
     * @param schemaService injected service for schema introspection operations
     * @return list of tool callbacks that can be invoked by MCP clients
     * 
     * @see ToolCallbacks#from(Object...)
     * @see org.springframework.ai.tool.annotation.Tool
     * @see ToolCallback
     */
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