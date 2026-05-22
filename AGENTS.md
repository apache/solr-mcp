# AGENTS.md

This file provides guidance to AI coding assistants when working with code in this repository.

## Project Overview

Solr MCP Server is a Spring AI Model Context Protocol (MCP) server that enables AI assistants to interact with Apache Solr. It provides tools for searching, indexing, and managing Solr collections through the MCP protocol.

- **Status:** Apache incubating project (v0.0.2-SNAPSHOT)
- **Java:** 25+ (centralized in build.gradle.kts)
- **Framework:** Spring Boot 3.5.14, Spring AI 1.1.7
- **License:** Apache 2.0

## Common Commands

```bash
# Build and test
./gradlew build                    # Full build with tests
./gradlew assemble                 # Build without tests (faster)

# Testing
./gradlew test                               # Run all tests
./gradlew test --tests SearchServiceTest     # Run specific test class
./gradlew test --tests "*IntegrationTest"    # Run integration tests
./gradlew test jacocoTestReport              # Tests with coverage report

# Code formatting (REQUIRED before commit)
./gradlew spotlessApply            # Apply formatting
./gradlew spotlessCheck            # Check formatting

# Docker images
./gradlew jibDockerBuild                                  # JVM (Jib):    solr-mcp:<v>                  (both stdio + http)
./gradlew bootBuildImage -Pnative                         # Native stdio: solr-mcp:<v>-native-stdio
./gradlew bootBuildImage -Pnative -Pprofile=http          # Native http:  solr-mcp:<v>-native-http
./gradlew dockerIntegrationTest                           # Test JVM Jib (stdio + http + MCP stdio)
./gradlew dockerIntegrationTest -Pnative                  # Test native stdio
./gradlew dockerIntegrationTest -Pnative -Pprofile=http   # Test native http

# Native image (requires GraalVM JDK 25; -Pnative applies the graalvm-native plugin)
./gradlew nativeCompile -Pnative             # Compile native binary (host OS only)
./gradlew nativeTest -Pnative                # Run tests as native image

# Run locally (requires `docker compose up -d` for Solr)
./gradlew bootRun                  # STDIO mode (default)
PROFILES=http ./gradlew bootRun    # HTTP mode
```

## Image × Mode matrix

Three published image artifacts cover the full transport × runtime matrix:

| Image                          | Toolchain | Build command                                            | STDIO | HTTP |
|--------------------------------|-----------|----------------------------------------------------------|-------|------|
| `solr-mcp:<v>`                 | Jib       | `./gradlew jibDockerBuild`                               | ✅    | ✅   |
| `solr-mcp:<v>-native-stdio`    | Paketo    | `./gradlew bootBuildImage -Pnative`                      | ✅    | ❌   |
| `solr-mcp:<v>-native-http`     | Paketo    | `./gradlew bootBuildImage -Pnative -Pprofile=http`       | ❌    | ✅   |

Why three images:

- **Jib's JVM image is dual-mode** because Jib uses a clean `java -jar`
  entrypoint with no launcher script. Stdout stays clean for MCP STDIO, and
  runtime `PROFILES=http` switches to web mode.
