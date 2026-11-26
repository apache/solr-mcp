# AGENTS.md

This file provides guidance to AI coding assistants when working with code in this repository.

## Project Overview

Solr MCP Server is a Spring AI Model Context Protocol (MCP) server that enables AI assistants to interact with Apache Solr. It provides tools for searching, indexing, and managing Solr collections through the MCP protocol.

- **Status:** Apache incubating project (v1.0.0-SNAPSHOT)
- **Java:** 25+ (centralized in build.gradle.kts)
- **Framework:** Spring Boot 4.2.0, Spring AI 2.1.0
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

# SBOM (Software Bill of Materials)
./gradlew cyclonedxBom                       # Generate build/reports/cyclonedx/application.cdx.json

# Gradle wrapper upgrade (run twice so the new version regenerates the wrapper jar;
# wrapper settings such as retries live on tasks.wrapper in build.gradle.kts)
./gradlew wrapper --gradle-version latest

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

# Run locally
# STDIO disables Docker Compose auto-start (spring.docker.compose.enabled=false in
# application-stdio.properties) — run `docker compose up -d` first, same as for the
# packaged jar/Docker/native image or to start the boot-ignored `lgtm` service.
docker compose up -d && ./gradlew bootRun    # STDIO mode (default)
# HTTP mode enables it (application-http.properties), so bootRun auto-starts/stops
# compose.yaml's solr/zoo via the developmentOnly spring-boot-docker-compose starter.
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
    -e OTEL_TRACES_URL=http://host.docker.internal:4318/v1/traces \
    -e OTEL_METRICS_URL=http://host.docker.internal:4318/v1/metrics \
    -e OTEL_LOGS_URL=http://host.docker.internal:4318/v1/logs \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest

# HTTP — native
docker run -p 8080:8080 --rm -e PROFILES=http \
    -e OTEL_TRACES_URL=http://host.docker.internal:4318/v1/traces \
    -e OTEL_METRICS_URL=http://host.docker.internal:4318/v1/metrics \
    -e OTEL_LOGS_URL=http://host.docker.internal:4318/v1/logs \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native-http
