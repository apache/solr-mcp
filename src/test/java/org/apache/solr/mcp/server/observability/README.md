# Distributed Tracing Tests

This package contains comprehensive tests for OpenTelemetry distributed tracing functionality.

## Overview

We use a **three-tier testing strategy** to verify that distributed tracing works correctly:

1. **Unit Tests** - Fast, in-memory verification of span creation
2. **Integration Tests** - End-to-end validation with real OTLP collector
3. **Manual Testing** - Local development verification with Grafana

## Test Files

### 1. `DistributedTracingTest.java`

**Purpose**: Fast unit tests using in-memory span exporter

**What it tests**:

- Spans are created for `@Observed` methods
- Span attributes are correctly populated
- Span hierarchy (parent-child relationships) is correct
- Span kinds (INTERNAL, CLIENT, etc.) are appropriate
- Service name is included in resource attributes
- Span durations are valid

**How it works**:

- Uses `InMemorySpanExporter` to capture spans without external infrastructure
- Uses Awaitility for asynchronous span collection
- Runs fast (seconds) - suitable for CI/CD pipelines

**Run with**:

```bash
./gradlew test --tests DistributedTracingTest
```

### 2. `OtlpExportIntegrationTest.java`

**Purpose**: End-to-end integration test with real OTLP collector

**What it tests**:

- Traces are successfully exported to OTLP collector
- Traces appear in Tempo (distributed tracing backend)
- Service name and tags are correctly included
- OTLP HTTP protocol works correctly
- Network communication is successful

**How it works**:

- Starts Grafana LGTM stack in Testcontainers (includes OTLP collector + Tempo)
- Configures app to export to the test container
- Executes operations that create spans
- Queries Tempo API to verify traces were received

**Run with**:

```bash
./gradlew test --tests OtlpExportIntegrationTest
```

**Note**: This test is slower (30+ seconds) due to:

- Container startup time
- Tempo ingestion delay
- Network I/O

### 3. Helper Classes

#### `ObservabilityTestConfiguration.java`

Test configuration that provides:

- `InMemorySpanExporter` bean for capturing spans
- `SdkTracerProvider` configured to use in-memory exporter

#### `LgtmAssertions.java`

Helper for querying LGTM stack (Tempo, Prometheus, Loki):

```java
LgtmAssertions lgtm = new LgtmAssertions(lgtmContainer, objectMapper);

// Fetch trace by ID
Optional<JsonNode> trace = lgtm.getTraceById(traceId);

// Search traces with TraceQL
Optional<JsonNode> traces = lgtm.searchTraces("{.service.name=\"solr-mcp-server\"}", 10);

// Query Prometheus metrics
Optional<JsonNode> metrics = lgtm.queryPrometheus("http_server_requests_seconds_count");
```

#### `TraceAssertions.java`

Fluent assertion utilities for trace verification:

```java
// Assert span exists
TraceAssertions.assertSpanExists(spans, "SearchService.search");

// Assert span has attribute
TraceAssertions.

assertSpanHasAttribute(spans, "SearchService","collection","test");

// Assert span count
TraceAssertions.

assertSpanCount(spans, 3);

// Assert span kind
TraceAssertions.

assertSpanKind(spans, "SearchService",SpanKind.INTERNAL);

// Find specific span
SpanData span = TraceAssertions.findSpan(spans, "SearchService");
```

## Running All Tracing Tests

```bash
# Run all observability tests
./gradlew test --tests "org.apache.solr.mcp.server.observability.*"

# Run with coverage
./gradlew test jacocoTestReport --tests "org.apache.solr.mcp.server.observability.*"
```

## Manual Testing

For local development, you can verify tracing works by:

1. **Start LGTM stack**:
   ```bash
   docker compose up -d lgtm
   ```

2. **Run the application in HTTP mode**:
   ```bash
   PROFILES=http ./gradlew bootRun
   ```

3. **Execute some operations** (via MCP client or HTTP API):
    - Index documents
    - Search collections
    - List collections

