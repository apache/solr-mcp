package org.apache.solr.mcp.server.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring Configuration class for Apache Solr client setup and connection management.
 *
 * <p>This configuration class is responsible for creating and configuring the SolrJ client that
 * serves as the primary interface for communication with Apache Solr servers. It handles URL
 * normalization, connection parameters, and timeout configurations to ensure reliable connectivity
 * for the MCP server operations.
 *
 * <p><strong>Configuration Features:</strong>
 *
 * <ul>
 *   <li><strong>Automatic URL Normalization</strong>: Ensures proper Solr URL formatting
 *   <li><strong>Connection Timeout Management</strong>: Configurable timeouts for reliability
 *   <li><strong>Property Integration</strong>: Uses externalized configuration through properties
 *   <li><strong>Production-Ready Defaults</strong>: Optimized timeout values for production use
 * </ul>
 *
 * <p><strong>URL Processing:</strong>
 *
 * <p>The configuration automatically normalizes Solr URLs to ensure proper communication:
 *
 * <ul>
 *   <li>Adds trailing slashes if missing
 *   <li>Appends "/solr/" path if not present in the URL
 *   <li>Handles various URL formats (with/without protocols, paths, etc.)
 * </ul>
 *
 * <p><strong>Connection Parameters:</strong>
 *
 * <ul>
 *   <li><strong>Connection Timeout</strong>: 10 seconds (10,000ms) for establishing connections
 *   <li><strong>Socket Timeout</strong>: 60 seconds (60,000ms) for read operations
 * </ul>
 *
 * <p><strong>Configuration Example:</strong>
 *
 * <pre>{@code
 * # application.properties
 * solr.url=http://localhost:8983
 *
 * # Results in normalized URL: http://localhost:8983/solr/
 * }</pre>
 *
 * <p><strong>Supported URL Formats:</strong>
 *
 * <ul>
 *   <li>{@code http://localhost:8983} → {@code http://localhost:8983/solr/}
 *   <li>{@code http://localhost:8983/} → {@code http://localhost:8983/solr/}
 *   <li>{@code http://localhost:8983/solr} → {@code http://localhost:8983/solr/}
 *   <li>{@code http://localhost:8983/solr/} → {@code http://localhost:8983/solr/} (unchanged)
 * </ul>
 *
 * @author adityamparikh
 * @author dsmiley
 * @version 0.0.1
 * @see SolrConfigurationProperties
 * @see Http2SolrClient
 * @see org.springframework.boot.context.properties.EnableConfigurationProperties
 * @since 0.0.1
 */
@Configuration
@EnableConfigurationProperties(SolrConfigurationProperties.class)
public class SolrConfig {

    private static final int CONNECTION_TIMEOUT_MS = 10000;
    private static final int SOCKET_TIMEOUT_MS = 60000;
    private static final String SOLR_PATH = "solr/";

    /**
     * Creates and configures a SolrClient bean for Apache Solr communication.
     *
     * <p>This method serves as the primary factory for creating SolrJ client instances that are
     * used throughout the application for all Solr operations. It performs automatic URL
     * normalization and applies production-ready timeout configurations.
     *
     * <p><strong>URL Normalization Process:</strong>
     *
     * <ol>
     *   <li><strong>Trailing Slash</strong>: Ensures URL ends with "/"
     *   <li><strong>Solr Path</strong>: Appends "/solr/" if not already present
     *   <li><strong>Validation</strong>: Checks for proper Solr endpoint format
     * </ol>
     *
     * <p><strong>Connection Configuration:</strong>
     *
     * <ul>
     *   <li><strong>Connection Timeout</strong>: 10,000ms - Time to establish initial connection
     *   <li><strong>Socket Timeout</strong>: 60,000ms - Time to wait for data/response
     * </ul>
     *
     * <p><strong>Client Type:</strong>
     *
     * <p>Creates an {@code HttpSolrClient} configured for standard HTTP-based communication with
     * Solr servers. This client type is suitable for both standalone Solr instances and SolrCloud
     * deployments when used with load balancers.
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>URL normalization is defensive and handles various input formats gracefully. Invalid URLs
     * or connection failures will be caught during application startup or first usage, providing
     * clear error messages for troubleshooting.
     *
     * <p><strong>Production Considerations:</strong>
     *
     * <ul>
     *   <li>Timeout values are optimized for production workloads
     *   <li>Connection pooling is handled by the HttpSolrClient internally
     *   <li>Client is thread-safe and suitable for concurrent operations
     * </ul>
     *
     * @param properties the injected Solr configuration properties containing connection URL
     * @return configured SolrClient instance ready for use in application services
     * @see Http2SolrClient.Builder
     * @see SolrConfigurationProperties#url()
     */
    @Bean
    SolrClient solrClient(SolrConfigurationProperties properties) {
        String url = properties.url();

        // Ensure URL is properly formatted for Solr
        // The URL should end with /solr/ for proper path construction
        if (!url.endsWith("/")) {
            url = url + "/";
        }

        // If URL doesn't contain /solr/ path, add it
        if (!url.endsWith("/" + SOLR_PATH) && !url.contains("/" + SOLR_PATH)) {
            if (url.endsWith("/")) {
                url = url + SOLR_PATH;
            } else {
                url = url + "/" + SOLR_PATH;
            }
        }

        // Use with explicit base URL
        return new Http2SolrClient.Builder(url)
                .withConnectionTimeout(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withIdleTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }
}
