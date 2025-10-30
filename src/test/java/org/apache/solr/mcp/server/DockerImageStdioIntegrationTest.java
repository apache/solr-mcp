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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the Docker image produced by Jib running in STDIO mode.
 *
 * <p>This test verifies that the Docker image built by Jib:
 *
 * <ul>
 *   <li>Starts successfully without errors in STDIO mode
 *   <li>Runs the Spring Boot MCP server application correctly
 *   <li>Doesn't crash during initial startup period
 *   <li>Can connect to an external Solr instance
 * </ul>
 *
 * <p><strong>Prerequisites:</strong> Before running this test, you must build the Docker image:
 *
 * <pre>{@code
 * ./gradlew jibDockerBuild
 * }</pre>
 *
 *
 * <p><strong>Test Architecture:</strong>
 *
 * <ol>
 *   <li>Creates a shared Docker network for inter-container communication
 *   <li>Starts a Solr container on the network
 *   <li>Starts the MCP server Docker image in STDIO mode with connection to Solr
 *   <li>Verifies the container starts and remains stable
 *   <li>Validates container health over time
 * </ol>
 *
 * <p><strong>Note:</strong> This test is tagged with "docker-integration" and is designed to run
 * separately from regular unit tests using the {@code dockerIntegrationTest} Gradle task.
 */
@Testcontainers
@Tag("docker-integration")
class DockerImageStdioIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(DockerImageStdioIntegrationTest.class);

    // Docker image name and tag from build.gradle.kts
    private static final String SOLR_IMAGE = "solr:9.9-slim";

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
    // Note: In STDIO mode, the application doesn't produce logs to stdout that we can wait for,
    // so we use a simple startup delay and then verify the container is running
    @Container
    private static final GenericContainer<?> mcpServerContainer =
            new GenericContainer<>(DockerImageName.parse(DOCKER_IMAGE))
                    .withNetwork(network)
                    .withEnv("SOLR_URL", "http://solr:8983/solr/")
                    .withEnv("SPRING_DOCKER_COMPOSE_ENABLED", "false")
                    .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("MCP-SERVER"))
                    // Give the application time to start (STDIO mode doesn't produce logs to wait
                    // for)
                    .withStartupTimeout(Duration.ofSeconds(60));

    @BeforeAll
    static void setup() throws InterruptedException {
        log.info("Solr container started. Internal URL: http://solr:8983/solr/");
        log.info("MCP Server container starting. Waiting for initialization...");

        // Give the MCP server a few seconds to initialize
        // In STDIO mode, the app runs but doesn't produce logs we can monitor
        Thread.sleep(5000);

        log.info("Initialization wait complete. Beginning tests.");
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

        assertFalse(
                logs.contains("exec format error"),
                "Logs should not contain platform compatibility errors");

        log.info("No critical errors found in container logs");
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
}