```

## Architecture

### MCP Tools (src/main/java/org/apache/solr/mcp/server/)

Five service classes expose MCP tools via `@McpTool` annotations:

- **SearchService** (`search/`) - Full-text search with filtering, faceting, sorting, pagination
- **IndexingService** (`indexing/`) - Document indexing supporting JSON, CSV, XML, and markdown formats
- **CollectionService** (`collection/`) - List collections, get stats, health checks
- **AliasService** (`alias/`) - List, create and delete collection aliases
- **SchemaService** (`schema/`) - Schema introspection and additive modification (add-fields, add-field-types)

### MCP contracts

Every tool, resource, prompt and completion accepts and returns types the server owns. Solr's response shapes are an implementation detail the server maps away. The rules, from the `mcp-contracts` spec of the `typed-mcp-contracts` OpenSpec change (#306):

- **Tools** return records and set `@McpTool(generateOutputSchema = true)`. The client then gets an `outputSchema` and `structuredContent`, and the SDK adds the same JSON as a text block. The SDK also validates each result against the schema, so a mismatch fails the call. A tool that returns a `List` (`list-aliases`, `list-collections`, `list-fields`) leaves the flag off: MCP 2025-11-25 restricts an `outputSchema` to an object root, so the list goes out as JSON text.
- **Optional record components** are `@Nullable`, with `@JsonInclude(NON_NULL)` on the record. Spring AI reads `@Nullable` through Spring's `Nullness` and leaves the component out of `required`.
- **Shapes** use only property names the server defines. Leave out Solr's response header, flag encodings, comma-joined lists and `@JsonAnySetter` catch-alls. The open maps are user content: document `fields`, and the class-specific `parameters` of field types, analyzers and analyzer components.
- **Failures** are exceptions, which reach the client as `isError: true`. A result reports what happened, with no `success`/`message` flags and no timestamps from the server's clock. Text that describes a failure is the server's own fixed wording; Solr's exception text goes only to the log.
- **Prompts** return `GetPromptResult` through `McpResults.prompt(description, role, text)`. Pick the role by speaker: `Role.USER` for instructions or a request to the model, which is every current prompt; `Role.ASSISTANT` only for text the model should treat as its own earlier reply. The description is the `*_DESCRIPTION` constant in `PromptNames`, shared with the annotation.
- **Resources** return `McpResults.jsonResource(request.uri(), json)`, with the same JSON as the matching tool. The method takes the `ReadResourceRequest` Spring AI injects next to any URI-template variables, so the content echoes the URI the client read.
- **Completions** return `McpResults.completion(matches, limit)`, which reports `total` and `hasMore`.

`McpContractConformanceIntegrationTest` (legacy protocol) and `McpModernContractConformanceIntegrationTest` (modern) check that every published `outputSchema` describes an object, that the list tools publish none, and that every tool with a schema, called against a real Solr, succeeds (so its `structuredContent` passed the SDK's own validation against the schema), returns a first text block holding the same JSON as `structuredContent`, and carries no `success` or `message` property. A tool that publishes one needs an entry in `McpContractConformanceTestBase.SAMPLE_ARGUMENTS`.

### Document Creators and pass-throughs

Only the formats Solr cannot parse itself go through server-side document creators
in `indexing/documentcreator/`:
- `SolrDocumentCreator` - Common interface
- `JsonDocumentCreator`, `MarkdownDocumentCreator` - Format implementations (Jackson, commonmark)
- `IndexingDocumentCreator` - Orchestrator that delegates to the format-specific creator

CSV and XML are forwarded unchanged to Solr's own update handlers (`/update` with
`text/csv` or `application/xml`); the server does not parse them and field names
are used as given. The one server-side step is `indexing/SolrUpdateXml`, a
hardened StAX read up to the root element that rejects anything but an `<add>`
block, because Solr's update grammar also carries `<delete>` and `<commit>` on the
same endpoint. There is no generic-XML mapping; the XML tool takes Solr update XML
(`<add><doc><field name="...">`). All four tools return an `IndexingResult`
(`collection`, `format`). For JSON and Markdown it also carries
`documentsSubmitted`, `documentsIndexed`, `fieldNames` (up to 50 field names of the
accepted documents, in sort order) and `fieldCount` (how many there are), and the
call fails if Solr rejects every document. Solr's update response carries no
document count, so for CSV and XML those four are absent.

### Transport Modes

- **STDIO** (default): For Claude Desktop integration. Uses stdin/stdout for communication. Spring Web disabled.
- **HTTP**: For MCP Inspector and remote access. Servlet-based with optional OAuth2 security.

Both transports speak the 2025 MCP revisions by default (`stateless` HTTP, `streamable`
STDIO; up to 2025-11-25). `MCP_PROTOCOL=modern` switches either one to the 2026-07-28
revision, served by the modern server in MCP Java SDK 2.2.0 (the first GA SDK release
with it) that Spring AI 2.1.0 auto-configures. It is opt-in because the revisions are not
wire-compatible. See [docs/modern-protocol.md](docs/modern-protocol.md).

Configuration files: `application-stdio.properties`, `application-http.properties`

### SBOM Architecture

The `org.cyclonedx.bom` plugin (3.4.1) writes `build/reports/cyclonedx/application.cdx.json`;
Spring Boot embeds it in the bootJar at `META-INF/sbom/application.cdx.json`, so every image
ships it, and the actuator serves it at `/actuator/sbom/application` in the `http` profile.

> **Do not drop `includeConfigs = [productionRuntimeClasspath]` on `cyclonedxDirectBom`**
> in `build.gradle.kts`. Without it the SBOM lists ~100 test/build-only components that are
> not in the fat jar, and nothing in the build catches it: the LICENSE completeness gate only
> fails on *missing* entries, never extra ones.

How the tasks fit together and the 2.4.1-pin history:
[dev-docs/sbom-and-license.md](dev-docs/sbom-and-license.md).

### Logging Architecture

STDIO uses stdout for JSON-RPC, so any stray stdout output corrupts the protocol. Rules:

- **One logback file: `logback-spring.xml`.** Never add a `logback.xml` or
  `logback-test.xml`, and never set `logging.config` in `application.properties` to point at
  one. Boot loads a standard-location file first and silently drops every `<springProfile>`
  appender, so HTTP mode runs with no console logs and no OTLP export.
  `LoggingConfigurationTest` fails the build on either.
- **The `stdio` profile has no appenders.** Do not add one, including the OTel appender.
- **`application-stdio.properties` must not set `logging.pattern.console=`** (the
  empty-pattern idiom Spring AI documents). Boot copies it into the JVM-wide
  `CONSOLE_LOG_PATTERN`, logback rejects the empty pattern, and in tests it breaks every
  later http-profile context in the same JVM. `LoggingConfigurationTest` enforces this.
- **Keep `Main.main()`'s first statement**, which sets `logback.statusListenerClass` to
  `NopStatusListener`. In a native image logback otherwise prints its status list to stdout
  mid-stream. Only `./gradlew dockerIntegrationTest -Pnative` covers this end to end.

If an HTTP-mode startup fails with no output, look for a `logback.xml` on the classpath.
Why each rule holds, the AOT logback model and the init order:
[dev-docs/logging.md](dev-docs/logging.md).

### Docker image strategy

Two toolchains, three image artifacts; see **Image × Mode matrix** above for the images and
why Paketo is not used for the JVM image. Jib's JVM image is multi-arch (amd64 + arm64) and
serves both transports; the two Paketo native images are AOT-pinned to one transport each.

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
  `PROFILES`, breaking stdio — hence one native image per profile. The MCP
  protocol revision is frozen the same way: Spring AI picks the modern server
  with `@ConditionalOnProperty`, so `MCP_PROTOCOL` at runtime has no effect on a
  native image (see [docs/modern-protocol.md](docs/modern-protocol.md)).
- **Cross-platform builds:** `bootBuildImage` compiles inside a Linux builder
  container, so it works on any host OS (macOS, Linux, Windows). Multi-arch
  (amd64+arm64) is handled in CI via a GitHub Actions matrix; local builds
  produce a single image for the host architecture.
- **OTel build-time init:** the OTel logback appender (`opentelemetry-logback-appender-1.0`)
  ships no native metadata; `--initialize-at-build-time` is set for `io.opentelemetry.api`, `io.opentelemetry.context`,
  `io.opentelemetry.instrumentation.api`, and `io.opentelemetry.instrumentation.logback`.
  Do **NOT** add `io.opentelemetry.instrumentation.spring` — it contains CGLIB proxies.
- **Reflection hints:** `SolrNativeHints.java` registers hints that Spring AOT does
  not generate automatically:
  - **SolrJ types** (no native metadata): `QueryResponse`, `UpdateResponse`, `NamedList`,
    `SimpleOrderedMap`, `SolrDocument`, `SolrDocumentList`, `SolrInputDocument`,
    `SolrInputField`, `FacetField`, `FacetField.Count`
  - **Solr API model types** used by `CoreAdminResponse.getCoreStatus()`:
    `SolrJerseyResponse`, `ErrorInfo`, `CoreStatusResponse` and their nested types
  - **MCP tool response records** (invisible to AOT because the MCP framework uses
    generic `Object` dispatch): `Alias`, `AliasDeletion`, `Collection`,
    `CollectionCreationResult`, `HealthStatus` and its `Reason`, `CollectionMetrics` and its
    nested stats records, `SearchResponse` and its nested `SearchHit`,
    `FacetField` and `FacetValue`, `SortClause`,
    `SchemaUpdateResult`, `IndexingResult` and its `Format` enum, `CollectionSchema` and its
    nested records, `IndexedField` and `FieldProperty`
  - **Spring AI**: `org.springframework.ai.mcp.annotation.context.DefaultMetaProvider`,
    which `MetaUtils` instantiates reflectively
  - **Resources**: `logback-spring.xml` (see Logging Architecture), and SolrJ's
    `EnvToSyspropMappings.properties` / `DeprecatedSystemPropertyMappings.properties`,
    which `EnvUtils` loads in its static initializer
- **Wire format:** the same on the JVM and in the native image. Responses are JSON
  (`JsonResponseParser`, chosen in #55 so the traffic is readable). Document batches go
  out as XML through `XMLRequestWriter` instead of SolrJ's default `JavaBinRequestWriter`;
  the writer only serializes `UpdateRequest`s, which `index-json-documents` and
  `index-markdown-documents` send. The CSV and XML tools post their payload as given, and
  every other request carries its own body. Neither choice is a native-image requirement:
  SolrJ's JavaBin classes use no reflection, and `JavaBinResponseParser` passes
  `nativeTest -Pnative` with no extra hints. XML stays for updates because it is readable
  and it is the path the native tests run.
- **Docker tags:** Jib JVM = `solr-mcp:<version>` (`solr-mcp:latest`).
  Paketo native = `solr-mcp:<version>-native-stdio` /
  `solr-mcp:<version>-native-http` (with corresponding `:latest-native-*` tags).
- **CI:** Separate `native.yml` workflow; native failures do not block JVM-path merges.
- **Spec:** [dev-docs/graalvm-native-image.md](dev-docs/graalvm-native-image.md)

### Spring Boot 4 Notes

The move from Spring Boot 3.5 / Spring AI 1.1 to Spring Boot 4 / Spring AI 2
([Spring AI 2.0 announcement](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now))
changed these things; keep them in mind when reading older docs or PRs:

- **Jackson 3:** `tools.jackson.databind` replaces `com.fasterxml.jackson.databind`. Annotations
  remain in `com.fasterxml.jackson.annotation`.
- **MCP Annotations:** Package moved from `org.springaicommunity.mcp.annotation` to
  `org.springframework.ai.mcp.annotation` in Spring AI 2.0.
- **Testcontainers 2.x:** Module names changed (e.g., `testcontainers-junit-jupiter`, `testcontainers-solr`).
- **JSpecify:** Comes transitively from Spring Framework 7 (`spring-core`), so there is no
  separate dependency.
- **`spring-boot-starter-aop` removed:** Replaced by `spring-boot-starter-aspectj` for
  `@Observed` annotation support.
- **Observability:** Uses `spring-boot-starter-opentelemetry` for traces, metrics, and log
  export via OTLP. The `micrometer-tracing-bridge-otel` + manual OTel BOM approach and the
  Prometheus registry (`/actuator/prometheus`) are gone.
- **MCP SDK:** `io.modelcontextprotocol.sdk:mcp`, with the Jackson 3 module
  (`mcp-json-jackson3`), at the version Spring AI depends on (2.2.0 under Spring AI
  2.1.0). There is no separate SDK pin; Spring AI 2.0.1 needed one via `mcp-bom`.
- **Span naming:** `@Observed` spans use `ClassName#methodName` (PascalCase) instead of
  the earlier `class-name#method-name` (kebab-case).

