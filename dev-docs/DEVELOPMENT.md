# Development Guide

This guide covers development workflows, testing, and building the Solr MCP Server.

## Build System

This project uses Gradle with version catalogs for dependency management. All dependencies and their versions are centrally managed in `gradle/libs.versions.toml`.

### Common Gradle Tasks

```bash
# Build the project and run tests
./gradlew build

# Build without tests (faster)
./gradlew assemble

# Clean and rebuild
./gradlew clean build

# Run tests only
./gradlew test

# Run Docker integration tests
./gradlew dockerIntegrationTest

# Check code formatting
./gradlew spotlessCheck

# Apply code formatting
./gradlew spotlessApply
```

### Build Outputs

The build produces an executable JAR in `build/libs/`:

- `solr-mcp-1.0.0-SNAPSHOT.jar` — Spring Boot executable (fat) JAR

### Publishing to Maven Local

To install the project artifacts to your local Maven repository for testing or local development:

```bash
./gradlew publishToMavenLocal
```

This publishes the following artifacts to `~/.m2/repository/org/apache/solr/solr-mcp/{version}/`:

- `solr-mcp-{version}.jar` - Main application JAR
- `solr-mcp-{version}-sources.jar` - Source code for IDE navigation
- `solr-mcp-{version}-javadoc.jar` - API documentation
- `solr-mcp-{version}.pom` - Maven POM with dependencies

This is useful when:
- Testing the library locally before publishing to a remote repository
- Sharing artifacts between local projects during development
- Verifying the published POM and artifact structure

### Generating the SBOM locally

