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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot Configuration Properties record for Apache Solr connection settings.
 *
 * <p>This immutable configuration record encapsulates all external configuration properties
 * required for establishing and maintaining connections to Apache Solr servers. It follows Spring
 * Boot's type-safe configuration properties pattern using Java records for enhanced immutability
 * and reduced boilerplate.
 *
 * <p><strong>Configuration Binding:</strong>
 *
 * <p>This record automatically binds to configuration properties with the "solr" prefix from
 * various configuration sources including:
 *
 * <ul>
 *   <li><strong>application.properties</strong>: {@code solr.url=http://localhost:8983}
 *   <li><strong>application.yml</strong>: {@code solr: url: http://localhost:8983}
 *   <li><strong>Environment Variables</strong>: {@code SOLR_URL=http://localhost:8983}
 *   <li><strong>Command Line Arguments</strong>: {@code --solr.url=http://localhost:8983}
 * </ul>
 *
 * <p><strong>Record Benefits:</strong>
 *
 * <ul>
 *   <li><strong>Immutability</strong>: Properties cannot be modified after construction
 *   <li><strong>Type Safety</strong>: Compile-time validation of property types
 *   <li><strong>Automatic Generation</strong>: Constructor, getters, equals, hashCode, toString
 *   <li><strong>Validation Support</strong>: Compatible with Spring Boot validation annotations
 * </ul>
 *
 * <p><strong>URL Format Requirements:</strong>
 *
 * <p>The Solr URL should point to the base Solr server endpoint. The configuration system will
 * automatically normalize URLs to ensure proper formatting:
 *
 * <ul>
 *   <li><strong>Valid Examples</strong>:
 *       <ul>
 *         <li>{@code http://localhost:8983}
 *         <li>{@code http://localhost:8983/}
 *         <li>{@code http://localhost:8983/solr}
 *         <li>{@code http://localhost:8983/solr/}
 *         <li>{@code https://solr.example.com:8983}
 *       </ul>
 * </ul>
 *
 * <p><strong>Environment-Specific Configuration:</strong>
 *
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
 * <p><strong>Integration with Dependency Injection:</strong>
 *
 * <p>This record is automatically instantiated by Spring Boot's configuration properties mechanism
 * and can be injected into any Spring-managed component that requires Solr connection information.
 *
 * <p><strong>Validation Considerations:</strong>
 *
 * <p>While basic validation is handled by the configuration system, additional URL validation and
 * normalization occurs in the {@link SolrConfig} class during SolrClient bean creation.
 *
 * @param url the base URL of the Apache Solr server (required, non-null)
 * @version 0.0.1
 * @see SolrConfig
 * @see org.springframework.boot.context.properties.ConfigurationProperties
 * @see org.springframework.boot.context.properties.EnableConfigurationProperties
 * @since 0.0.1
 */
@ConfigurationProperties(prefix = "solr")
public record SolrConfigurationProperties(String url) {
}