4. **Open Grafana**: http://localhost:3000
    - Navigate to "Explore"
    - Select "Tempo" datasource
    - Search for service name: `solr-mcp-server`
    - View traces, spans, and distributed call graphs

## What Gets Traced?

All service methods annotated with `@Observed` automatically create spans:

- **SearchService.search()** - Search operations
- **IndexingService.indexJsonDocuments()** - Document indexing
- **IndexingService.indexCsvDocuments()** - CSV indexing
- **IndexingService.indexXmlDocuments()** - XML indexing
- **CollectionService.listCollections()** - Collection listing
- **SchemaService.getSchema()** - Schema retrieval

Spring Boot also automatically instruments:

- HTTP requests (incoming and outgoing)
- JDBC database queries
- RestClient/RestTemplate calls
- Scheduled tasks

## Continuous Integration

### In CI Pipelines

The **unit tests** (`DistributedTracingTest`) are fast and suitable for CI:

```yaml
# GitHub Actions example
-   name: Run observability tests
    run: ./gradlew test --tests "DistributedTracingTest"
```

The **integration tests** (`OtlpExportIntegrationTest`) can be run:

- On merge to main (comprehensive validation)
- Nightly builds
- Pre-release verification

### Coverage Expectations

- **Unit Tests**: Should cover all `@Observed` methods
- **Integration Tests**: Should verify OTLP export works end-to-end
- **Target Coverage**: Aim for 80%+ coverage of observability code

## Troubleshooting

### Spans Not Appearing in Tests

**Problem**: `InMemorySpanExporter` returns empty list

**Solutions**:

1. Verify `@Observed` annotation is present on method
2. Ensure `management.observations.annotations.enabled=true`
3. Check that AspectJ is configured (`spring-boot-starter-aspectj` dependency)
4. Use `await()` with sufficient timeout (spans are async)

### Integration Test Timeout

**Problem**: `OtlpExportIntegrationTest` times out waiting for traces

**Solutions**:

1. Increase timeout: `await().atMost(60, TimeUnit.SECONDS)`
2. Check LGTM container is running: `docker ps | grep lgtm`
3. Verify OTLP endpoint configuration in test properties
4. Check Tempo logs: `docker logs solr-mcp-lgtm-1`

### No Traces in Grafana (Manual Testing)

**Problem**: Grafana/Tempo shows no traces

**Solutions**:

1. Verify LGTM stack is running: `docker compose ps`
2. Check OTLP endpoint: `http://localhost:4318/v1/traces`
3. Verify application properties:
    - `spring.opentelemetry.tracing.export.otlp.endpoint` is set
    - `management.tracing.sampling.probability=1.0` (100% sampling)
4. Check application logs for OTLP export errors
5. Verify Grafana datasource: Grafana → Connections → Data Sources → Tempo

## Best Practices

### Writing New Tracing Tests

1. **Use in-memory exporter for unit tests** (fast feedback)
2. **Use real OTLP collector sparingly** (only for integration tests)
3. **Always use Awaitility** for async span collection
4. **Test both success and error cases** (errors should also create spans)
5. **Verify span attributes** - not just span existence

### Example Test Pattern

```java

@Test
void shouldCreateSpanForMyOperation() throws Exception {
    // Given: Initial state
    spanExporter.reset();

    // When: Execute operation
    myService.doSomething();

    // Then: Verify span was created
    await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                List<SpanData> spans = spanExporter.getFinishedSpanItems();
                TraceAssertions.assertSpanExists(spans, "MyService.doSomething");
                TraceAssertions.assertSpanHasAttribute(spans, "MyService", "operation", "doSomething");
            });
}
```

## Resources

- [OpenTelemetry Java SDK Testing](https://github.com/open-telemetry/opentelemetry-java/tree/main/sdk/testing)
- [Spring Boot Observability](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.observability)
- [Micrometer Tracing](https://micrometer.io/docs/tracing)
- [Grafana Tempo](https://grafana.com/docs/tempo/latest/)
