# Observability Guide for Solr MCP Server

This guide covers setting up observability (metrics, traces, and logs) for the Solr MCP Server running in HTTP mode using OpenTelemetry.

> **Looking for the short version?** [docs/observability.md](../docs/observability.md) is the
> user-facing guide: start the stack, run the server, read the dashboards, and the environment
> variables you need in production. This document is the developer companion — exporter
> architecture, the Logback OTLP appender wiring, and how the pieces fit together.

## Table of Contents

- [Overview](#overview)
- [The LGTM Stack](#the-lgtm-stack)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Accessing Telemetry Data](#accessing-telemetry-data)
  - [Grafana Dashboard](#grafana-dashboard)
  - [Viewing Traces](#viewing-traces)
  - [Viewing Logs](#viewing-logs)
  - [Viewing Metrics](#viewing-metrics)
- [Configuration](#configuration)
  - [Environment Variables](#environment-variables)
  - [Sampling Configuration](#sampling-configuration)
  - [Custom OTLP Endpoints](#custom-otlp-endpoints)
- [Production Considerations](#production-considerations)
- [Troubleshooting](#troubleshooting)

## Overview

The Solr MCP Server integrates with OpenTelemetry to provide comprehensive observability in HTTP mode:

| Signal | Description | Backend |
|--------|-------------|---------|
| **Traces** | Distributed tracing for request flows | Tempo |
| **Metrics** | Application and JVM metrics | Prometheus |
| **Logs** | Structured log export with trace correlation | Loki |

**Note:** Observability is only available in HTTP mode. STDIO mode disables telemetry to prevent stdout pollution that would interfere with MCP protocol communication.

## The LGTM Stack

The project uses the **Grafana LGTM stack** (`grafana/otel-lgtm`) - an all-in-one Docker image that provides a complete observability backend for local development. LGTM stands for:

| Component | Purpose | Port |
|-----------|---------|------|
| **L**oki | Log aggregation and querying | Internal |
| **G**rafana | Visualization, dashboards, and exploration | 3000 |
| **T**empo | Distributed tracing backend | Internal |
| **M**imir | Metrics storage — note the image actually ships **Prometheus**, which is what Grafana is wired to | Internal |

The image also includes an **OpenTelemetry Collector** that receives telemetry data via OTLP protocol:
- **Port 4317**: OTLP gRPC receiver
- **Port 4318**: OTLP HTTP receiver (used by Spring Boot)

This single container replaces what would otherwise require deploying and configuring multiple services separately, making it ideal for local development and testing.

## Quick Start

The `lgtm` service in `compose.yaml` carries `org.springframework.boot.ignore: "true"`, so
Spring Boot's Docker Compose support never starts or stops it. Start it yourself, then run
the server in HTTP mode:

```bash
docker compose up -d lgtm
PROFILES=http ./gradlew bootRun
```

In HTTP mode `bootRun` starts (and stops) the `solr` and `zoo` containers through the
`spring-boot-docker-compose` dependency and waits for them to be healthy. It does not wire
the OTLP endpoints: those come from the `localhost:4318` defaults in
`application-http.properties`, which match the ports `lgtm` publishes.

Once running, open Grafana at **http://localhost:3000** to explore your telemetry data.
`lgtm` keeps everything in memory, so `docker compose stop lgtm` discards it.

## Architecture

```
┌─────────────────────┐     OTLP/HTTP       ┌───────────────────────────────────┐
│  Solr MCP Server    │─────────────────────│   OpenTelemetry Collector         │
│  (HTTP mode)        │    :4318            │   (grafana/otel-lgtm)             │
│                     │                     │                                   │
│  ┌───────────────┐  │                     │  ┌────────────┐  ┌─────────────┐  │
│  │ Traces        │──┼─────────────────────┼─▶│ Tempo      │  │ Grafana     │  │
│  │ (auto-instr.) │  │                     │  └────────────┘  │ :3000       │  │
│  └───────────────┘  │                     │                  │             │  │
│  ┌───────────────┐  │                     │  ┌────────────┐  │ - Dashboards│  │
│  │ Metrics       │──┼─────────────────────┼─▶│ Prometheus │  │ - Explore   │  │
│  │ (Micrometer)  │  │                     │  └────────────┘  │ - Alerts    │  │
│  └───────────────┘  │                     │                  └─────────────┘  │
│  ┌───────────────┐  │                     │  ┌────────────┐                   │
│  │ Logs          │──┼─────────────────────┼─▶│ Loki       │                   │
│  │ (logback)     │  │                     │  └────────────┘                   │
│  └───────────────┘  │                     │                                   │
└─────────────────────┘                     └───────────────────────────────────┘
```

## Accessing Telemetry Data

### Grafana Dashboard

Access Grafana at **http://localhost:3000** (no login required in development
mode). Anonymous access is read-only and the UI is published on the loopback
interface only, so it is not reachable from other machines. To edit dashboards
anonymously, start the stack with `GF_ANON_ROLE=Admin docker compose up -d lgtm`.

The LGTM stack comes with pre-configured datasources:
- **Prometheus** - For metrics (the default datasource)
- **Tempo** - For distributed traces
- **Loki** - For logs
- **Pyroscope** - For continuous profiling

### Viewing Traces

Grafana's **Drilldown** feature provides an integrated view for exploring traces, metrics, and logs all in one place.

1. Open Grafana: http://localhost:3000
2. Go to **Drilldown** > **Traces** in the sidebar
3. Select **Tempo** as the datasource
4. Filter traces by:
   - Service name: `solr-mcp`
   - Span name (e.g., `http post /mcp`)
   - Duration
   - URL path

The trace view shows the complete request flow with a timing breakdown for each
span. A representative `/mcp` search request looks like this:
- The root span `http post /mcp` taking 223.98ms total
- Security filter chain spans for authentication/authorization
- The `SearchService#search` span (177.01ms) created by the `@Observed` annotation on the service method
- Nested security filter spans for the secured request

**Navigating Between Signals:**

The Drilldown sidebar provides quick access to related telemetry:
- **Metrics** - View application and JVM metrics (request rates, latencies, memory usage)
- **Logs** - View correlated logs with the same trace ID (empty for a request that logged
  nothing, which is every successful tool call; the services log only on failure)
- **Traces** - The current distributed trace view
- **Profiles** - CPU and memory profiling data (if configured)

This unified view makes it easy to investigate issues by correlating traces with their associated logs and metrics.

**Example TraceQL query:**
```
{resource.service.name="solr-mcp"}
```

The Solr call inside a tool is not a separate span; its time is part of the tool span.

### Viewing Logs

1. Open Grafana: http://localhost:3000
2. Go to **Explore**
3. Select **Loki** as the datasource
4. Query logs using LogQL:

**Example queries:**
```logql
# All logs from the MCP server
{service_name="solr-mcp"}

# Warnings and errors only
{service_name="solr-mcp"} | detected_level=~"warn|error"

# Only lines written during a request
{service_name="solr-mcp"} | trace_id != ""

# Logs with specific trace ID
{service_name="solr-mcp"} | trace_id="<your-trace-id>"
```

The level, `trace_id` and `span_id` are record attributes, not part of the message text, so
filter them with `| name=value`; `|= "ERROR"` matches nothing, and the records are not JSON,
so a `| json` stage only adds a parse error.

### Viewing Metrics

1. Open Grafana: http://localhost:3000
2. Go to **Explore**
3. Select **Prometheus** as the datasource
4. Query metrics using PromQL:

**Example queries:**
```promql
# MCP request rate, by method and status
sum by (method, status) (rate(http_server_requests_milliseconds_count{job="solr-mcp", uri="/mcp"}[5m]))

# Average latency per tool (ms), from the @Observed service methods
sum by (class, method) (rate(method_observed_milliseconds_sum{job="solr-mcp"}[5m]))
  / sum by (class, method) (rate(method_observed_milliseconds_count{job="solr-mcp"}[5m]))

# JVM memory usage
sum by (area) (jvm_memory_used_bytes{job="solr-mcp"})

# Live threads
jvm_threads_live{job="solr-mcp"}
```

The OTLP registry exports timers in milliseconds (`_milliseconds_*`, not `_seconds_*`) and
labels series with `job="solr-mcp"` / `service_name="solr-mcp"`; there is no `application`
label. Percentiles need histogram buckets, which timers publish only when enabled, e.g.
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`; the p99
query is in [docs/observability.md](../docs/observability.md).

## Configuration

### Environment Variables

For production deployments without Docker Compose, set these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | Trace sampling rate (0.0-1.0) |
| `OTEL_METRICS_URL` | `http://localhost:4318/v1/metrics` | OTLP/HTTP metrics endpoint |
| `OTEL_TRACES_URL` | `http://localhost:4318/v1/traces` | OTLP/HTTP traces endpoint |
| `OTEL_LOGS_URL` | `http://localhost:4318/v1/logs` | OTLP/HTTP logs endpoint |

Each URL is a complete signal path, not a base address. `OTEL_TRACES_URL` previously meant a
base endpoint on the gRPC port (`http://collector:4317`) and shared that endpoint with metrics
and logs — a value carried over from before this change stops exporting silently rather than
failing loudly.

Example production configuration:
```bash
export OTEL_SAMPLING_PROBABILITY=0.1
export OTEL_METRICS_URL=https://otel-collector.prod.example.com/v1/metrics
export OTEL_TRACES_URL=https://otel-collector.prod.example.com/v1/traces
export OTEL_LOGS_URL=https://otel-collector.prod.example.com/v1/logs
```

### Sampling Configuration

For production, reduce sampling to manage costs and storage:

```bash
# Sample 10% of traces
export OTEL_SAMPLING_PROBABILITY=0.1
```

Or in `application-http.properties`:
```properties
management.tracing.sampling.probability=0.1
```

### Custom OTLP Endpoints

To send telemetry to a different backend (e.g., Jaeger, Datadog, New Relic):

```bash
# Example: Send traces to Jaeger
export OTEL_TRACES_URL=http://jaeger:4318/v1/traces

# Example: Send metrics to Prometheus remote write endpoint
export OTEL_METRICS_URL=http://prometheus:9090/api/v1/otlp/v1/metrics
```

## Production Considerations

### 1. Use Secure Endpoints

```properties
# Use HTTPS for production OTLP endpoints
management.otlp.metrics.export.url=https://otel-collector.prod.example.com/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=https://otel-collector.prod.example.com/v1/traces
management.opentelemetry.logging.export.otlp.endpoint=https://otel-collector.prod.example.com/v1/logs
```

### 2. Add Authentication Headers

If your OTLP collector requires authentication, configure headers in your OpenTelemetry configuration.

### 3. Resource Attributes

Add deployment-specific attributes for better filtering:

```properties
spring.application.name=solr-mcp-prod
```

## Troubleshooting

### No Data in Grafana

1. **Check the LGTM container is running:**
   ```bash
   docker compose ps lgtm
   ```

2. **Verify OTLP endpoints are reachable:**
   ```bash
   curl -v http://localhost:4318/v1/traces
   ```

3. **Check application logs for OTLP errors:**
   ```bash
   ./gradlew bootRun 2>&1 | grep -i otel
   ```

### Traces Not Appearing

1. Ensure you're running in HTTP mode (`PROFILES=http`)
2. Check sampling probability is > 0
3. Verify the trace endpoint URL is correct

### Logs Not Appearing

1. Check that logback-spring.xml is being loaded
2. Verify the OTEL appender is installed (check startup logs)
3. Ensure log level is INFO or lower

### Metrics Not Appearing

1. Verify actuator endpoints are exposed:
   ```bash
   curl http://localhost:8080/actuator/metrics
   ```
2. Check the metrics endpoint URL is correct

### High Memory Usage

If the LGTM container uses too much memory:
```yaml
# compose.yaml
lgtm:
  image: grafana/otel-lgtm:0.30.0
  deploy:
    resources:
      limits:
        memory: 2G
```

## References

- [Spring Boot OpenTelemetry](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [Grafana LGTM Stack](https://grafana.com/blog/2024/03/13/an-opentelemetry-backend-in-a-docker-image-introducing-grafana/otel-lgtm/)
- [LogQL Query Language](https://grafana.com/docs/loki/latest/logql/)
- [TraceQL Query Language](https://grafana.com/docs/tempo/latest/traceql/)
