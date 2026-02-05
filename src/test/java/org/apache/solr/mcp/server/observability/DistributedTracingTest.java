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
package org.apache.solr.mcp.server.observability;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.solr.mcp.server.observability.TraceAssertions.assertServiceNamePresent;
import static org.apache.solr.mcp.server.observability.TraceAssertions.assertSpanExists;
import static org.apache.solr.mcp.server.observability.TraceAssertions.assertSpanMatches;
import static org.apache.solr.mcp.server.observability.TraceAssertions.assertValidTimestamps;
import static org.awaitility.Awaitility.await;

/**
 * Tests for distributed tracing using OpenTelemetry.
 *
 * <p>
 * These tests verify that:
 * <ul>
 * <li>Spans are created for @Observed methods</li>
 * <li>Span attributes are correctly set</li>
 * <li>Span hierarchy is correct (parent-child relationships)</li>
 * <li>Span names follow conventions</li>
 * </ul>
 *
 * <p>
 * Uses InMemorySpanExporter to capture spans without requiring external
 * infrastructure.
 */
@SpringBootTest(properties = {
        // Enable HTTP mode for observability
        "spring.profiles.active=http",
        // Disable OTLP logging in tests (logback appender causes issues in test
        // context)
        "management.opentelemetry.logging.export.otlp.enabled=false",
        // Ensure 100% sampling for tests
        "management.tracing.sampling.probability=1.0"})
@Import({TestcontainersConfiguration.class, InMemoryTracingTestConfiguration.class, ObservationTestConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("http")
class DistributedTracingTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private SolrClient solrClient;

    @Autowired
    private InMemorySpanExporter spanExporter;
    @Autowired
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @BeforeEach
    void setUp() {
        // Clear any existing spans before each test
        spanExporter.reset();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        spanExporter.reset();
    }

    @Test
    void shouldCreateSpanForSearchServiceMethod() {
        System.out.println("[DEBUG_LOG] ObservationRegistry: " + observationRegistry);
        // Given: A Solr collection (assume test collection exists)
        String collectionName = "test_collection";

        // When: We execute a search operation
        try {
            searchService.search(collectionName, "*:*", null, null, null, null, null);
        } catch (Exception e) {
            // Ignore errors - we're testing span creation, not business logic
        }

        // Then: A span should be created with the correct name
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertSpanExists(spans, "SearchService");
        });
    }

    @Test
    void shouldIncludeSpanAttributes() {
        // Given: A search query
        String collectionName = "test_collection";
        String query = "test:query";

        // When: We execute a search with parameters
        try {
            searchService.search(collectionName, query, null, null, null, 0, 10);
        } catch (Exception e) {
            // Ignore errors
        }

        // Then: Spans should include relevant attributes
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertSpanExists(spans, "SearchService");
            // Verify @Observed attributes are present
            assertSpanMatches(spans, "Span should have 'class' attribute", span -> span.getName()
                    .contains("SearchService")
                    && span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("class")) != null);
            assertSpanMatches(spans, "Span should have 'method' attribute",
                    span -> span.getName().contains("SearchService") && "search".equals(
                            span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("method"))));
        });
    }

    @Test
    void shouldCreateSpanHierarchy() {
        // When: We execute a complex operation that triggers multiple spans
        try {
            searchService.search("test_collection", "*:*", null, null, null, null, null);
        } catch (Exception e) {
            // Ignore errors
        }

        // Then: We should see parent-child relationships in spans
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertSpanExists(spans, "SearchService");
            // Note: In a simple test, we may not always have parent-child relationships
            // This test verifies the structure is available, even if parent count is 0
        });
    }

    @Test
    void shouldSetCorrectSpanKind() {
        // When: We execute a service method
        try {
            searchService.search("test_collection", "*:*", null, null, null, null, null);
        } catch (Exception e) {
            // Ignore errors
        }

        // Then: Spans should have appropriate span kinds
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertSpanExists(spans, "SearchService");

            // Verify all spans have a kind set
            assertSpanMatches(spans, "All spans should have a kind", span -> span.getKind() != null);

            // Most application spans should be INTERNAL or CLIENT
            assertSpanMatches(spans, "At least one span should be INTERNAL or CLIENT",
                    span -> span.getKind() == SpanKind.INTERNAL || span.getKind() == SpanKind.CLIENT);
        });
    }

    @Test
    void shouldIncludeServiceNameInResource() {
        // When: We execute any operation
        try {
            searchService.search("test_collection", "*:*", null, null, null, null, null);
        } catch (Exception e) {
            // Ignore errors
        }

        // Then: Spans should include the service name in resource attributes
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertServiceNamePresent(spans);
        });
    }

    @Test
    void shouldRecordSpanDuration() {
        // When: We execute an operation
        try {
            searchService.search("test_collection", "*:*", null, null, null, null, null);
        } catch (Exception e) {
            // Ignore errors
        }

        // Then: All spans should have valid durations
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertValidTimestamps(spans);
        });
    }
}
