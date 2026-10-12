# Observability

## Overview ##

When running in **HTTP mode**, the Solr MCP Server exports telemetry data via OpenTelemetry to the Grafana **LGTM stack** (Loki, Grafana, Tempo, and Prometheus for metrics) for full observability.

| Signal | Backend | What it shows |
|--------|---------|---------------|
| **Traces** | Tempo | A trace per HTTP request, with a span for the MCP tool it invoked |
| **Metrics** | Prometheus | HTTP request rate and latency, per-tool latency, JVM, Tomcat and Spring Security metrics |
| **Logs** | Loki | Application logs, each tagged with the trace and span it was written under |

Every MCP tool invocation creates a span named after the service class and method, such as
`SearchService#search` or `CollectionService#checkHealth`, inside the trace of the HTTP
request that carried it. The call to Solr is not a separate span; its time is part of the
tool span.

All Micrometer metrics, JVM and Tomcat included, are exported over OTLP. There is no
`/actuator/prometheus` scrape endpoint.

***

## Setup ##

### Start the LGTM Stack ###

The project's `compose.yaml` includes a Grafana OTEL LGTM all-in-one container:

```bash
docker compose up -d
```

This starts:

| Service | URL | Purpose |
|---------|-----|---------|
| Grafana | http://localhost:3000 | Dashboards and exploration (no auth required) |
| OTLP HTTP | localhost:4318 | Trace/metric/log ingestion — **the port this server exports to** |
| OTLP gRPC | localhost:4317 | Also accepted by the collector; not used by this server |

**LGTM is never auto-started.** The `lgtm` service carries
`org.springframework.boot.ignore: "true"` in `compose.yaml`, which opts it out of Spring
Boot's Docker Compose lifecycle management. This is independent of transport mode: even
in HTTP mode, where `PROFILES=http ./gradlew bootRun` auto-starts (and stops) the `solr`
and `zoo` services, LGTM is untouched. Start it explicitly, either with the
`docker compose up -d` above (which starts everything) or, if `solr`/`zoo` are already
running via `bootRun`'s auto-start, on its own:

```bash
docker compose up -d lgtm
```

Because it's Boot-ignored, `bootRun` won't stop it either&mdash;bring it down by hand
when you're done:

```bash
docker compose stop lgtm
```

The `grafana/otel-lgtm` container stores everything in memory with no persistent volume,
so a restart discards all traces, metrics, and logs.

### Run the Server with Observability ###

```bash
PROFILES=http ./gradlew bootRun
```

The server auto-configures OTLP export when the LGTM stack is running. Default configuration:

```properties
management.tracing.sampling.probability=1.0     # 100% sampling (dev)
management.opentelemetry.tracing.export.otlp.endpoint=${OTEL_TRACES_URL:http://localhost:4318/v1/traces}
management.otlp.metrics.export.url=${OTEL_METRICS_URL:http://localhost:4318/v1/metrics}
management.opentelemetry.logging.export.otlp.endpoint=${OTEL_LOGS_URL:http://localhost:4318/v1/logs}
```

Export goes over **OTLP/HTTP on port 4318**, with a separate full URL per signal.
Each endpoint is a complete path ending in `/v1/traces`, `/v1/metrics` or
`/v1/logs` — not a base address.
When the server runs in a container and LGTM on the host, `localhost` is the container
itself; point all three variables at the host, e.g.
`OTEL_TRACES_URL=http://host.docker.internal:4318/v1/traces` (plus
`--add-host=host.docker.internal:host-gateway` on Linux).

### Generate Some Activity ###

Grafana has nothing to show until at least one tool call runs. Any MCP client works
(the [Quick Start](tutorial.md) prompts are enough), or call the HTTP endpoint directly.
`HTTP_SECURITY_ENABLED=false` skips the OAuth2 setup for this local check&mdash;see
[Security](security/http.md) to keep it on:

```bash
HTTP_SECURITY_ENABLED=false PROFILES=http ./gradlew bootRun
```

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"list-collections","arguments":{}}}'
```

Run it a few times&mdash;each call is one trace and one more request in the metrics.

A successful call writes no log lines, so it has nothing to show in Loki. To see a trace
with a log attached, make one call that fails; a health check on a collection that does not
exist logs a `WARN` inside the request:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"check-health","arguments":{"collection":"no-such-collection"}}}'
```

Traces take up to a minute to become searchable in Tempo, and metrics are exported once
a minute, so an empty Grafana right after the calls is expected.

***

## Grafana ##

