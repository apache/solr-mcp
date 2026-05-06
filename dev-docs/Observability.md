# Observability Guide for Solr MCP Server

This guide covers setting up observability (metrics, traces, and logs) for the Solr MCP Server running in HTTP mode using OpenTelemetry.

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
| **Metrics** | Application and JVM metrics | Mimir (Prometheus-compatible) |
| **Logs** | Structured log export with trace correlation | Loki |

**Note:** Observability is only available in HTTP mode. STDIO mode disables telemetry to prevent stdout pollution that would interfere with MCP protocol communication.

## The LGTM Stack

The project uses the **Grafana LGTM stack** (`grafana/otel-lgtm`) - an all-in-one Docker image that provides a complete observability backend for local development. LGTM stands for:

| Component | Purpose | Port |
|-----------|---------|------|
| **L**oki | Log aggregation and querying | Internal |
| **G**rafana | Visualization, dashboards, and exploration | 3000 |
| **T**empo | Distributed tracing backend | Internal |
| **M**imir | Prometheus-compatible metrics storage | Internal |

The image also includes an **OpenTelemetry Collector** that receives telemetry data via OTLP protocol:
- **Port 4317**: OTLP gRPC receiver
- **Port 4318**: OTLP HTTP receiver (used by Spring Boot)

This single container replaces what would otherwise require deploying and configuring multiple services separately, making it ideal for local development and testing.

## Quick Start

Thanks to the `spring-boot-docker-compose` dependency, **Docker containers are automatically started** when you run the application locally. Simply run:

```bash
# Run the MCP server in HTTP mode - Docker containers start automatically!
PROFILES=http ./gradlew bootRun
```

Spring Boot detects the `compose.yaml` file and automatically:
1. Starts the `lgtm` container (Grafana, Loki, Tempo, Mimir)
2. Starts the `solr` and `zoo` containers
3. Configures OTLP endpoints to point to the running containers
4. Waits for containers to be healthy before accepting requests

Once running, open Grafana at **http://localhost:3000** to explore your telemetry data.

**Note:** To start containers manually (e.g., for debugging), use:
```bash
docker compose up -d lgtm solr
```

## Architecture

```
┌─────────────────────┐     OTLP/HTTP      ┌─────────────────────────────────┐
│  Solr MCP Server    │─────────────────────│   OpenTelemetry Collector      │
│  (HTTP mode)        │    :4318            │   (grafana/otel-lgtm)          │
│                     │                     │                                 │
│  ┌───────────────┐  │                     │  ┌─────────┐  ┌─────────────┐  │
│  │ Traces        │──┼─────────────────────┼─▶│ Tempo   │  │ Grafana     │  │
│  │ (auto-instr.) │  │                     │  └─────────┘  │ :3000       │  │
│  └───────────────┘  │                     │               │             │  │
│  ┌───────────────┐  │                     │  ┌─────────┐  │ - Dashboards│  │
│  │ Metrics       │──┼─────────────────────┼─▶│ Mimir   │  │ - Explore   │  │
│  │ (actuator)    │  │                     │  └─────────┘  │ - Alerts    │  │
│  └───────────────┘  │                     │               └─────────────┘  │
│  ┌───────────────┐  │                     │  ┌─────────┐                   │
│  │ Logs          │──┼─────────────────────┼─▶│ Loki    │                   │
│  │ (logback)     │  │                     │  └─────────┘                   │
│  └───────────────┘  │                     │                                 │
└─────────────────────┘                     └─────────────────────────────────┘
```

## Accessing Telemetry Data

### Grafana Dashboard

Access Grafana at **http://localhost:3000** (no login required in development mode).

The LGTM stack comes with pre-configured datasources:
- **Tempo** - For distributed traces
- **Loki** - For logs
- **Mimir** - For metrics (Prometheus-compatible)

### Viewing Traces

Grafana's **Drilldown** feature provides an integrated view for exploring traces, metrics, and logs all in one place.

1. Open Grafana: http://localhost:3000
2. Go to **Drilldown** > **Traces** in the sidebar
3. Select **Tempo** as the datasource
4. Filter traces by:
   - Service name: `solr-mcp-server`
   - Span name (e.g., `http post /mcp`)
   - Duration
   - URL path

The trace view shows the complete request flow with timing breakdown for each span:

![Distributed Tracing in Grafana](images/grafana-traces.png)

In this example, you can see:
- The root span `http post /mcp` taking 223.98ms total
- Security filter chain spans for authentication/authorization
- The `SearchService#search` span (177.01ms) created by the `@Observed` annotation on the service method
- Nested security filter spans for the secured request

**Navigating Between Signals:**

The Drilldown sidebar provides quick access to related telemetry:
- **Metrics** - View application and JVM metrics (request rates, latencies, memory usage)
- **Logs** - View correlated logs with the same trace ID
- **Traces** - The current distributed trace view
- **Profiles** - CPU and memory profiling data (if configured)

This unified view makes it easy to investigate issues by correlating traces with their associated logs and metrics.

**Example TraceQL query:**
```
{resource.service.name="solr-mcp-server"}
```

### Viewing Logs

1. Open Grafana: http://localhost:3000
2. Go to **Explore**
3. Select **Loki** as the datasource
4. Query logs using LogQL:

**Example queries:**
```logql
# All logs from the MCP server
{service_name="solr-mcp-server"}

# Error logs only
{service_name="solr-mcp-server"} |= "ERROR"

# Logs with specific trace ID
{service_name="solr-mcp-server"} | json | trace_id="<your-trace-id>"
```

### Viewing Metrics

1. Open Grafana: http://localhost:3000
2. Go to **Explore**
3. Select **Mimir** as the datasource
4. Query metrics using PromQL:

**Example queries:**
```promql
# HTTP request rate
rate(http_server_requests_seconds_count{application="solr-mcp-server"}[5m])

# Request latency (p99)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{application="solr-mcp-server"}[5m]))

# JVM memory usage
jvm_memory_used_bytes{application="solr-mcp-server"}

# Active threads
jvm_threads_live_threads{application="solr-mcp-server"}
```

## Configuration

### Environment Variables

For production deployments without Docker Compose, set these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | Trace sampling rate (0.0-1.0) |
| `OTEL_METRICS_URL` | (auto-configured) | OTLP metrics endpoint |
| `OTEL_TRACES_URL` | (auto-configured) | OTLP traces endpoint |
| `OTEL_LOGS_URL` | (auto-configured) | OTLP logs endpoint |

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
spring.application.name=solr-mcp-server-prod
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
  image: grafana/otel-lgtm:latest
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
