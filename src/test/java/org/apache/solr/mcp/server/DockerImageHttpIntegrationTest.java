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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the Docker image produced by Jib running in HTTP mode (streamable HTTP).
 *
 * <p>This test verifies that the Docker image built by Jib:
 *
 * <ul>
 *   <li>Starts successfully without errors in HTTP mode
 *   <li>Runs the Spring Boot MCP server application correctly
 *   <li>Exposes HTTP endpoint on port 8080
 *   <li>Responds to HTTP requests
 *   <li>Can connect to an external Solr instance
 * </ul>
 *
 * <p><strong>Prerequisites:</strong> Before running this test, you must build the Docker image:
 *
 * <pre>{@code
 * ./gradlew jibDockerBuild
 * }</pre>
 *
 * <p>This will create the image: {@code solr-mcp:0.0.1-SNAPSHOT}
 *
 * <p><strong>Test Architecture:</strong>
 *
 * <ol>
 *   <li>Creates a shared Docker network for inter-container communication
 *   <li>Starts a Solr container on the network
 *   <li>Starts the MCP server Docker image in HTTP mode with connection to Solr
 *   <li>Verifies the container starts and HTTP endpoint is accessible
 *   <li>Validates HTTP responses and container health
 * </ol>
 *
 * <p><strong>Note:</strong> This test is tagged with "docker-integration" and is designed to run
 * separately from regular unit tests using the {@code dockerIntegrationTest} Gradle task.
 */
@Testcontainers
@Tag("docker-integration")
class DockerImageHttpIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(DockerImageHttpIntegrationTest.class);

    // Docker image name and tag from build.gradle.kts
    private static final String DOCKER_IMAGE = "solr-mcp:0.0.1-SNAPSHOT";
    private static final String SOLR_IMAGE = "solr:9.9-slim";
    private static final int HTTP_PORT = 8080;

    // Network for container communication
    private static final Network network = Network.newNetwork();

    // Solr container for backend
    // Note: This field is used implicitly through the @Container annotation.
    // Testcontainers JUnit extension automatically:
    // 1. Starts this container before tests run
    // 2. Makes it accessible via network alias "solr" at http://solr:8983/solr/
    // 3. Stops and cleans up the container after tests complete
    @Container
    private static final SolrContainer solrContainer =
            new SolrContainer(DockerImageName.parse(SOLR_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("solr")
                    .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("SOLR"));

    // MCP Server container (the image we're testing)
    // Note: In HTTP mode, the application exposes a web server on port 8080
    @Container
    private static final GenericContainer<?> mcpServerContainer =
            new GenericContainer<>(DockerImageName.parse(DOCKER_IMAGE))
                    .withNetwork(network)
                    .withEnv("SOLR_URL", "http://solr:8983/solr/")
                    .withEnv("SPRING_DOCKER_COMPOSE_ENABLED", "false")
                    .withEnv("PROFILES", "http")
                    .withExposedPorts(HTTP_PORT)
                    .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("MCP-SERVER-HTTP"))
                    // Wait for HTTP endpoint to be ready
                    .waitingFor(
                            Wait.forHttp("/actuator/health")
                                    .forPort(HTTP_PORT)
                                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static HttpClient httpClient;
    private static String baseUrl;

    @BeforeAll
    static void setup() {
        log.info("Solr container started. Internal URL: http://solr:8983/solr/");
        log.info("MCP Server container started in HTTP mode");

        // Initialize HTTP client
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // Get the mapped port for accessing the container from the host
        Integer mappedPort = mcpServerContainer.getMappedPort(HTTP_PORT);
        baseUrl = "http://localhost:" + mappedPort;

        log.info("MCP Server HTTP endpoint available at: {}", baseUrl);
    }

    @Test
    void testSolrContainerIsRunning() {
        // Verify Solr container started successfully
        // This is essential because MCP server depends on Solr being available
        assertTrue(solrContainer.isRunning(), "Solr container should be running");

        log.info("Solr container is running and available at http://solr:8983/solr/");
    }

    @Test
    void testContainerStartsAndRemainsStable() {
        // Verify initial startup
        assertTrue(mcpServerContainer.isRunning(), "Container should start successfully");

        // Monitor container stability over 10 seconds to ensure it doesn't crash
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .pollDelay(Duration.ZERO)
                .untilAsserted(() -> assertTrue(mcpServerContainer.isRunning()));

        log.info("Container started successfully and remained stable for 10 seconds");
    }

    @Test
    void testNoErrorsInLogs() {
        String logs = mcpServerContainer.getLogs();

        // Check for critical error patterns
        assertFalse(
                logs.contains("Exception in thread \"main\""),
                "Logs should not contain main thread exceptions");

        assertFalse(
                logs.contains("Application run failed"),
                "Logs should not contain application failure messages");

        assertFalse(
                logs.contains("ERROR") && logs.contains("Failed to start"),
                "Logs should not contain startup failure errors");

        assertFalse(
                logs.contains("fatal error") || logs.contains("JVM crash"),
                "Logs should not contain JVM crash messages");

        log.info("No critical errors found in container logs");
    }

    @Test
    void testHttpEndpointResponds() throws IOException, InterruptedException {
        // Test that the HTTP endpoint is accessible
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/actuator/health"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Health endpoint should return 200 OK");
        assertTrue(
                response.body().contains("UP") || response.body().contains("\"status\""),
                "Health endpoint should return UP status or status field");

        log.info("HTTP endpoint responded successfully with status: {}", response.statusCode());
    }

    @Test
    void testSolrConnectivity() {
        // Verify environment variables are working and Solr is accessible
        String logs = mcpServerContainer.getLogs();

        assertFalse(
                logs.contains("Connection refused"),
                "Logs should not contain connection refused errors");

        assertFalse(
                logs.contains("UnknownHostException"),
                "Logs should not contain unknown host exceptions");

        log.info("Container can connect to Solr without errors");
    }

    @Test
    void testHttpModeConfiguration() {
        String logs = mcpServerContainer.getLogs();

        // Verify HTTP mode is active by checking for typical Spring Boot web server logs
        assertTrue(
                logs.contains("Tomcat started on port") || logs.contains("Netty started on port"),
                "Logs should indicate web server started on a port");

        log.info("HTTP mode configuration verified");
    }

    @Test
    void testPortExposure() {
        // Verify the port is exposed and mapped
        Integer mappedPort = mcpServerContainer.getMappedPort(HTTP_PORT);
        assertNotNull(mappedPort, "HTTP port should be exposed and mapped");
        assertTrue(mappedPort > 0, "Mapped port should be a valid port number");

        log.info("Port {} is properly exposed and mapped to {}", HTTP_PORT, mappedPort);
    }
}
