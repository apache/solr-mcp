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

import ch.qos.logback.core.status.NopStatusListener;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.schema.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot application class for the Apache Solr Model Context Protocol
 * (MCP) Server.
 *
 * <p>
 * This class serves as the entry point for the Solr MCP Server application,
 * which provides a bridge between AI clients (such as Claude Desktop) and
 * Apache Solr search and indexing capabilities through the Model Context
 * Protocol specification.
 *
 * <p>
 * <strong>Application Architecture:</strong>
 *
 * <p>
 * The application follows a service-oriented architecture where each major Solr
 * operation category is encapsulated in its own service class:
 *
 * <ul>
 * <li><strong>SearchService</strong>: Search operations, faceting, sorting,
 * pagination
 * <li><strong>IndexingService</strong>: Document indexing, schema-less
 * ingestion, batch processing
 * <li><strong>CollectionService</strong>: Collection management, metrics,
 * health monitoring
 * <li><strong>SchemaService</strong>: Schema introspection and field management
 * </ul>
 *
 * <p>
 * <strong>Spring Boot Features:</strong>
 *
 * <ul>
 * <li><strong>Auto-Configuration</strong>: Automatic setup of Solr client and
 * service beans
 * <li><strong>Property Management</strong>: Externalized configuration through
 * application.properties
 * <li><strong>Dependency Injection</strong>: Automatic wiring of service
 * dependencies
 * <li><strong>Component Scanning</strong>: Automatic discovery of service
 * classes
 * </ul>
 *
 * <p>
 * <strong>Communication Flow:</strong>
 *
 * <ol>
 * <li>AI client connects to MCP server via stdio
 * <li>Client discovers available tools through MCP protocol
 * <li>Client invokes tools with natural language parameters
 * <li>Server routes requests to appropriate service methods
 * <li>Services interact with Solr via SolrJ client library
 * <li>Results are serialized and returned to AI client
 * </ol>
 *
 * <p>
 * <strong>Configuration Requirements:</strong>
 *
 * <p>
 * The application requires the following configuration properties:
 *
 * <pre>{@code
 * # application.properties
 * solr.url=http://localhost:8983
 * }</pre>
 *
 * <p>
 * <strong>Deployment Considerations:</strong>
 *
 * <ul>
 * <li>Ensure Solr server is running and accessible at configured URL
 * <li>Verify network connectivity between MCP server and Solr
 * <li>Configure appropriate timeouts for production workloads
 * <li>Monitor application logs for connection and performance issues
 * </ul>
 *
 * @see SearchService
 * @see IndexingService
 * @see CollectionService
 * @see SchemaService
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public class Main {

	/** Default constructor used by Spring Boot to bootstrap the application. */
	public Main() {
	}

	/**
	 * Logback's own system property naming the status listener to install during
	 * its self-initialization.
	 */
	static final String LOGBACK_STATUS_LISTENER_PROPERTY = "logback.statusListenerClass";

	public static void main(String[] args) {
		silenceLogbackStatusOutput();
		SpringApplication.run(Main.class, args);
	}

	/**
	 * Keeps logback's internal status messages off stdout, which the STDIO
	 * transport reserves for JSON-RPC.
	 *
	 * <p>
	 * Logback initializes itself on the first {@code LoggerFactory} touch, long
	 * before Spring Boot's logging system runs. In a native image it cannot read
	 * its version from the manifest, raises a {@code |-WARN}, and
	 * {@code LogbackServiceProvider} then prints its whole status list to stdout -
	 * unless a status listener is already installed. It installs one from this
	 * system property before deciding whether to print, so setting it here, before
	 * anything can touch a logger, is the earliest and only hook. Spring Boot's
	 * later configuration ({@code logback-spring.xml}) is unaffected.
	 *
	 * <p>
	 * An explicit {@code -Dlogback.statusListenerClass=...} on the command line
	 * wins, so logback's own configuration can still be debugged with
	 * {@code OnConsoleStatusListener}.
	 */
	static void silenceLogbackStatusOutput() {
		if (System.getProperty(LOGBACK_STATUS_LISTENER_PROPERTY) == null) {
			System.setProperty(LOGBACK_STATUS_LISTENER_PROPERTY, NopStatusListener.class.getName());
		}
	}
}