- **Paketo's JVM image is unsuitable for stdio** — its `libjvm` helpers
  (memory calculator, NMT, ca-certificates) write 6 lines to stdout before
  the JVM, breaking MCP's JSON-RPC stream. Verified via
  `DockerImageMcpClientStdioIntegrationTest`. Filed upstream as
  [paketo-buildpacks/libjvm#482](https://github.com/paketo-buildpacks/libjvm/issues/482).
  We use Jib for the JVM image instead.
- **Native images must AOT-pin to one profile** because Spring AOT bakes
  `spring.main.web-application-type` into the binary; activating both profiles
  picks `servlet` (http overrides stdio) and forces Tomcat to start regardless
  of runtime `PROFILES`. So one native image per profile.

Run examples:

```bash
# STDIO — Jib JVM (default profile is stdio)
docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest

# STDIO — native (faster startup, smaller image)
docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native-stdio

# HTTP — Jib JVM
docker run -p 8080:8080 --rm -e PROFILES=http \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest

# HTTP — native
docker run -p 8080:8080 --rm -e PROFILES=http \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native-http
```

## Architecture

### MCP Tools (src/main/java/org/apache/solr/mcp/server/)

Four service classes expose MCP tools via `@McpTool` annotations:

- **SearchService** (`search/`) - Full-text search with filtering, faceting, sorting, pagination
- **IndexingService** (`indexing/`) - Document indexing supporting JSON, CSV, XML formats
- **CollectionService** (`metadata/`) - List collections, get stats, health checks
- **SchemaService** (`metadata/`) - Schema introspection

### Document Creators (Strategy Pattern)

`indexing/documentcreator/` uses strategy pattern for format parsing:
- `SolrDocumentCreator` - Common interface
- `JsonDocumentCreator`, `CsvDocumentCreator`, `XmlDocumentCreator` - Format implementations
- `IndexingDocumentCreator` - Orchestrator that delegates to format-specific creators
- `FieldNameSanitizer` - Automatic field name validation for Solr compatibility

### Transport Modes

- **STDIO** (default): For Claude Desktop integration. Uses stdin/stdout for communication. Spring Web disabled.
- **HTTP**: For MCP Inspector and remote access. Servlet-based with optional OAuth2 security.

Configuration files: `application-stdio.properties`, `application-http.properties`

### Logging Architecture

The STDIO transport uses stdout for JSON-RPC messages, so any stray stdout output
corrupts the protocol. Logging is configured in two layers:

- **`logback.xml`** — Loaded by logback BEFORE Spring Boot initializes. Contains only
  a `NopStatusListener` to suppress logback's internal status messages (`|-INFO`,
  `|-WARN`) that would otherwise be written directly to stdout. Required for native
  image where logback falls through to `BasicConfigurator` without it.
- **`logback-spring.xml`** — Loaded by Spring Boot, overrides `logback.xml`. Uses
  `<springProfile>` blocks to scope appenders per transport mode:
  - **HTTP**: CONSOLE appender (stdout) + OpenTelemetry appender (OTLP log export with
    `captureExperimentalAttributes` and `captureKeyValuePairAttributes` enabled).
  - **STDIO**: No appenders defined. Relies on `logging.pattern.console=` in
    `application-stdio.properties` to produce empty output from Spring Boot's default
    console appender. The OTEL appender is intentionally excluded to keep stdout clean.
- **`application-stdio.properties`** — Sets `logging.pattern.console=` (empty pattern)
  which suppresses all Spring-managed console logging after Spring Boot initializes.

**Init order**: logback.xml → Spring Boot starts → logback-spring.xml → application-{profile}.properties

### Docker image strategy

Two toolchains, three image artifacts. See **Image × Mode matrix** above
for the published-artifact summary.

- **Jib** builds the JVM image (`solr-mcp:<v>`). Plain `java -jar` entrypoint,
  multi-arch (amd64 + arm64), clean stdout. One image serves both stdio and
  http; runtime `PROFILES` env var selects.
- **Paketo `bootBuildImage`** builds two native-image variants:
  - `solr-mcp:<v>-native-stdio` via `./gradlew bootBuildImage -Pnative`
  - `solr-mcp:<v>-native-http`  via `./gradlew bootBuildImage -Pnative -Pprofile=http`

