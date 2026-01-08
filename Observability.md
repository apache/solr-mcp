# Observability Guide for Solr MCP Server

This guide covers setting up observability (metrics, traces, and logs) for the Solr MCP Server running in HTTP mode using OpenTelemetry.

## Table of Contents

- [Overview](#overview)
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

## Quick Start

```bash
# 1. Start the observability stack
docker compose up -d lgtm

# 2. Start Solr (if needed for testing)
docker compose up -d solr

# 3. Run the MCP server in HTTP mode
export PROFILES=http
./gradlew bootRun

# 4. Open Grafana
open http://localhost:3000
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

1. Open Grafana: http://localhost:3000
2. Go to **Explore** (compass icon in sidebar)
3. Select **Tempo** as the datasource
4. Use **Search** tab to find traces by:
   - Service name: `solr-mcp-server`
   - Span name (e.g., `POST /mcp`)
   - Duration
   - Tags

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

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_SAMPLING_PROBABILITY` | `1.0` | Trace sampling rate (0.0-1.0) |
| `OTEL_METRICS_URL` | `http://localhost:4318/v1/metrics` | OTLP metrics endpoint |
| `OTEL_TRACES_URL` | `http://localhost:4318/v1/traces` | OTLP traces endpoint |
| `OTEL_LOGS_URL` | `http://localhost:4318/v1/logs` | OTLP logs endpoint |

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

### 1. Reduce Sampling Rate

```properties
# application-http.properties
management.tracing.sampling.probability=0.1
```

### 2. Use Secure Endpoints

```properties
# Use HTTPS for production OTLP endpoints
management.otlp.metrics.export.url=https://otel-collector.prod.example.com/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=https://otel-collector.prod.example.com/v1/traces
management.opentelemetry.logging.export.otlp.endpoint=https://otel-collector.prod.example.com/v1/logs
```

### 3. Add Authentication Headers

If your OTLP collector requires authentication, configure headers in your OpenTelemetry configuration.

### 4. Resource Attributes

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