## Release LICENSE / NOTICE

ASF policy requires distinct LICENSE/NOTICE for the source and binary forms. The source-form
jars carry the repo-root `LICENSE`/`NOTICE` unchanged. The bootJar's `META-INF/LICENSE` and
`META-INF/NOTICE` are **generated on every build** by the `org.apache.solr.mcp.license-notice`
plugin in `buildSrc/` (`generateBinaryLicense`, `generateBinaryNotice`), from the CycloneDX
SBOM and the bundled jars' own NOTICE files. Do not hand-edit them.

- **Completeness gate:** `generateBinaryLicense` (in `check`) fails if a bundled dependency is
  missing from the SBOM. It is the only gate: there is no license allow-list and no
  per-dependency license store, so do not add one.
- Inspect a built jar with `unzip -p build/libs/solr-mcp-<v>.jar META-INF/LICENSE`.
- The tasks are tested in `buildSrc/src/test/kotlin/.../LicenseNoticeTasksTest.kt`, which
  `./gradlew build` runs.

Details: [dev-docs/sbom-and-license.md](dev-docs/sbom-and-license.md).

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
- **JSON assertions** go through JsonPath, using Spring Framework's
  `org.springframework.test.json.JsonContent` (json-path ships with
  `spring-boot-starter-test`): `assertThat(new JsonContent(text)).extractingPath("$.a.b").isEqualTo(...)`,
  `hasPath` / `doesNotHavePath`, and `isStrictlyEqualTo(...)` for a whole document. Do not match
  substrings of serialized JSON or walk it through `Map` casts. A deep scan or wildcard
  (`$..other`, `$.x.*.y`) returns a list and never throws `PathNotFoundException`, so
  `doesNotHavePath("$..other")` always fails: use `extractingPath("$..other").asArray().isEmpty()`.