**Why not Paketo for the JVM image:** Paketo's `libjvm` runtime helpers
(memory calculator, NMT, ca-certificates) write 6 status lines to stdout
before the JVM starts. This breaks MCP STDIO at the protocol level;
verified end-to-end via `DockerImageMcpClientStdioIntegrationTest` (Spring
AI MCP client times out on `initialize()`). Filed upstream as
[paketo-buildpacks/libjvm#482](https://github.com/paketo-buildpacks/libjvm/issues/482).
Jib doesn't have this problem because it writes a plain `java -jar`
entrypoint — no launcher script, no stdout pollution.

**Why two native images instead of one:** Spring AOT bakes
`spring.main.web-application-type` into the binary at AOT time. Activating
both `stdio` and `http` profiles during AOT picks `servlet` (http overrides
stdio), which forces Tomcat to start regardless of the runtime `PROFILES`
value — breaking stdio. So we AOT-pin per profile and produce one native
image per transport: stdio binary excludes web servlet beans; http binary
includes them.

### GraalVM Native Image

Native image is enabled via `-Pnative`. The native binary is compiled by the
`org.graalvm.buildtools.native` plugin (`nativeCompile`) or via Paketo
buildpacks (`bootBuildImage -Pnative`). Key configuration:

- **Opt-in flag:** `val nativeBuild = project.hasProperty("native")` in
  `build.gradle.kts`. The `graalvm-native` plugin is applied conditionally on
  this flag, and the entire `graalvmNative { ... }` configuration block runs
  only under `-Pnative`. `nativeCompile`, `nativeTest`, and the native variant
  of `bootBuildImage` all require `-Pnative`.
- **Per-profile AOT pin:** `processAot` runs with
  `--spring.profiles.active=$nativeProfile` where `nativeProfile` is `stdio`
  by default or `http` if `-Pprofile=http` is passed. Activating both profiles
  during AOT picks `servlet` and forces Tomcat regardless of runtime
  `PROFILES`, breaking stdio — hence one native image per profile.
- **Cross-platform builds:** `bootBuildImage` compiles inside a Linux builder
  container, so it works on any host OS (macOS, Linux, Windows). Multi-arch
  (amd64+arm64) is handled in CI via a GitHub Actions matrix; local builds
  produce a single image for the host architecture.
- **OTel build-time init:** OTel instrumentation BOM 2.11.0 lacks native metadata;
  `--initialize-at-build-time` is set for `io.opentelemetry.api`, `io.opentelemetry.context`,
  `io.opentelemetry.instrumentation.api`, and `io.opentelemetry.instrumentation.logback`.
  Do **NOT** add `io.opentelemetry.instrumentation.spring` — it contains CGLIB proxies.
- **Reflection hints:** `SolrNativeHints.java` registers hints that Spring AOT does
  not generate automatically:
  - **SolrJ types** (no native metadata): `QueryResponse`, `UpdateResponse`, `NamedList`,
    `SimpleOrderedMap`, `SolrDocument`, `SolrDocumentList`, `SolrInputDocument`,
    `SolrInputField`, `FacetField`, `FacetField.Count`
  - **MCP tool response records** (invisible to AOT because the MCP framework uses
    generic `Object` dispatch): `CollectionCreationResult`, `SolrHealthStatus`,
    `SolrMetrics`, `IndexStats`, `QueryStats`, `CacheStats`, `CacheInfo`,
    `HandlerStats`, `HandlerInfo`, `FieldStats`, `SearchResponse`
  - **Resource**: `logback.xml` (see Logging Architecture above)
- **Wire format:** `SolrConfig` uses `XMLRequestWriter` instead of the default
  `JavaBinRequestWriter`. The JavaBin binary codec uses deep reflection that would
  require extensive additional native image hints.
- **Docker tags:** Jib JVM = `solr-mcp:<version>` (`solr-mcp:latest`).
  Paketo native = `solr-mcp:<version>-native-stdio` /
  `solr-mcp:<version>-native-http` (with corresponding `:latest-native-*` tags).
- **CI:** Separate `native.yml` workflow; native failures do not block JVM-path merges.
- **Spec:** [docs/specs/graalvm-native-image.md](docs/specs/graalvm-native-image.md)

## Testing Structure

- **Unit tests** (`*Test.java`): Mocked dependencies, fast execution. Mockito-based
  unit tests are `@DisabledInNativeImage` because ByteBuddy proxies don't survive
  GraalVM's closed-world assumption.
- **Integration tests** (`*IntegrationTest.java`, `*DirectTest.java`): Real Solr via
  Testcontainers. Run as part of `./gradlew build` (JVM) and `./gradlew nativeTest -Pnative`
  (native test binary).
- **Docker tests** (`containerization/`): Tagged `@Tag("docker-integration")`, only
  run via `./gradlew dockerIntegrationTest`. They drive a built Docker image as a
  black-box subject under test.

### MCP-protocol vs container smoke tests

Two different layers verify stdio behavior — easy to confuse:

- `McpClientStdioIntegrationTest` (top-level package) spawns the raw `java -jar`
  JAR as a subprocess and runs the full MCP tool-call workflow. Verifies the
  application's stdio JSON-RPC at the JVM-process layer. Runs in `./gradlew build`
  and `./gradlew nativeTest -Pnative`. It does **not** test any Docker image.
- `DockerImageMcpClientStdioIntegrationTest` (`containerization/`) does the same
  workflow but spawns `docker run -i <image>` instead of `java -jar`. This is
  the protocol-level Docker image verification. Runs only in
  `dockerIntegrationTest`.
- `DockerImageStdioIntegrationTest` is a container **smoke** test (starts, stays
  alive, no errors in logs). It does not exercise MCP at all.
- `DockerImageHttpIntegrationTest` exercises the HTTP transport via real HTTP
  calls to `/actuator/health`, etc.

### Image × Mode test coverage

Each Gradle invocation builds a different image and runs the appropriate test
subset. Together the three invocations cover all four image × mode combinations:

| Gradle invocation                                          | Image built                       | Tests that run                                                                                              |
|------------------------------------------------------------|-----------------------------------|-------------------------------------------------------------------------------------------------------------|
| `./gradlew dockerIntegrationTest`                          | Jib JVM `solr-mcp:<v>`            | `DockerImageStdioIntegrationTest` (smoke) + `DockerImageMcpClientStdioIntegrationTest` (MCP STDIO protocol) + `DockerImageHttpIntegrationTest` (HTTP endpoint) |
| `./gradlew dockerIntegrationTest -Pnative`                 | Paketo `solr-mcp:<v>-native-stdio` | `DockerImageStdioIntegrationTest` + `DockerImageMcpClientStdioIntegrationTest`                                |
| `./gradlew dockerIntegrationTest -Pnative -Pprofile=http`  | Paketo `solr-mcp:<v>-native-http`  | `DockerImageHttpIntegrationTest`                                                                              |

The native-stdio image excludes the HTTP test (no servlet beans in the closed
world). The native-http image excludes the stdio tests (no MCP STDIO transport
in that profile). The Jib JVM image runs all three because it serves both
modes.

### CI coverage

`native.yml` runs `dockerIntegrationTest` over a `[stdio, http]` matrix on
every PR that touches native-related files, so both Paketo native variants
are exercised. The Jib JVM path runs in `build-and-publish.yml`.

### Solr Version Compatibility Testing

The Solr Docker image used in tests is configurable via the `solr.test.image` system property (default: `solr:9.9-slim`):

```bash
./gradlew test -Dsolr.test.image=solr:8.11-slim    # Solr 8.11
./gradlew test -Dsolr.test.image=solr:9.4-slim     # Solr 9.4
./gradlew test -Dsolr.test.image=solr:9.9-slim     # Solr 9.9 (default)
./gradlew test -Dsolr.test.image=solr:9.10-slim    # Solr 9.10
./gradlew test -Dsolr.test.image=solr:10-slim      # Solr 10
```

**Tested compatible versions:** 8.11, 9.4, 9.9, 9.10, 10

### Solr 10 Compatibility

Solr 10.0.0 is fully supported with the JSON wire format. The `/admin/mbeans` endpoint was
removed in Solr 10; `getCacheMetrics()` and `getHandlerMetrics()` now catch `RuntimeException`
(which covers `RemoteSolrException`) so they degrade gracefully and return `null`. Tests that
check `cacheStats` and `handlerStats` already handle `null` values.

Remaining known differences from Solr 9:
- **`/admin/mbeans` removed:** Cache and handler stats from `getCollectionStats()` will always be `null` on Solr 10. A future migration to `/admin/metrics` will restore these metrics.
- **Metrics migration:** Dropwizard metrics replaced by OpenTelemetry. Metric names switch to snake_case in Solr 10.
- **SolrJ base URL:** Already uses root URLs — **no change needed**.
- **SolrJ 10.x dependency:** Not yet on Maven Central (as of 2026-03-06); tests use SolrJ 9.x against a Solr 10 server. Update `solr-solrj` and Jetty BOM when 10.x is released.

## Key Configuration

Environment variables:
- `SOLR_URL`: Solr URL (default: `http://localhost:8983/solr/`)
- `PROFILES`: Transport mode (`stdio` or `http`)
- `OAUTH2_ISSUER_URI`: OAuth2 issuer URL (HTTP mode only)

Dependencies managed in `gradle/libs.versions.toml`.

## Commit Convention

Uses [Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Example: `feat(search): add fuzzy search support`
