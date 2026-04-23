# Spec: GraalVM Native Image Support (Opt-In, bootBuildImage, STDIO)

Status: Draft
Owner: TBD
Target branch: `claude/graalvm-native-image-support-u1RqL`
Related: [Spring AI 1.1 blog post](https://spring.io/blog/2025/05/20/your-first-spring-ai-1)

## 1. Motivation

The Docker image produced by Jib currently ships the app on `eclipse-temurin:25-jre`.
For the **local STDIO use case** (Claude Desktop launching the container on demand
per session), JVM cold start and memory overhead are the main pain points:

- Each new session pays the JVM warm-up cost.
- Idle memory of a Spring Boot + Spring AI + SolrJ process is substantial even before
  it does any work.
- The image is hundreds of MB.

A GraalVM native image trades build-time complexity for:

- Sub-second startup.
- Significantly lower RSS.
- A smaller, self-contained image (no JRE layer).

Spring AI 1.1 added first-class AOT/native support, which makes this tractable
for this project.

## 2. Goals

1. Add an **opt-in** native image build path, triggered by a Gradle property
   (`-Pnative`), that produces a Docker image via `bootBuildImage`.
2. Keep the default build (JVM mode) unchanged.
3. Prove correctness: the existing test suite passes under `nativeTest`.
4. Prove the win: a reproducible benchmark script measures startup time,
   resident memory, and image size for JVM vs native, and the results are
   recorded in this spec.
5. Target transport: **STDIO profile only** for the initial cut. HTTP mode is
   out of scope for v1 but not precluded.

## 3. Non-Goals

- Replacing the JVM image. Both flavors ship.
- Native image support for the HTTP profile (OAuth2, actuator, Prometheus
  registry). These often need extra reachability metadata; deferred to a
  follow-up.
- JMH-style throughput benchmarks. Startup / RSS / disk only.

## 4. High-Level Approach

1. Add the **GraalVM Native Build Tools** plugin
   (`org.graalvm.buildtools.native`) alongside the existing Spring Boot plugin.
   Spring Boot's AOT tasks (`processAot`, `processTestAot`) are picked up
   automatically.
2. For native Docker images, `bootBuildImage` is used with
   `BP_NATIVE_IMAGE=true`. This compiles the native binary inside a Paketo
   builder container, so it works on any host OS/architecture — no
   cross-compilation needed.
3. Jib remains for JVM images (proven stdout-clean for STDIO).
4. For local native builds/tests (without Docker), `nativeCompile` and
   `nativeTest` are still available but produce host-OS binaries.
5. `processAot` runs with `--spring.profiles.active=stdio` under `-Pnative`
   so the correct bean graph (with security exclusions) is captured.
6. Native tests (`nativeTest`) run in a separate CI job, not as part of
   `./gradlew build`, because they are slow (image compile per run).

### 4.1 Why the Jib / bootBuildImage split

- **Jib for JVM images:** Proven clean stdout, multi-arch support
  (amd64/arm64), and no Docker daemon needed for registry push. This is the
  default image used by MCP STDIO clients.
- **`bootBuildImage` for native images:** Compiles inside a Linux Paketo
  builder container, solving the cross-OS problem (macOS hosts produce Linux
  binaries). The native binary IS the entrypoint — no buildpack launcher sits
  in between — so stdout is clean.
- The original concern about `bootBuildImage` stdout pollution applies to the
  **JVM buildpack launcher**, not to native images where the compiled binary
  runs directly as PID 1.

## 5. Gradle Changes

### 5.1 Plugin

`build.gradle.kts` plugins block:
```kotlin
alias(libs.plugins.graalvm.native)       // new, version-catalogued
```

`gradle/libs.versions.toml`:
```toml
[versions]
graalvm-native = "0.10.6"   # latest at time of writing; verify

[plugins]
graalvm-native = { id = "org.graalvm.buildtools.native", version.ref = "graalvm-native" }
```

### 5.2 Toolchain & Build Args

`nativeCompile` requires a GraalVM JDK on `PATH` or `JAVA_HOME`. The plugin
reads the location from the environment (toolchain detection is disabled by
default in the native build tools plugin). CI sets this up via
`graalvm/setup-graalvm@v1`; locally, use SDKMAN (`sdk install java
25.0.2-graalce`) or download from https://www.graalvm.org.

```kotlin
graalvmNative {
    binaries {
        named("main") {
            imageName.set("solr-mcp")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                // OTel 2.11.0 lacks native metadata — see §6.2
                "--initialize-at-build-time=io.opentelemetry.api",
                "--initialize-at-build-time=io.opentelemetry.context",
                "--initialize-at-build-time=io.opentelemetry.instrumentation.api",
                "--initialize-at-build-time=io.opentelemetry.instrumentation.logback",
            )
        }
        named("test") {
            buildArgs.addAll(
                "--no-fallback",
                "--initialize-at-build-time=io.opentelemetry.api",
                "--initialize-at-build-time=io.opentelemetry.context",
                "--initialize-at-build-time=io.opentelemetry.instrumentation.api",
                "--initialize-at-build-time=io.opentelemetry.instrumentation.logback",
            )
        }
    }
}
```

### 5.3 Opt-in flag

```kotlin
val nativeBuild = project.hasProperty("native")
```

The `-Pnative` flag is only needed for:
- `nativeCompile` — triggers `processAot` with the STDIO profile.
- `dockerIntegrationTest` — selects the `-native` image tag suffix.

`bootBuildImage` is always configured for native builds (it passes
`BP_NATIVE_IMAGE=true` unconditionally). No `-Pnative` flag is required
to run it.

### 5.4 bootBuildImage config for native

```kotlin
tasks.named<BootBuildImage>("bootBuildImage") {
    imageName.set("solr-mcp:${version}-native")
    tags.set(listOf("solr-mcp:latest-native"))
    environment.set(mapOf(
        "BP_NATIVE_IMAGE" to "true",
        "BP_NATIVE_IMAGE_BUILD_ARGUMENTS" to nativeImageBuildArgs.joinToString(" "),
        "BP_JVM_VERSION" to "25",
    ))
}
```

### 5.5 Native tests

No extra config needed beyond the plugin — `./gradlew nativeTest` is provided
by `org.graalvm.buildtools.native`. We do **not** wire it into `./gradlew build`.
It is invoked explicitly in a dedicated CI job.

Docker integration tests (`@Tag("docker-integration")`) should gain a
native counterpart that builds the `-native` image and re-runs the STDIO
integration scenario against it. This is the end-to-end proof.

## 6. Reflection / Resource Hints

### 6.1 SolrJ (JSON wire format only)

The client is constructed in `SolrConfig.java` as:

```java
new HttpJdkSolrClient.Builder(url)
    .withResponseParser(jsonResponseParser)   // JSON, not JavaBin
    .build();
```

This means the **JavaBin codec path is not taken**, which is the SolrJ
surface most frequently cited as native-hostile. The XML response parser
is similarly out of scope.

`SolrNativeHints.java` registers reflection hints for the narrow set of
types actually used: `QueryResponse`, `UpdateResponse`, `NamedList`,
`SolrDocument`, and `SolrDocumentList`.

### 6.2 OpenTelemetry / Micrometer tracing

The OpenTelemetry Spring Boot Starter officially supports native image
in newer versions. However, the project currently pins
`opentelemetry-instrumentation-bom:2.11.0`, which does **not** ship
native-image reachability metadata. The version catalog declares `2.26.1`
but bumping introduces an OTel SDK incompatibility with Spring Boot
3.5.x (`io.opentelemetry.common.ComponentLoader` not found), so the
bump is deferred until the OTel SDK and Spring Boot BOMs are aligned.

**Build-time initialization workaround:** The OTel logback appender's
`LoggingEventMapper` holds static `AttributeKey` fields (via
`InternalAttributeKeyImpl`) that end up in the image heap. GraalVM
requires their types to be build-time initialized. The `graalvmNative`
block adds targeted `--initialize-at-build-time` for four OTel packages:
- `io.opentelemetry.api` — `InternalAttributeKeyImpl`, `AttributeType`
- `io.opentelemetry.context` — context propagation
- `io.opentelemetry.instrumentation.api` — `MapBackedCache`
- `io.opentelemetry.instrumentation.logback` — the logback appender

**Important:** `io.opentelemetry.instrumentation.spring` must NOT be
included — it contains CGLIB proxy classes that cannot be build-time
initialized.

The OTLP/gRPC exporter is only wired in the HTTP profile, so the STDIO
native image does not exercise its reflection surface.

### 6.3 Security / OAuth2 on classpath under STDIO

Current state (already good):
- `application-stdio.properties` excludes `SecurityAutoConfiguration` and
  `ManagementWebSecurityAutoConfiguration` via `spring.autoconfigure.exclude`.
- `HttpSecurityConfiguration.java` is annotated `@Profile("http")`.
- `MethodSecurityConfiguration.java` is `@Profile("http")`.

**AOT mitigation:** The `processAot` Gradle task is configured (under
`-Pnative` only) to pass `--spring.profiles.active=stdio`, so the STDIO
property exclusions are active during AOT hint generation.

**Important:** The `@SpringBootApplication` annotation on `Main` is
**not** modified. Security autoconfiguration remains globally available
so the HTTP profile continues to work without any special handling.

### 6.4 Hints workflow

1. First pass: build and run `nativeTest` with `-Pnative`. Fix each
   reflection/resource failure by adding a targeted hint via a
   `RuntimeHintsRegistrar` in a `@Configuration` class registered with
   `@ImportRuntimeHints`. Preferred over annotation-scattering because
   the rules are centralized and reviewable.
2. Only fall back to the agent (`-agentlib:native-image-agent`) if
   static analysis of the failures is too noisy. Agent output goes to
   `src/main/resources/META-INF/native-image/org.apache.solr/solr-mcp/`
   and is committed.

## 7. Profile / Application Config

- Native v1 targets the STDIO profile. The native image's default profile
  is set via `SPRING_PROFILES_ACTIVE=stdio` env var in the
  `bootBuildImage` environment map.
- The `processAot` task also runs with `--spring.profiles.active=stdio`
  so the correct bean graph (with security exclusions) is captured.
- Actuator, Prometheus registry, Security starters: kept on the classpath.
  They do not interfere with STDIO AOT processing because the STDIO
  profile excludes security autoconfig and the HTTP-only `@Configuration`
  classes are not loaded.

## 8. Benchmark Plan

### 8.1 Script

`scripts/benchmark-native.sh` (new). Requirements:
- Runs on Linux (CI or Linux dev box).
- Builds both images:
  - `./gradlew jibDockerBuild` → `solr-mcp:<v>` (JVM)
  - `./gradlew bootBuildImage` → `solr-mcp:<v>-native`
- For each image, measures:
  - **Image size on disk** via `docker image inspect <img> --format '{{.Size}}'`.
  - **Startup time**: time from `docker run` until the container prints its
    MCP "server ready" signal on stdout (STDIO mode). If no such signal
    exists, add one in `Main` behind a `solr.mcp.startup.log=true` flag that
    is enabled for benchmarks only.
  - **Memory after startup**: after server-ready, sample
    `docker stats --no-stream --format '{{.MemUsage}}'` N times over 5 seconds,
    record the minimum (steady-state idle RSS).
  - **Memory after one search**: drive one MCP `search` call via the existing
    client harness, then resample.
- Each measurement is the median of 5 runs.
- Output a markdown table to stdout and write `docs/specs/benchmark-results.md`.

### 8.2 Results table (to be filled in after implementation)

| Metric                          | JVM (`:<v>`) | Native (`:<v>-native`) | Delta |
|---------------------------------|--------------|------------------------|-------|
| Image size (MB)                 | TBD          | TBD                    | TBD   |
| Cold start (ms)                 | TBD          | TBD                    | TBD   |
| Idle RSS after start (MB)       | TBD          | TBD                    | TBD   |
| RSS after first search (MB)     | TBD          | TBD                    | TBD   |
| `nativeTest` wall-clock         | n/a          | TBD                    | n/a   |

### 8.3 Acceptance thresholds

The native image is considered a win for the STDIO use case if **all** hold:
- Startup ≤ 25% of JVM startup.
- Idle RSS ≤ 50% of JVM idle RSS.
- Image size ≤ 60% of JVM image size.

If any threshold fails, capture the numbers anyway and keep the native
path as opt-in behind the same flag; document the gap.

## 9. CI

Add a separate GitHub Actions workflow (or job in the existing one)
`native.yml`:
- Triggers: `workflow_dispatch` and on PRs touching this spec, the native
  config, or `gradle/libs.versions.toml`.
- Steps:
  1. Set up GraalVM JDK 25 (via `graalvm/setup-graalvm@v1`).
  2. `./gradlew nativeTest`
  3. `./gradlew bootBuildImage`
  4. `./gradlew dockerIntegrationTest -Pnative` (native-mode variant of the
     STDIO integration test).
  5. `scripts/benchmark-native.sh` — upload results table as a job artifact.

The default PR build (`./gradlew build`) remains JVM-only and fast.

## 10. Rollout

1. Land Gradle plumbing behind `-Pnative` with no native-specific hints.
2. Iterate on hints until `nativeTest` is green.
3. Add `dockerIntegrationTest -Pnative` path and the STDIO integration test
   variant.
4. Land `scripts/benchmark-native.sh` and fill in section 8.2.
5. Update root README with a "Native image (experimental)" section pointing
   at this spec, plus the one-liner build command.
6. Tag the native image as `:latest-native` so users can opt in on pull:
   `docker pull solr-mcp:latest-native`.

## 11. Risks & Open Questions

- **SolrJ native compatibility.** *Downgraded from medium to low-medium.*
  The project already uses `JsonResponseParser` on `HttpJdkSolrClient`,
  avoiding the JavaBin/XML reflective surface. Residual risk is
  `ServiceLoader` metadata and a narrow set of response bean fields;
  handle via a local `RuntimeHintsRegistrar`. See §6.1.
- **OpenTelemetry starter.** *Managed via build-time init workaround.*
  The OTel instrumentation BOM 2.11.0 does not ship native metadata.
  Bumping to 2.26.1 (declared in the version catalog) fails at AOT time
  because 2.26.1 requires `io.opentelemetry.common.ComponentLoader` which
  is not present in the OTel SDK version managed by Spring Boot 3.5.x.
  The workaround is `--initialize-at-build-time` for four targeted OTel
  packages (see §6.2). The OTLP/gRPC exporter is only wired in the HTTP
  profile, so the STDIO native image does not exercise its reflection
  surface. **Follow-up:** align OTel BOM + SDK versions when Spring Boot
  upgrades its managed OTel dependency.
- **Spring Security + OAuth2 resource server.** *Downgraded.* Already
  excluded via `spring.autoconfigure.exclude` in
  `application-stdio.properties` and all security config classes carry
  `@Profile("http")`. The AOT concern is addressed by pinning
  `spring.profiles.active=stdio` on the `processAot` Gradle task so the
  exclusions are applied before hint generation. The `@SpringBootApplication`
  annotation is **not** modified — security autoconfiguration remains
  globally available for the HTTP profile.
- **AOT profile correctness.** AOT runs once at build time with one
  profile active. Building the native image with the wrong (or no)
  profile means the wrong bean graph is captured. V1 commits to STDIO
  only; HTTP native is an explicit follow-up.
- **Paketo builder download size.** `bootBuildImage` downloads a large
  Paketo builder on first run (~1 GB). CI caching mitigates this.
- **Build time & memory.** `nativeCompile` is RAM-hungry (commonly 4–8 GB).
  Ensure CI runners have headroom.
- **`mcp-server-security` library.** Small, non-Spring-official. Verify
  it has no eager `@Configuration` classes that load outside `@Profile("http")`
  that would force its classes into the STDIO AOT graph.
  image.
- HTTP profile native image (Actuator, Prometheus, OAuth2).
- Profile-Guided Optimization (PGO) builds.
- Publishing the native image from CI to GHCR / Docker Hub.
