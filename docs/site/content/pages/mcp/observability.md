Title: Observability
URL: mcp/observability.html
save_as: mcp/observability.html
template: mcp/observability

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
| OTLP gRPC | localhost:4317 | Trace/metric/log ingestion (gRPC) |
| OTLP HTTP | localhost:4318 | Trace/metric/log ingestion (HTTP) |

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

For production, reduce the sampling rate and configure the OTLP endpoint for your collector:

```bash
export OTEL_SAMPLING_PROBABILITY=0.1           # 10% sampling
export OTEL_TRACES_URL=https://otel-collector.example.com:4317
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```
