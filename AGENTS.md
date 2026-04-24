# AGENTS.md

This file provides guidance to AI coding assistants when working with code in this repository.

## Project Overview

Solr MCP Server is a Spring AI Model Context Protocol (MCP) server that enables AI assistants to interact with Apache Solr. It provides tools for searching, indexing, and managing Solr collections through the MCP protocol.

- **Status:** Apache incubating project (v0.0.2-SNAPSHOT)
- **Java:** 25+ (centralized in build.gradle.kts)
- **Framework:** Spring Boot 3.5.13, Spring AI 1.1.4
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
./gradlew dockerIntegrationTest              # Run Docker image tests (requires jibDockerBuild first)
./gradlew test jacocoTestReport              # Tests with coverage report

# Code formatting (REQUIRED before commit)
./gradlew spotlessApply            # Apply formatting
./gradlew spotlessCheck            # Check formatting

# Docker
./gradlew jibDockerBuild           # Build Docker image locally

# Native image (experimental, requires GraalVM JDK 25)
./gradlew nativeCompile -Pnative            # Compile native binary (host OS only)
./gradlew bootBuildImage                     # Build native Docker image (any OS/arch)
./gradlew nativeTest                         # Run tests as native image
./gradlew dockerIntegrationTest -Pnative     # Docker integration tests (native)

# Run locally (requires `docker compose up -d` for Solr)
./gradlew bootRun                  # STDIO mode (default)
PROFILES=http ./gradlew bootRun    # HTTP mode
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

### Why Jib Instead of Spring Boot Buildpacks

Spring Boot Buildpacks output logs to stdout, breaking MCP's STDIO protocol. Jib produces clean images with no stdout pollution, plus faster builds and multi-platform support (amd64/arm64).

### GraalVM Native Image (Opt-In)

An opt-in native image build is available via `-Pnative`, targeting the STDIO profile only.
The native binary is compiled by `org.graalvm.buildtools.native` (`nativeCompile`) and packaged
into a Docker image via `bootBuildImage` (Paketo buildpacks). Key configuration:

- **Opt-in flag:** `val nativeBuild = project.hasProperty("native")` in `build.gradle.kts`
- **Cross-platform:** `bootBuildImage` compiles inside a Linux builder container, so it works on any host OS (macOS, Linux, Windows).
- **AOT profile:** `processAot` runs with `--spring.profiles.active=stdio` under `-Pnative`
  so security autoconfig exclusions from `application-stdio.properties` are applied during
  hint generation. The `@SpringBootApplication` annotation is **not** modified.
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
    `HandlerStats`, `HandlerInfo`, `SearchResponse`
  - **Resource**: `logback.xml` (see Logging Architecture above)
- **Wire format:** `SolrConfig` uses `XMLRequestWriter` instead of the default
  `JavaBinRequestWriter`. The JavaBin binary codec uses deep reflection that would
  require extensive additional native image hints.
- **Docker tags:** JVM image = `solr-mcp:<version>` (Jib), native image = `solr-mcp:<version>-native` (bootBuildImage)
- **CI:** Separate `native.yml` workflow; native failures do not block JVM-path merges.
- **Spec:** [docs/specs/graalvm-native-image.md](docs/specs/graalvm-native-image.md)

## Testing Structure

- **Unit tests** (`*Test.java`): Mocked dependencies, fast execution
- **Integration tests** (`*IntegrationTest.java`, `*DirectTest.java`): Real Solr via Testcontainers
- **Docker tests** (`containerization/`): Tagged `@Tag("docker-integration")`, run separately

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
