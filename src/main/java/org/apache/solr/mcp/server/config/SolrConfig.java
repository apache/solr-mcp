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
package org.apache.solr.mcp.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.XMLRequestWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Spring Configuration class for Apache Solr client setup and connection
 * management.
 *
 * <p>
 * This configuration class is responsible for creating and configuring the
 * SolrJ client that serves as the primary interface for communication with
 * Apache Solr servers. It handles URL normalization, connection parameters, and
 * timeout configurations to ensure reliable connectivity for the MCP server
 * operations.
 *
 * <p>
 * <strong>Configuration Features:</strong>
 *
 * <ul>
 * <li><strong>Automatic URL Normalization</strong>: Ensures proper Solr URL
 * formatting
 * <li><strong>Connection Timeout Management</strong>: Configurable timeouts for
 * reliability
 * <li><strong>Property Integration</strong>: Uses externalized configuration
 * through properties
 * <li><strong>Production-Ready Defaults</strong>: Optimized timeout values for
 * production use
 * </ul>
 *
 * <p>
 * <strong>URL Processing:</strong>
 *
 * <p>
 * The configuration automatically normalizes Solr URLs to ensure proper
 * communication:
 *
 * <ul>
 * <li>Adds trailing slashes if missing
 * <li>Appends "/solr/" path if not present in the URL
 * <li>Handles various URL formats (with/without protocols, paths, etc.)
 * </ul>
 *
 * <p>
 * <strong>Connection Parameters:</strong>
 *
 * <ul>
 * <li><strong>Connection Timeout</strong>: 10 seconds (10,000ms) for
 * establishing connections
 * <li><strong>Socket Timeout</strong>: 60 seconds (60,000ms) for read
 * operations
 * </ul>
 *
 * <p>
 * <strong>Configuration Example:</strong>
 *
 * <pre>{@code
 * # application.properties
 * solr.url=http://localhost:8983
 *
 * # Results in normalized URL: http://localhost:8983/solr/
 * }</pre>
 *
 * <p>
 * <strong>Supported URL Formats:</strong>
 *
 * <ul>
 * <li>{@code http://localhost:8983} → {@code http://localhost:8983/solr/}
 * <li>{@code http://localhost:8983/} → {@code http://localhost:8983/solr/}
 * <li>{@code http://localhost:8983/solr} → {@code http://localhost:8983/solr/}
 * <li>{@code http://localhost:8983/solr/} → {@code http://localhost:8983/solr/}
 * (unchanged)
 * </ul>
 *
 * @see SolrConfigurationProperties
 * @see HttpJdkSolrClient
 * @see org.springframework.boot.context.properties.EnableConfigurationProperties
 */
@Configuration
@EnableConfigurationProperties(SolrConfigurationProperties.class)
public class SolrConfig {

	private static final int CONNECTION_TIMEOUT_MS = 10000;
	private static final int SOCKET_TIMEOUT_MS = 60000;
	private static final String SOLR_PATH = "solr/";

	/** Default constructor used by Spring to instantiate this configuration. */
	public SolrConfig() {
	}

	/**
	 * Creates and configures a SolrClient bean for Apache Solr communication.
	 *
	 * <p>
	 * This method serves as the primary factory for creating SolrJ client instances
	 * that are used throughout the application for all Solr operations. It performs
	 * automatic URL normalization and applies production-ready timeout
	 * configurations.
	 *
	 * <p>
	 * <strong>URL Normalization Process:</strong>
	 *
	 * <ol>
	 * <li><strong>Trailing Slash</strong>: Ensures URL ends with "/"
	 * <li><strong>Solr Path</strong>: Appends "/solr/" if not already present
	 * <li><strong>Validation</strong>: Checks for proper Solr endpoint format
	 * </ol>
	 *
	 * <p>
	 * <strong>Connection Configuration:</strong>
	 *
	 * <ul>
	 * <li><strong>Connection Timeout</strong>: 10,000ms - Time to establish initial
	 * connection
	 * <li><strong>Socket Timeout</strong>: 60,000ms - Time to wait for
	 * data/response
	 * </ul>
	 *
	 * <p>
	 * <strong>Client Type:</strong>
	 *
	 * <p>
	 * Creates an {@code HttpSolrClient} configured for standard HTTP-based
	 * communication with SolrCloud servers. This client type is suitable for
	 * SolrCloud deployments when used with load balancers.
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * URL normalization is defensive and handles various input formats gracefully.
	 * Invalid URLs or connection failures will be caught during application startup
	 * or first usage, providing clear error messages for troubleshooting.
	 *
	 * <p>
	 * <strong>Production Considerations:</strong>
	 *
	 * <ul>
	 * <li>Timeout values are optimized for production workloads
	 * <li>Connection pooling is handled by the HttpSolrClient internally
	 * <li>Client is thread-safe and suitable for concurrent operations
	 * </ul>
	 *
	 * @param properties
	 *            the injected Solr configuration properties containing connection
	 *            URL
	 * @param jsonResponseParser
	 *            the parser that converts Solr's JSON responses into the NamedList
	 *            tree SolrJ expects
	 * @return configured SolrClient instance ready for use in application services
	 * @see HttpJdkSolrClient.Builder
	 * @see SolrConfigurationProperties#url()
	 */
	@Bean
	SolrClient solrClient(SolrConfigurationProperties properties, JsonResponseParser jsonResponseParser) {
		return buildSolrClient(properties, jsonResponseParser);
	}

	/**
	 * Response parser used by {@link #solrClient}, requesting {@code wt=json} and
	 * converting the response into SolrJ's
	 * {@link org.apache.solr.common.util.NamedList} tree.
	 *
	 * @param objectMapper
	 *            the application's Jackson mapper, reused so Solr responses are
	 *            parsed with the same configuration as the rest of the app
	 * @return the JSON response parser
	 */
	@Bean
	JsonResponseParser jsonResponseParser(ObjectMapper objectMapper) {
		return new JsonResponseParser(objectMapper);
	}

	private static SolrClient buildSolrClient(SolrConfigurationProperties properties,
			JsonResponseParser jsonResponseParser) {
		// Normalise against the URL's *path* only. Testing the whole URL string
		// would see the "/solr/" inside an authority such as http://solr/ and
		// wrongly conclude the path was already present.
		URI parsed = URI.create(properties.url());
		String path = parsed.getPath() == null ? "" : parsed.getPath();
		if (!path.endsWith("/")) {
			path = path + "/";
		}
		if (!path.contains("/" + SOLR_PATH)) {
			path = path + SOLR_PATH;
		}
		String url = parsed.resolve(path).toString();

		// JSON wire format for responses; XML wire format for update requests.
		// The default JavaBin request writer uses a binary codec that requires
		// additional reflection metadata in GraalVM native images.
		// Force HTTP/1.1: the JDK HttpClient's HTTP/2 transport intermittently
		// closes reused connections with an EOFException against Solr/Jetty.
		HttpJdkSolrClient.Builder builder = new HttpJdkSolrClient.Builder(url)
				.withConnectionTimeout(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
				.withIdleTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS).useHttp1_1(true)
				.withResponseParser(jsonResponseParser).withRequestWriter(new XMLRequestWriter());

		// Optional HTTP Basic Authentication: applied only when both credentials
		// are provided so existing unauthenticated deployments are unaffected.
		String username = properties.username();
		String password = properties.password();
		if (StringUtils.hasText(username) && password != null) {
			builder.withBasicAuthCredentials(username, password);
		}

		return builder.build();
	}
}
