package org.apache.solr.mcp.server;

import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.apache.solr.mcp.server.metadata.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot application class for the Apache Solr Model Context Protocol (MCP) Server.
 *
 * <p>This class serves as the entry point for the Solr MCP Server application, which provides a
 * bridge between AI clients (such as Claude Desktop) and Apache Solr search and indexing
 * capabilities through the Model Context Protocol specification.
 *
 * <p><strong>Application Architecture:</strong>
 *
 * <p>The application follows a service-oriented architecture where each major Solr operation
 * category is encapsulated in its own service class:
 *
 * <ul>
 *   <li><strong>SearchService</strong>: Search operations, faceting, sorting, pagination
 *   <li><strong>IndexingService</strong>: Document indexing, schema-less ingestion, batch
 *       processing
 *   <li><strong>CollectionService</strong>: Collection management, metrics, health monitoring
 *   <li><strong>SchemaService</strong>: Schema introspection and field management
 * </ul>
 *
 * <p><strong>Spring Boot Features:</strong>
 *
 * <ul>
 *   <li><strong>Auto-Configuration</strong>: Automatic setup of Solr client and service beans
 *   <li><strong>Property Management</strong>: Externalized configuration through
 *       application.properties
 *   <li><strong>Dependency Injection</strong>: Automatic wiring of service dependencies
 *   <li><strong>Component Scanning</strong>: Automatic discovery of service classes
 * </ul>
 *
 * <p><strong>Communication Flow:</strong>
 *
 * <ol>
 *   <li>AI client connects to MCP server via stdio
 *   <li>Client discovers available tools through MCP protocol
 *   <li>Client invokes tools with natural language parameters
 *   <li>Server routes requests to appropriate service methods
 *   <li>Services interact with Solr via SolrJ client library
 *   <li>Results are serialized and returned to AI client
 * </ol>
 *
 * <p><strong>Configuration Requirements:</strong>
 *
 * <p>The application requires the following configuration properties:
 *
 * <pre>{@code
 * # application.properties
 * solr.url=http://localhost:8983
 * }</pre>
 *
 * <p><strong>Deployment Considerations:</strong>
 *
 * <ul>
 *   <li>Ensure Solr server is running and accessible at configured URL
 *   <li>Verify network connectivity between MCP server and Solr
 *   <li>Configure appropriate timeouts for production workloads
 *   <li>Monitor application logs for connection and performance issues
 * </ul>
 *
 * @author adityamparikh
 * @version 0.0.1
 * @see SearchService
 * @see IndexingService
 * @see CollectionService
 * @see SchemaService
 * @see org.springframework.boot.SpringApplication
 * @since 0.0.1
 */
@SpringBootApplication
public class Main {

    /**
     * Main application entry point that starts the Spring Boot application.
     *
     * <p>This method initializes the Spring application context, configures all service beans,
     * establishes Solr connectivity, and begins listening for MCP client connections via standard
     * input/output.
     *
     * <p><strong>Startup Process:</strong>
     *
     * <ol>
     *   <li>Initialize Spring Boot application context
     *   <li>Load configuration properties from various sources
     *   <li>Create and configure SolrClient bean
     *   <li>Initialize all service beans with dependency injection
     *   <li>Register MCP tools from service methods
     *   <li>Start MCP server listening on stdio
     * </ol>
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>Startup failures typically indicate configuration issues such as:
     *
     * <ul>
     *   <li>Missing or invalid Solr URL configuration
     *   <li>Network connectivity issues to Solr server
     *   <li>Missing required dependencies or classpath issues
     * </ul>
     *
     * @param args command-line arguments passed to the application
     * @see SpringApplication#run(Class, String...)
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
