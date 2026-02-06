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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.grafana.LgtmStackContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Helper class to query LGTM stack backends (Tempo, Prometheus, Loki).
 *
 * <p>
 * Provides convenient methods for verifying traces, metrics, and logs in
 * integration tests using Spring's {@link RestClient}.
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
 *
 * // Query logs
 * Optional&lt;JsonNode&gt; logs = lgtm.queryLoki("{service_name=\"my-service\"}", 10);
 * </pre>
 */
public class LgtmAssertions {

    private static final Logger log = LoggerFactory.getLogger(LgtmAssertions.class);

    private final LgtmStackContainer lgtm;

    private final ObjectMapper objectMapper;

    private final RestClient restClient;

    public LgtmAssertions(LgtmStackContainer lgtm, ObjectMapper objectMapper) {
        this.lgtm = lgtm;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
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

    public String getLokiUrl() {
        return lgtm.getLokiUrl();
    }

    /**
     * Fetch a trace by ID from Tempo.
     *
     * @param traceId the trace ID to fetch
     * @return Optional containing the trace JSON if found
     */
    public Optional<JsonNode> getTraceById(String traceId) {
        try {
            String url = getTempoUrl() + "/api/traces/" + traceId;
            String response = restClient.get().uri(url).retrieve().body(String.class);

            if (response != null) {
                return Optional.of(objectMapper.readTree(response));
            }
        } catch (Exception e) {
            log.debug("Trace not found: {}", traceId);
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
            String encodedQuery = URLEncoder.encode(traceQlQuery, StandardCharsets.UTF_8);
            String url = getTempoUrl() + "/api/search?q=" + encodedQuery + "&limit=" + limit;
            String response = restClient.get().uri(url).retrieve().body(String.class);

            if (response != null) {
                return Optional.of(objectMapper.readTree(response));
            }
        } catch (Exception e) {
            log.warn("Error searching traces: {}", e.getMessage());
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
            String encodedQuery = URLEncoder.encode(promQlQuery, StandardCharsets.UTF_8);
            String url = getPrometheusUrl() + "/api/v1/query?query=" + encodedQuery;
            String response = restClient.get().uri(url).retrieve().body(String.class);

            if (response != null) {
                JsonNode result = objectMapper.readTree(response);
                JsonNode status = result.get("status");
                if (status != null && "success".equals(status.asText())) {
                    return Optional.of(result.get("data"));
                }
            }
        } catch (Exception e) {
            log.warn("Error querying Prometheus: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Query Loki logs using LogQL.
     *
     * @param logQlQuery the LogQL query string
     * @param limit      maximum number of log entries to return
     * @return Optional containing the query result data if successful
     */
    public Optional<JsonNode> queryLoki(String logQlQuery, int limit) {
        try {
            String encodedQuery = URLEncoder.encode(logQlQuery, StandardCharsets.UTF_8);
            // Use instant query (simpler than query_range which requires time bounds)
            String url = getLokiUrl() + "/loki/api/v1/query?query=" + encodedQuery + "&limit=" + limit;
            String response = restClient.get().uri(url).retrieve().body(String.class);

            if (response != null) {
                JsonNode result = objectMapper.readTree(response);
                JsonNode status = result.get("status");
                if (status != null && "success".equals(status.asText())) {
                    return Optional.of(result.get("data"));
                }
            }
        } catch (Exception e) {
            log.warn("Error querying Loki: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Check if Loki API is accessible and responding.
     *
     * @return true if Loki is ready
     */
    public boolean isLokiReady() {
        try {
            String url = getLokiUrl() + "/ready";
            String response = restClient.get().uri(url).retrieve().body(String.class);
            return response != null && response.contains("ready");
        } catch (Exception e) {
            log.debug("Loki not ready: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if Prometheus has any metrics from the service.
     *
     * @param serviceName the service name to check for
     * @return true if metrics exist for the service
     */
    public boolean hasMetricsForService(String serviceName) {
        Optional<JsonNode> result = queryPrometheus("{service_name=\"" + serviceName + "\"}");
        if (result.isPresent()) {
            JsonNode data = result.get();
            JsonNode resultArray = data.get("result");
            return resultArray != null && !resultArray.isEmpty();
        }
        return false;
    }

    /**
     * Check if Loki has any logs from the service.
     *
     * @param serviceName the service name to check for
     * @return true if logs exist for the service
     */
    public boolean hasLogsForService(String serviceName) {
        Optional<JsonNode> result = queryLoki("{service_name=\"" + serviceName + "\"}", 1);
        if (result.isPresent()) {
            JsonNode data = result.get();
            JsonNode resultArray = data.get("result");
            return resultArray != null && !resultArray.isEmpty();
        }
        return false;
    }

}
