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

import org.testcontainers.grafana.LgtmStackContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Helper class to query LGTM stack backends (Tempo, Prometheus, Loki).
 *
 * <p>
 * Provides convenient methods for verifying traces and metrics in integration
 * tests.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * LgtmAssertions lgtm = new LgtmAssertions(lgtmContainer, objectMapper);
 *
 * // Search for traces
 * Optional&lt;JsonNode&gt; traces = lgtm.searchTraces("{.service.name=\"my-service\"}", 10);
 *
 * // Query metrics
 * Optional&lt;JsonNode&gt; metrics = lgtm.queryPrometheus("http_server_requests_seconds_count");
 * </pre>
 */
public class LgtmAssertions {

    private final LgtmStackContainer lgtm;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public LgtmAssertions(LgtmStackContainer lgtm, ObjectMapper objectMapper) {
        this.lgtm = lgtm;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public String getTempoUrl() {
        return "http://" + lgtm.getHost() + ":" + lgtm.getMappedPort(3200);
    }

    public String getPrometheusUrl() {
        return "http://" + lgtm.getHost() + ":" + lgtm.getMappedPort(9090);
    }

    public String getGrafanaUrl() {
        return lgtm.getGrafanaHttpUrl();
    }

    /**
     * Fetch a trace by ID from Tempo.
     *
     * @param traceId the trace ID to fetch
     * @return Optional containing the trace JSON if found
     */
    public Optional<JsonNode> getTraceById(String traceId) {
        try {
            URI uri = URI.create(getTempoUrl() + "/api/traces/" + traceId);
            HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            // Trace not found yet
        }
        return Optional.empty();
    }

    /**
     * Search traces using TraceQL.
     *
     * @param traceQlQuery the TraceQL query string
     * @param limit        maximum number of traces to return
     * @return Optional containing the search results JSON if successful
     */
    public Optional<JsonNode> searchTraces(String traceQlQuery, int limit) {
        try {
            // URL-encode the query parameter to handle special characters like {}
            String encodedQuery = java.net.URLEncoder.encode(traceQlQuery, java.nio.charset.StandardCharsets.UTF_8);
            String url = getTempoUrl() + "/api/search?q=" + encodedQuery + "&limit=" + limit;
            URI uri = URI.create(url);

            HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            System.err.println("Error searching traces: " + e.getMessage());
            e.printStackTrace();
        }
        return Optional.empty();
    }

    /**
     * Query Prometheus metrics using PromQL.
     *
     * @param promQlQuery the PromQL query string
     * @return Optional containing the query result data if successful
     */
    public Optional<JsonNode> queryPrometheus(String promQlQuery) {
        try {
            String url = getPrometheusUrl() + "/api/v1/query?query=" + promQlQuery;
            URI uri = URI.create(url);

            HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                JsonNode result = objectMapper.readTree(response.body());
                if ("success".equals(result.get("status").asText())) {
                    return Optional.of(result.get("data"));
                }
            }
        } catch (Exception e) {
            // Query failed
        }
        return Optional.empty();
    }

}