Open [http://localhost:3000](http://localhost:3000) and click **Explore** in the left sidebar.

### View Traces (Tempo) ###

1. Select **Tempo** as the data source
2. Use TraceQL to search:

        {.service.name="solr-mcp"}

3. Click on an `http post /mcp` trace to see the span waterfall: the security filter
   chain, then one span for the tool (`SearchService#search`,
   `CollectionService#checkHealth`, &hellip;) with its duration

### View Logs (Loki) ###

1. Select **Loki** as the data source
2. Use LogQL to search:

        {service_name="solr-mcp"} | trace_id != ""

   That keeps only lines written during a request; drop the filter to include startup
   and lifecycle lines, which have no trace.
3. Expand a line and follow its **Trace** link to open the request in Tempo.

### View Metrics (Prometheus) ###

1. Select **Prometheus** as the data source
2. Example queries:

        # MCP request rate, by method and status
        sum by (method, status) (rate(http_server_requests_milliseconds_count{job="solr-mcp", uri="/mcp"}[5m]))

        # Average MCP request latency (ms)
        sum(rate(http_server_requests_milliseconds_sum{job="solr-mcp", uri="/mcp"}[5m]))
          / sum(rate(http_server_requests_milliseconds_count{job="solr-mcp", uri="/mcp"}[5m]))

        # Average latency per tool (ms)
        sum by (class, method) (rate(method_observed_milliseconds_sum{job="solr-mcp"}[5m]))
          / sum by (class, method) (rate(method_observed_milliseconds_count{job="solr-mcp"}[5m]))

        # JVM memory, heap vs non-heap
        sum by (area) (jvm_memory_used_bytes{job="solr-mcp"})

   Timers are exported over OTLP in **milliseconds**, so their names end in
   `_milliseconds_*`; queries written for `http_server_requests_seconds_*` return nothing.
   Timers publish only the `+Inf` bucket by default, so percentiles return `NaN` unless
   `management.metrics.distribution.percentiles-histogram.http.server.requests=true` is set.

### Pivoting Between the Three ###

The fastest way through all three signals for one request:

1. Find the trace in **Tempo** (TraceQL query above) and open it.
2. Each span carries a **Logs for this span** link&mdash;click it to jump to the
   matching lines in **Loki**, filtered by trace ID. This works because the OTEL logback
   appender (`logback-spring.xml`) tags every log line with the active trace and span ID.
   The link filters on the trace, so it finds the same lines from any span in it.
3. The link comes up empty for a trace that logged nothing, which is every successful
   tool call: the services log only on failure. The `check-health` call above is the one
   to follow; its `WARN` sits under the `CollectionService#checkHealth` span.
4. From a log line, the **Trace** link takes you back to its trace.
5. **Metrics** aren't per-request the same way&mdash;there's no single span/log &harr;
   metric-sample link&mdash;but the PromQL queries above will show the aggregate effect
   (e.g. a rise in `http_server_requests_milliseconds_count`) of whatever activity you
   just generated.

***

## Actuator Endpoints ##

The following health and metrics endpoints are exposed in HTTP mode:

```bash
curl http://localhost:8080/actuator/health       # Health check
curl http://localhost:8080/actuator/info          # Build info
curl http://localhost:8080/actuator/metrics       # Available metrics
curl http://localhost:8080/actuator/loggers       # Logger levels
```

***

## Troubleshooting ##

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| No traces/metrics/logs show up in Grafana at all | LGTM was never started&mdash;`bootRun` does not start it in either mode | `docker compose up -d lgtm` |
| Tempo finds nothing right after the calls | Traces take up to a minute to become searchable; metrics are exported once a minute | Wait and re-run the query |
| **Logs for this span** is empty | The request logged nothing; successful tool calls never do | Expected; follow a failing call such as `check-health` on a missing collection |
| `http_server_requests_seconds_*` returns nothing in Grafana | Timers are exported over OTLP in milliseconds | Query `http_server_requests_milliseconds_*` |
| Traces appear but stop after a restart | The `otel-lgtm` container has no persistent volume | Expected; re-run your workload after restarting `lgtm` |
| OTLP export connection refused | Running the server outside the `search` Docker network (e.g. inside its own container) while LGTM is on the host | Point `OTEL_TRACES_URL`, `OTEL_METRICS_URL` and `OTEL_LOGS_URL` at a reachable host, or join the same Docker network |
| Traces are sparse or missing under load | `management.tracing.sampling.probability` is below 1.0 | Raise it for the session you're debugging; keep it low in production |
| No data in **STDIO** mode | Tracing/metrics export is an HTTP-mode feature&mdash;STDIO has no servlet layer to instrument | Run `PROFILES=http ./gradlew bootRun` instead |

***

## Production Configuration ##

For production, reduce the sampling rate and point each signal at your collector:

```bash
export OTEL_SAMPLING_PROBABILITY=0.1                                          # 10% sampling
export OTEL_TRACES_URL=https://otel-collector.example.com/v1/traces
export OTEL_METRICS_URL=https://otel-collector.example.com/v1/metrics
export OTEL_LOGS_URL=https://otel-collector.example.com/v1/logs
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```

| Variable | Default | Purpose |
|----------|---------|---------|
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | Fraction of traces sampled |
| `OTEL_TRACES_URL` | `http://localhost:4318/v1/traces` | OTLP/HTTP traces endpoint |
| `OTEL_METRICS_URL` | `http://localhost:4318/v1/metrics` | OTLP/HTTP metrics endpoint |
| `OTEL_LOGS_URL` | `http://localhost:4318/v1/logs` | OTLP/HTTP logs endpoint |

> **Upgrading from a pre-Spring-Boot-4 release?** `OTEL_TRACES_URL` changed meaning.
> It used to be a *base* endpoint on the gRPC port (`http://collector:4317`); it is now
> the *complete* traces URL on the HTTP port (`http://collector:4318/v1/traces`). A value
> carried over unchanged will not error — traces simply stop arriving. `OTEL_METRICS_URL`
> and `OTEL_LOGS_URL` are new; previously all three signals shared one endpoint.

For the exporter architecture and how the Logback OTLP appender is wired, see
[dev-docs/Observability.md](../dev-docs/Observability.md).
