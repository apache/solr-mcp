# Observability

## Overview ##

When running in **HTTP mode**, the Solr MCP Server exports telemetry data via OpenTelemetry to the **LGTM stack** (Loki, Grafana, Tempo, Mimir) for full observability.

| Signal | Backend | What it shows |
|--------|---------|---------------|
| **Traces** | Tempo | Distributed traces for every MCP tool invocation, Solr query, and HTTP request |
| **Metrics** | Mimir/Prometheus | JVM stats, HTTP request rates, Solr query latencies, cache hit ratios |
| **Logs** | Loki | Structured application logs correlated with trace IDs |

Every MCP tool invocation creates a trace span: search, indexing (JSON, CSV, XML), collection operations (list, stats, health, create), and schema retrieval. All incoming HTTP requests and outgoing Solr calls are automatically traced.

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

***

## Grafana ##

Open [http://localhost:3000](http://localhost:3000) and click **Explore** in the left sidebar.

### View Traces (Tempo) ###

1. Select **Tempo** as the data source
2. Use TraceQL to search:

        {.service.name="solr-mcp"}

3. Click on a trace to see the span waterfall&mdash;each MCP tool invocation, Solr query, and HTTP request is a separate span

### View Logs (Loki) ###

1. Select **Loki** as the data source
2. Use LogQL to search:

        {service_name="solr-mcp"} |= "search"

3. Logs are automatically correlated with trace IDs&mdash;click a log line to jump to its trace

### View Metrics (Prometheus) ###

1. Select **Prometheus** as the data source
2. Example queries:

        # HTTP request rate
        rate(http_server_requests_seconds_count[5m])

        # JVM memory usage
        jvm_memory_used_bytes

        # Request latency (p99)
        histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))

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