The build produces a [CycloneDX](https://cyclonedx.org/) 1.6 Software Bill of Materials. The Spring Boot Gradle plugin also embeds it in the bootJar (and therefore every Docker image) at `META-INF/sbom/application.cdx.json`. To generate it from source without running the server:

```bash
./gradlew cyclonedxBom
cat build/reports/application.cdx.json
```

### Where the SBOM ships

Every released JAR and Docker image carries a CycloneDX 1.6 SBOM so downstream consumers can audit and scan the dependency graph:

- **Inside every JAR and image:** `META-INF/sbom/application.cdx.json` — embedded by the Spring Boot Gradle plugin at build time. The Jib JVM image (`solr-mcp:<v>`) and both Paketo native images (`solr-mcp:<v>-native-stdio`, `solr-mcp:<v>-native-http`) all package the bootJar contents, so the SBOM ships with every distribution channel.
- **HTTP endpoint** (`http` profile only): `GET /actuator/sbom/application` returns the SBOM as `application/vnd.cyclonedx+json`.
- **GitHub Releases:** `release-publish.yml` attaches `solr-mcp-<version>.cdx.json` to every official ASF release.
- **CI artifacts:** every `Build and Publish` run uploads `solr-mcp-sbom` (CycloneDX JSON) to the workflow run page, retained for 30 days.

### Consuming and scanning the SBOM

Fetch from a running HTTP-mode server:

```bash
curl -s http://localhost:8080/actuator/sbom/application > application.cdx.json
```

Scan it for CVEs (both tools natively consume CycloneDX 1.6):

```bash
# Trivy
trivy sbom application.cdx.json

# Grype
grype sbom:application.cdx.json
```

## Running Locally

### Start Solr

```bash
docker compose up -d
```

This starts a Solr instance in SolrCloud mode with ZooKeeper and creates two sample collections:
- `books` - Created empty. The books.csv download and post are commented out in
  `init-solr.sh`, so use it as a scratch collection or uncomment those lines.
- `films` - Collection populated with Solr's sample film data

### Run the Server

#### STDIO Mode (Default)

```bash
./gradlew bootRun
```

Or using the JAR:
```bash
java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```

#### HTTP Mode

```bash
PROFILES=http ./gradlew bootRun
```

Spring Boot Docker Compose will automatically start the services declared in `compose.yaml`
(Solr, ZooKeeper, and optionally LGTM for observability) before the application starts.

The server will start on http://localhost:8080

### Environment Variables

- `SOLR_URL`: Solr instance URL (default: `http://localhost:8983/solr/`)
- `SOLR_USERNAME`: HTTP Basic Authentication username (optional; required together with `SOLR_PASSWORD`)
- `SOLR_PASSWORD`: HTTP Basic Authentication password (optional; required together with `SOLR_USERNAME`)
- `PROFILES`: Transport mode (`stdio` or `http`)
- `SPRING_DOCKER_COMPOSE_ENABLED`: Enable/disable Docker Compose integration (default: `true`)

Example:
```bash
SOLR_URL=http://my-solr:8983/solr/ ./gradlew bootRun
```

## Testing

### Unit Tests

Unit tests use mocked dependencies for fast, isolated testing:

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests SearchServiceTest

# Run with coverage
./gradlew test jacocoTestReport
```

### Integration Tests

Integration tests use Testcontainers to spin up real Solr instances:

```bash
# Run integration tests
./gradlew test --tests "*IntegrationTest"
```

### Docker Integration Tests

These tests verify the Docker images built by Jib:

```bash
# Build Docker image first
./gradlew jibDockerBuild

# Run Docker integration tests
./gradlew dockerIntegrationTest
```

This runs tests tagged with `@Tag("docker-integration")` which verify:
- STDIO mode functionality
- HTTP mode functionality
- Container stability
- Solr connectivity

### Solr Version Compatibility

Tests run against `solr:9.9-slim` by default. Point them at another Solr version with the `solr.test.image` system property:

```bash
./gradlew test -Dsolr.test.image=solr:8.11-slim   # Solr 8.11
./gradlew test -Dsolr.test.image=solr:9.4-slim    # Solr 9.4
./gradlew test -Dsolr.test.image=solr:9.9-slim    # Solr 9.9 (default)
./gradlew test -Dsolr.test.image=solr:9.10-slim   # Solr 9.10
./gradlew test -Dsolr.test.image=solr:10-slim     # Solr 10
```

**Tested compatible versions:** 8.11, 9.4, 9.9, 9.10, 10.

**Solr 10 notes.** Solr 10 is fully supported with the JSON wire format. The `/admin/mbeans`
endpoint was removed in Solr 10, so `getCacheMetrics()`/`getHandlerMetrics()` catch
`RuntimeException` and return `null` — `cacheStats`/`handlerStats` from `get-collection-stats`
are therefore always `null` on Solr 10 (a future migration to `/admin/metrics` will restore
them). SolrJ is on 10.0.0; since `solr.test.image` defaults to `solr:9.9-slim`, the standard
build runs a SolrJ 10 client against a Solr 9.9 server.

### Test with MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) provides a web UI for testing:

```bash
# Start the server in HTTP mode
PROFILES=http ./gradlew bootRun

# In another terminal, start MCP Inspector
npx @modelcontextprotocol/inspector
```

Then open the browser URL provided (typically http://localhost:6274) and connect to http://localhost:8080/mcp

### Distributed Tracing Tests

`DistributedTracingTest` verifies that spans are produced for `@Observed` methods (e.g.
`SearchService#search`) without requiring any external tracing infrastructure.

```bash
./gradlew test --tests "org.apache.solr.mcp.server.observability.DistributedTracingTest"
```

**How it works.** Spring Boot 3.5's observability stack is
`@Observed annotation → Micrometer Observation API → Micrometer Tracing → tracer`. The test
swaps in a `SimpleTracer` (from `micrometer-tracing-test`) as a `@Primary` bean via
`OpenTelemetryTestConfiguration`, so spans are captured in-memory. Spans are retrieved with
`tracer.getSpans()` (returns `Deque<SimpleSpan>`) and named in kebab-case as
`class-name#method-name` (e.g. `search-service#search`). Test properties disable OTLP export,
force `management.tracing.sampling.probability=1.0`, and set
`management.observations.annotations.enabled=true`.

**Known issue — `OtlpExportIntegrationTest` is disabled.** The end-to-end OTLP export test
(via `LgtmStackContainer`/`testcontainers-grafana`) fails with a Jetty
`ClassNotFoundException` for `org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP`
under the current Jetty BOM. Core tracing is fully covered by `DistributedTracingTest`, so the
impact is low; fixing it would mean swapping the HTTP client (Apache HttpClient/OkHttp) or
upgrading `testcontainers-grafana`.

**Spring Boot 3.5 vs 4 differences** (relevant if/when we upgrade — SB4 drops the Micrometer
bridge for direct OpenTelemetry):

| Aspect | Spring Boot 3.5 | Spring Boot 4 |
|--------|-----------------|---------------|
| Tracing API | Micrometer Observation → Micrometer Tracing → OpenTelemetry | Direct OpenTelemetry integration |
| Test approach | `SimpleTracer` (`micrometer-tracing-test`) | `InMemorySpanExporter` (`opentelemetry-sdk-testing`) |
| Span retrieval | `tracer.getSpans()` | `spanExporter.getFinishedSpanItems()` |
| Span type | `SimpleSpan` (Micrometer) | `SpanData` (OpenTelemetry) |
| Bridge dependency | `micrometer-tracing-bridge-otel` required | not required |
| AspectJ starter | `spring-boot-starter-aop` | `spring-boot-starter-aspectj` |

## Code Quality

### Spotless Formatting

This project uses Spotless for consistent code formatting:

```bash
# Check if code is formatted correctly
./gradlew spotlessCheck

# Auto-format all code
./gradlew spotlessApply
```

**Important**: Always run `spotlessApply` before committing. The CI will reject PRs with formatting issues.

### Error Prone

Error Prone is configured to catch common Java mistakes at compile time. It will fail the build if issues are found.

## Build Info

The project generates build metadata at build time via the Spring Boot Gradle plugin. This creates `META-INF/build-info.properties` containing:

- `build.artifact`: Artifact name (e.g., "solr-mcp")
- `build.group`: Group ID (e.g., "org.apache.solr")
- `build.name`: Project name
- `build.version`: Version (e.g., "1.0.0-SNAPSHOT")
- `build.time`: Build timestamp

This metadata is used by:
- Spring Boot Actuator (`/actuator/info` endpoint)
- Test utilities (e.g., `BuildInfoReader`)
- Docker image labels
- Runtime version introspection

## Docker Development

See [DEPLOYMENT.md](DEPLOYMENT.md) for detailed Docker build instructions.

### Quick Docker Build

```bash
# Build to local Docker daemon
./gradlew jibDockerBuild

# Run the image
docker run -i --rm solr-mcp:1.0.0-SNAPSHOT
```

### Docker Executable Configuration

Jib needs to find the Docker executable. The build auto-detects based on your OS:

- **macOS**: `/usr/local/bin/docker`
- **Linux**: `/usr/bin/docker`
- **Windows**: `C:\Program Files\Docker\Docker\resources\bin\docker.exe`

Override if needed:
```bash
export DOCKER_EXECUTABLE=/custom/path/to/docker
./gradlew jibDockerBuild
```

### Native Image (GraalVM)

Native compilation is opt-in behind the `-Pnative` Gradle property. It trades a
slower, RAM-hungry build for sub-second startup, much lower RSS, and a smaller,
JRE-free image — most valuable for the local STDIO use case where Claude Desktop
launches a fresh container per session. See the [Image × Mode matrix](../AGENTS.md)
for which image serves which transport.

**Prerequisites.** `nativeCompile`/`nativeTest` need a GraalVM JDK on `PATH` or
`JAVA_HOME` (the plugin does not auto-provision a toolchain). Install locally via
SDKMAN:

```bash
sdk install java 25.0.2-graalce
```

or download from <https://www.graalvm.org>. CI provisions it with
`graalvm/setup-graalvm`.

**Build and test.**

```bash
# Compile a host-OS native binary
./gradlew nativeCompile -Pnative

# Run the test suite as a native image (slow — not part of ./gradlew build)
./gradlew nativeTest -Pnative

# Build a native Docker image via Paketo buildpacks (compiles inside a Linux
# builder container, so it works on any host OS — no cross-compilation)
./gradlew bootBuildImage -Pnative                    # stdio binary
./gradlew bootBuildImage -Pnative -Pprofile=http     # http binary
```

`nativeTest` is intentionally excluded from `./gradlew build` (an image compile
per run is slow); it runs in the dedicated `native.yml` CI job instead.

**Adding a reflection hint.** GraalVM's closed-world analysis can't see
reflective access, so when `nativeTest` fails with a missing-class/method or
resource error:

1. Add a targeted hint to a `RuntimeHintsRegistrar` (we centralize these in
   `SolrNativeHints.java`, registered via `@ImportRuntimeHints`) rather than
   scattering `@Reflective` annotations — the rules stay reviewable in one place.
2. Only if static analysis of the failures is too noisy, fall back to the
   tracing agent (`-agentlib:native-image-agent`); commit its output under
   `src/main/resources/META-INF/native-image/`.

**Known gotchas.**

- **Memory:** `nativeCompile` commonly needs 4–8 GB RAM. Ensure local/CI runners
  have headroom.
- **First Paketo build is large:** `bootBuildImage` downloads a ~1 GB builder on
  first run; CI caching mitigates this.
- **OpenTelemetry build-time init:** the pinned OTel instrumentation BOM lacks
  native metadata, so the build adds `--initialize-at-build-time` for four OTel
  packages (see `SolrNativeHints`/`build.gradle.kts`). Do **not** add
  `io.opentelemetry.instrumentation.spring` — it contains CGLIB proxies that
  cannot be build-time initialized. Bumping the OTel BOM to 2.26.1 currently
  fails at AOT time (`io.opentelemetry.common.ComponentLoader` not found) because
  it outpaces the OTel SDK that Spring Boot 3.5.x manages; revisit when Spring
  Boot aligns its managed OTel version.

## IDE Setup

### IntelliJ IDEA

1. Open the project directory
2. IDEA will automatically detect it as a Gradle project
3. Enable annotation processing for Lombok (if used)
4. Install Spotless plugin for automatic formatting

### VS Code

1. Install Java Extension Pack
2. Install Gradle Extension
3. Open the project directory
4. VS Code will configure automatically

## Debugging

### Debug STDIO Mode

Since STDIO uses stdin/stdout for protocol communication, traditional debugging can interfere. Use these approaches:

1. **Log to file**:
   ```java
   System.setOut(new PrintStream(new FileOutputStream("debug.log")));
   ```

2. **Use IDE remote debugging**:
   ```bash
   ./gradlew bootRun --debug-jvm
   ```
   Then attach your IDE debugger to port 5005

### Debug HTTP Mode

Standard debugging works normally:

1. Start in debug mode in your IDE
2. Set breakpoints
3. Make HTTP requests to http://localhost:8080

## Common Development Tasks

### Adding a New MCP Tool

1. Create a new method in an existing service or new service class
2. Annotate with `@McpTool`:
   ```java
   @McpTool(
       name = "tool_name",
       description = "What this tool does"
   )
   public String myTool(
       @McpToolParam(description = "Parameter description")
       String param
   ) {
       // Implementation
   }
   ```
3. Add tests
4. Update documentation

### Adding a New Document Format

1. Create a new class implementing `SolrDocumentCreator` (the format interface)
2. Register it with the `IndexingDocumentCreator` orchestrator
3. Add tests
4. Update documentation

### Modifying Configuration

1. Update `application.properties` for defaults
2. Update profile-specific properties as needed
3. Update `SolrConfigurationProperties` if adding new properties
4. Document changes in docs/ (e.g., DEVELOPMENT.md or DEPLOYMENT.md) and link from README

## Performance Testing

### Load Testing HTTP Mode

Use tools like Apache JMeter or wrk:

```bash
# Install wrk
brew install wrk

# Run load test
wrk -t4 -c100 -d30s http://localhost:8080/mcp
```

### Profiling

Use Java Flight Recorder:

```bash
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
     -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar
```

Analyze with Java Mission Control.

## Continuous Integration

The project uses GitHub Actions for CI/CD. See:

- `.github/workflows/build-and-publish.yml` - Build, test, and publish Docker images
- `.github/workflows/release-publish.yml` - the `publish-mcp-registry` job publishes to the MCP Registry after a release vote passes

Local CI simulation:

```bash
# Approximate what CI runs
./gradlew clean build spotlessCheck
```
