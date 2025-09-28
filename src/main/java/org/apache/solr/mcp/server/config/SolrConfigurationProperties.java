package org.apache.solr.mcp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot Configuration Properties record for Apache Solr connection settings.
 * 
 * <p>This immutable configuration record encapsulates all external configuration
 * properties required for establishing and maintaining connections to Apache Solr
 * servers. It follows Spring Boot's type-safe configuration properties pattern
 * using Java records for enhanced immutability and reduced boilerplate.</p>
 * 
 * <p><strong>Configuration Binding:</strong></p>
 * <p>This record automatically binds to configuration properties with the "solr"
 * prefix from various configuration sources including:</p>
 * <ul>
 *   <li><strong>application.properties</strong>: {@code solr.url=http://localhost:8983}</li>
 *   <li><strong>application.yml</strong>: {@code solr: url: http://localhost:8983}</li>
 *   <li><strong>Environment Variables</strong>: {@code SOLR_URL=http://localhost:8983}</li>
 *   <li><strong>Command Line Arguments</strong>: {@code --solr.url=http://localhost:8983}</li>
 * </ul>
 * 
 * <p><strong>Record Benefits:</strong></p>
 * <ul>
 *   <li><strong>Immutability</strong>: Properties cannot be modified after construction</li>
 *   <li><strong>Type Safety</strong>: Compile-time validation of property types</li>
 *   <li><strong>Automatic Generation</strong>: Constructor, getters, equals, hashCode, toString</li>
 *   <li><strong>Validation Support</strong>: Compatible with Spring Boot validation annotations</li>
 * </ul>
 * 
 * <p><strong>URL Format Requirements:</strong></p>
 * <p>The Solr URL should point to the base Solr server endpoint. The configuration
 * system will automatically normalize URLs to ensure proper formatting:</p>
 * <ul>
 *   <li><strong>Valid Examples</strong>:</li>
 *   <ul>
 *     <li>{@code http://localhost:8983}</li>
 *     <li>{@code http://localhost:8983/}</li>
 *     <li>{@code http://localhost:8983/solr}</li>
 *     <li>{@code http://localhost:8983/solr/}</li>
 *     <li>{@code https://solr.example.com:8983}</li>
 *   </ul>
 * </ul>
 * 
 * <p><strong>Environment-Specific Configuration:</strong></p>
 * <pre>{@code
 * # Development
 * solr.url=http://localhost:8983
 * 
 * # Staging  
 * solr.url=http://solr-staging.company.com:8983
 * 
 * # Production
 * solr.url=https://solr-prod.company.com:8983
 * }</pre>
 * 
 * <p><strong>Integration with Dependency Injection:</strong></p>
 * <p>This record is automatically instantiated by Spring Boot's configuration
 * properties mechanism and can be injected into any Spring-managed component
 * that requires Solr connection information.</p>
 * 
 * <p><strong>Validation Considerations:</strong></p>
 * <p>While basic validation is handled by the configuration system, additional
 * URL validation and normalization occurs in the {@link SolrConfig} class
 * during SolrClient bean creation.</p>
 * 
 * @param url the base URL of the Apache Solr server (required, non-null)
 * 
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 * 
 * @see SolrConfig
 * @see org.springframework.boot.context.properties.ConfigurationProperties
 * @see org.springframework.boot.context.properties.EnableConfigurationProperties
 */
@ConfigurationProperties(prefix = "solr")
public record SolrConfigurationProperties(String url) {

}