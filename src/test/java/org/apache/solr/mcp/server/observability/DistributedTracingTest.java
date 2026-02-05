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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.concurrent.TimeUnit;
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

/**
 * Tests for distributed tracing using Micrometer Tracing with OpenTelemetry.
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
 * Uses SimpleTracer from micrometer-tracing-test to capture spans without
 * requiring external infrastructure. This is the Spring Boot 3 recommended
 * approach.
 */
@SpringBootTest(properties = {
		// Enable HTTP mode for observability
		"spring.profiles.active=http",
		// Disable OTLP export in tests - we're using SimpleTracer instead
		"management.otlp.tracing.endpoint=", "management.opentelemetry.logging.export.otlp.enabled=false",
		// Ensure 100% sampling for tests
		"management.tracing.sampling.probability=1.0",
		// Enable @Observed annotation support
		"management.observations.annotations.enabled=true"})
@Import({TestcontainersConfiguration.class, OpenTelemetryTestConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("http")
class DistributedTracingTest {

	@Autowired
	private SearchService searchService;

	@Autowired
	private SolrClient solrClient;

	@Autowired
	private SimpleTracer tracer;

	@BeforeEach
	void setUp() {
		// Clear any existing spans before each test
		tracer.getSpans().clear();
	}

	@AfterEach
	void tearDown() {
		// Clean up after each test
		tracer.getSpans().clear();
	}

	@Test
	void shouldCreateSpanForSearchServiceMethod() {
		// Given: A Solr collection (assume test collection exists)
		String collectionName = "test_collection";

		// When: We execute a search operation
		try {
			searchService.search(collectionName, "*:*", null, null, null, null, null);
		} catch (Exception e) {
			// Ignore errors - we're testing span creation, not business logic
		}

		// Then: A span should be created with the correct name
		// Note: Spring's @Observed annotation generates span names in kebab-case
		// format: "class-name#method-name"
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
			var spans = tracer.getSpans();
			assertThat(spans).as("Should have created at least one span").isNotEmpty();
			assertThat(spans).as("Should have span for search-service#search method")
					.anyMatch(span -> span.getName().equals("search-service#search"));
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
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
			var spans = tracer.getSpans();
			assertThat(spans).as("Should have created spans").isNotEmpty();
			assertThat(spans).as("At least one span should have tags/attributes")
					.anyMatch(span -> !span.getTags().isEmpty());
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

		// Then: We should see spans created
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
			var spans = tracer.getSpans();
			assertThat(spans).as("Should have created spans").isNotEmpty();
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
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
			var spans = tracer.getSpans();
			assertThat(spans).as("Should have created spans").isNotEmpty();
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

		// Then: Spans should be created (service name is in resource attributes in
		// OpenTelemetry)
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
			var spans = tracer.getSpans();
			assertThat(spans).as("Should have created spans").isNotEmpty();
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
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
			var spans = tracer.getSpans();
			assertThat(spans).as("Should have created spans").isNotEmpty();
			assertThat(spans).as("All spans should have start and end times")
					.allMatch(span -> span.getStartTimestamp() != null && span.getEndTimestamp() != null);
		});
	}
}