### Module boundaries (`ModularityTests`)

`ModularityTests` runs Spring Modulith's `ApplicationModules.of(Main.class).verify()`.
Each direct sub-package of `org.apache.solr.mcp.server` (e.g. `search`, `indexing`,
`config`, `util`) is a module, so a new top-level package becomes a module
automatically; a module's own sub-packages (e.g. `indexing.documentcreator`) are
internal to it. The test fails on dependency
cycles between modules and on any use of another module's internal types.

- **If it fails, fix the design, not the test.** Move the type, invert the dependency,
  or call the owning module's top-level API. Do not delete, `@Disabled`, or loosen the
  test, and do not add `allowedDependencies`/`@NamedInterface` just to make it pass.
- **A new cross-module dependency must be explicit.** If a module genuinely needs to
  expose an internal package, declare it in that module's `package-info.java`
  (`@NamedInterface`, or `@ApplicationModule` metadata) so the dependency is visible
  in review.
- Spring Modulith is a **test-only** dependency (`spring-modulith-starter-test` in the
  `test` bundle); never add a runtime Modulith artifact. The test also writes PlantUML
  diagrams to `build/spring-modulith-docs/` (build output, not committed).

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

The Solr Docker image used in tests is pinned as `test-image-solr` in `gradle/libs.versions.toml` (the Grafana LGTM image for the OTLP test as `test-image-lgtm`), matching the literal default in `TestcontainersConfiguration`/`OtlpExportIntegrationTest`. Override the Solr image for one run with the `solr.test.image` system property:

```bash
./gradlew test -Dsolr.test.image=solr:9.4-slim     # Solr 9.4
./gradlew test -Dsolr.test.image=solr:9.9.0-slim     # Solr 9.9 (the pinned default)
./gradlew test -Dsolr.test.image=solr:9.10-slim    # Solr 9.10
./gradlew test -Dsolr.test.image=solr:9.11-slim    # Solr 9.11
./gradlew test -Dsolr.test.image=solr:10-slim      # Solr 10
```

**Tested compatible versions:** 9.4, 9.9, 9.10, 9.11, 10

### Solr 10 Compatibility

Solr 10.0.0 is fully supported with the JSON wire format. The `/admin/mbeans` endpoint was
removed in Solr 10; `getCacheMetrics()` and `getHandlerMetrics()` now catch `RuntimeException`
(which covers `RemoteSolrException`) so they degrade gracefully and return `null`. Tests that
check `caches` and `handlers` already handle their absence.

Remaining known differences from Solr 9:
- **`/admin/mbeans` removed:** Cache and handler stats from `getCollectionStats()` will always be `null` on Solr 10. A future migration to `/admin/metrics` will restore these metrics.
- **Metrics migration:** Dropwizard metrics replaced by OpenTelemetry. Metric names switch to snake_case in Solr 10.
- **SolrJ base URL:** Already uses root URLs — **no change needed**.
- **SolrJ version:** `solr-solrj` is on 10.0.0 (`gradle/libs.versions.toml`), released to Maven Central and bumped in #58. Jetty artifacts are declared versionless and managed by Spring Boot's BOM, so there is no separate Jetty pin to update. Note the client is *newer* than the default test server: `solr.test.image` defaults to `solr:9.9.0-slim`, so the standard build exercises a SolrJ 10 client against Solr 9.9.

## Key Configuration

Environment variables:
- `SOLR_URL`: Solr URL (default: `http://localhost:8983/solr/`)
- `PROFILES`: Transport mode (`stdio` or `http`)
- `MCP_PROTOCOL`: MCP spec revision. Unset = 2025 revisions (`stateless` HTTP, `streamable`
  STDIO); `modern` = 2026-07-28. Sets `spring.ai.mcp.server.protocol`; fixed at build time in
  native images.
- `OAUTH2_ISSUER_URI`: OAuth2 issuer URL (HTTP mode only)
- `OTEL_SAMPLING_PROBABILITY`: trace sampling rate (default `1.0`)
- `OTEL_TRACES_URL` / `OTEL_METRICS_URL` / `OTEL_LOGS_URL`: OTLP/HTTP endpoints
  (default `http://localhost:4318/v1/{traces,metrics,logs}`). Each is a complete
  signal path. On SB 3.x a single `OTEL_TRACES_URL` was a *base* gRPC endpoint on
  port 4317 — a value carried over from there stops exporting silently.

Dependencies managed in `gradle/libs.versions.toml`.

## Commit Convention

Uses [Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Example: `feat(search): add fuzzy search support`

## Spec-Driven Changes (OpenSpec)

Behaviour changes can be specified with [OpenSpec](https://github.com/Fission-AI/OpenSpec)
before they are implemented. `openspec/specs/` holds the current behaviour, one capability per
directory; `openspec/changes/<name>/` holds a proposed change (`proposal.md`, `design.md`,
`tasks.md`, and spec deltas), moved to `openspec/changes/archive/` once it ships.
`openspec/config.yaml` selects the `spec-driven` schema.

The workflow skills live in `.agents/skills/openspec-*` (linked into `.claude/skills/` for
Claude Code): propose → apply → archive, plus explore, update and sync. The CLI is
`npm install -g @fission-ai/openspec`; `openspec list` shows active changes and
`openspec validate <change>` checks one. Re-run `openspec update` after upgrading the CLI to
refresh the generated skills.

## Security

Security model: [SECURITY.md](./SECURITY.md)

Agents that scan this repository should consult `SECURITY.md` and the
threat model it links before reporting issues.

This repo is the Solr MCP server; its threat model is distinct from the Apache Solr search server (cross-referenced within).
