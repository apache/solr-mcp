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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Helper utilities for asserting on distributed traces in tests.
 *
 * <p>
 * Provides fluent assertions for verifying OpenTelemetry span properties.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * List&lt;SpanData&gt; spans = spanExporter.getFinishedSpanItems();
 *
 * TraceAssertions.assertSpanExists(spans, "SearchService.search");
 * TraceAssertions.assertSpanHasAttribute(spans, "SearchService.search", "collection", "test");
 * TraceAssertions.assertSpanCount(spans, 3);
 * </pre>
 */
public class TraceAssertions {

    private TraceAssertions() {
        // Utility class
    }

    /**
     * Assert that at least one span with the given name exists.
     *
     * @param spans    the list of captured spans
     * @param spanName the expected span name (can be a partial match)
     */
    public static void assertSpanExists(List<SpanData> spans, String spanName) {
        assertThat(spans).as("Expected to find span with name containing: %s", spanName)
                .anyMatch(span -> span.getName().contains(spanName));
    }

    /**
     * Assert that a span with the given name has a specific attribute value.
     *
     * @param spans         the list of captured spans
     * @param spanName      the span name to search for
     * @param attributeKey  the attribute key
     * @param expectedValue the expected attribute value
     */
    public static void assertSpanHasAttribute(List<SpanData> spans, String spanName, String attributeKey,
                                              String expectedValue) {
        assertThat(spans).as("Expected span '%s' to have attribute %s=%s", spanName, attributeKey, expectedValue)
                .anyMatch(span -> span.getName().contains(spanName)
                        && expectedValue.equals(span.getAttributes().get(AttributeKey.stringKey(attributeKey))));
    }

    /**
     * Assert that the total number of spans matches the expected count.
     *
     * @param spans         the list of captured spans
     * @param expectedCount the expected number of spans
     */
    public static void assertSpanCount(List<SpanData> spans, int expectedCount) {
        assertThat(spans).as("Expected exactly %d spans", expectedCount).hasSize(expectedCount);
    }

    /**
     * Assert that a span with the given name has the specified span kind.
     *
     * @param spans        the list of captured spans
     * @param spanName     the span name to search for
     * @param expectedKind the expected span kind
     */
    public static void assertSpanKind(List<SpanData> spans, String spanName, SpanKind expectedKind) {
        assertThat(spans).as("Expected span '%s' to have kind %s", spanName, expectedKind)
                .anyMatch(span -> span.getName().contains(spanName) && span.getKind() == expectedKind);
    }

    /**
     * Assert that a span exists matching the given predicate.
     *
     * @param spans       the list of captured spans
     * @param description description of what is being tested
     * @param predicate   the condition to match
     */
    public static void assertSpanMatches(List<SpanData> spans, String description, Predicate<SpanData> predicate) {
        assertThat(spans).as(description).anyMatch(predicate);
    }

    /**
     * Assert that at least one span has a parent (i.e., is part of a trace).
     *
     * @param spans the list of captured spans
     */
    public static void assertSpansHaveParentChild(List<SpanData> spans) {
        long spansWithParent = spans.stream()
                .filter(span -> span.getParentSpanId() != null && !span.getParentSpanId().equals("0000000000000000"))
                .count();

        assertThat(spansWithParent).as("Expected at least one span to have a parent").isGreaterThan(0);
    }

    /**
     * Assert that all spans have valid timestamps (end time > start time).
     *
     * @param spans the list of captured spans
     */
    public static void assertValidTimestamps(List<SpanData> spans) {
        assertThat(spans).as("All spans should have valid timestamps (end > start)").allMatch(span -> {
            long startTime = span.getStartEpochNanos();
            long endTime = span.getEndEpochNanos();
            return startTime > 0 && endTime > startTime;
        });
    }

    /**
     * Assert that all spans include a service name in their resource attributes.
     *
     * @param spans the list of captured spans
     */
    public static void assertServiceNamePresent(List<SpanData> spans) {
        assertThat(spans).as("All spans should have a service name").allMatch(span -> {
            String serviceName = span.getResource()
                    .getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.name"));
            return serviceName != null && !serviceName.isEmpty();
        });
    }

    /**
     * Find the first span matching the given name.
     *
     * @param spans    the list of captured spans
     * @param spanName the span name to search for
     * @return the first matching span, or null if not found
     */
    public static SpanData findSpan(List<SpanData> spans, String spanName) {
        return spans.stream().filter(span -> span.getName().contains(spanName)).findFirst().orElse(null);
    }

    /**
     * Get all spans with the given name.
     *
     * @param spans    the list of captured spans
     * @param spanName the span name to search for
     * @return list of matching spans
     */
    public static List<SpanData> findSpans(List<SpanData> spans, String spanName) {
        return spans.stream().filter(span -> span.getName().contains(spanName)).toList();
    }

}
