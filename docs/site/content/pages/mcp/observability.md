Title: Observability
URL: mcp/observability.html
save_as: mcp/observability.html
template: mcp/observability

## Overview ##

When running in **HTTP mode**, the Solr MCP Server exports telemetry data via OpenTelemetry to the **LGTM stack** (Loki, Grafana, Tempo, Mimir) for full observability.

| Signal | Backend | What it shows |
|--------|---------|---------------|
| **Traces** | Tempo | A trace per HTTP request, with a span for the MCP tool it invoked |
| **Metrics** | Mimir/Prometheus | HTTP server request count and duration |
| **Logs** | Loki | Application logs, each tagged with the trace and span it was written under |

Every MCP tool invocation creates a span named after the service and tool, such as
`search-service#search` or `collection-service#check-health`, inside the trace of the HTTP
request that carried it. The call to Solr is not a separate span; its time is part of the
tool span.

JVM, Tomcat and other Micrometer metrics are not exported over OTLP. They are served for
scraping at `/actuator/prometheus` (see **Actuator Endpoints** below).

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
| OTLP gRPC | localhost:4317 | Trace/metric/log ingestion (gRPC) |
| OTLP HTTP | localhost:4318 | Trace/metric/log ingestion (HTTP) |

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

The `grafana/otel-lgtm` container stores everything in memory with no persistent
volume, so a restart discards all traces, metrics, and logs.

### Run the Server with Observability ###

```bash
PROFILES=http ./gradlew bootRun
```

The server auto-configures OTLP export when the LGTM stack is running. Default configuration:

```properties
management.tracing.sampling.probability=1.0     # 100% sampling (dev)
otel.exporter.otlp.endpoint=http://localhost:4317
otel.exporter.otlp.protocol=grpc
```

`otel.exporter.otlp.endpoint` is the endpoint for all three signals, so `OTEL_TRACES_URL`
moves logs and metrics too, despite its name. When the server runs in a container and LGTM
on the host, `localhost` is the container itself; set
`OTEL_TRACES_URL=http://host.docker.internal:4317` (plus
`--add-host=host.docker.internal:host-gateway` on Linux).

### Generate Some Activity ###

Grafana has nothing to show until at least one tool call runs. Any MCP client works
(the [Quick Start](/mcp/quick-start.html) prompts are enough), or call the HTTP
endpoint directly. `HTTP_SECURITY_ENABLED=false` skips the OAuth2 setup for this local
check&mdash;see [Security](/mcp/security.html) to keep it on:

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
   chain, then one span for the tool (`search-service#search`,
   `collection-service#check-health`, &hellip;) with its duration

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

        # HTTP request rate, by method and status
        sum by (http_request_method, http_response_status_code) (rate(http_server_request_duration_seconds_count{job="solr-mcp"}[5m]))

        # Request latency (p99)
        histogram_quantile(0.99, sum by (le) (rate(http_server_request_duration_seconds_bucket{job="solr-mcp"}[5m])))

   These are the OpenTelemetry HTTP server metrics, the only application metrics exported
   over OTLP. Micrometer's names (`http_server_requests_seconds_*`, `jvm_*`) return nothing
   here; read them from `/actuator/prometheus` instead.

### Pivoting Between the Three ###

The fastest way through all three signals for one request:

1. Find the trace in **Tempo** (TraceQL query above) and open it.
2. Each span carries a **Logs for this span** link&mdash;click it to jump to the
   matching lines in **Loki**, filtered by trace ID. This works because the OTEL logback
   appender (`logback-spring.xml`) tags every log line with the active trace and span ID.
   The link filters on the trace, so it finds the same lines from any span in it.
3. The link comes up empty for a trace that logged nothing, which is every successful
   tool call: the services log only on failure. The `check-health` call above is the one
   to follow; its `WARN` sits under the `collection-service#check-health` span.
4. From a log line, the **Trace** link takes you back to its trace.
5. **Metrics** aren't per-request the same way&mdash;there's no single span/log &harr;
   metric-sample link&mdash;but the PromQL queries above will show the aggregate effect
   (e.g. a rise in `http_server_request_duration_seconds_count`) of whatever activity you
   just generated.

***

## Actuator Endpoints ##

The following health and metrics endpoints are exposed in HTTP mode:

```bash
curl http://localhost:8080/actuator/health       # Health check
curl http://localhost:8080/actuator/info          # Build info
curl http://localhost:8080/actuator/metrics       # Available metrics
curl http://localhost:8080/actuator/prometheus    # Prometheus scrape endpoint
curl http://localhost:8080/actuator/loggers       # Logger levels
```

***

## Troubleshooting ##

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| No traces/metrics/logs show up in Grafana at all | LGTM was never started&mdash;`bootRun` does not start it in either mode | `docker compose up -d lgtm` |
| Tempo finds nothing right after the calls | Traces take up to a minute to become searchable; metrics are exported once a minute | Wait and re-run the query |
| **Logs for this span** is empty | The request logged nothing; successful tool calls never do | Expected; follow a failing call such as `check-health` on a missing collection |
| `jvm_*` or `http_server_requests_seconds_*` returns nothing in Grafana | Those are Micrometer metrics, served only at `/actuator/prometheus` | Query `http_server_request_duration_seconds_*` in Grafana, or curl the actuator |
| Traces appear but stop after a restart | The `otel-lgtm` container has no persistent volume | Expected; re-run your workload after restarting `lgtm` |
| `otel.exporter.otlp.endpoint` connection refused | Running the server outside the `search` Docker network (e.g. inside its own container) while LGTM is on the host | Point `OTEL_TRACES_URL` at a reachable host, or join the same Docker network |
| Traces are sparse or missing under load | `management.tracing.sampling.probability` is below 1.0 | Raise it for the session you're debugging; keep it low in production |
| No data in **STDIO** mode | Tracing/metrics export is an HTTP-mode feature&mdash;STDIO has no servlet layer to instrument | Run `PROFILES=http ./gradlew bootRun` instead |

***

## Production Configuration ##

For production, reduce the sampling rate and configure the OTLP endpoint for your collector:

```bash
export OTEL_SAMPLING_PROBABILITY=0.1           # 10% sampling
export OTEL_TRACES_URL=https://otel-collector.example.com:4317
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```
