# GraalVM Native Image

How the project builds, tests, and ships GraalVM native images, and the
hard-won knowledge needed to keep them working. This is the deep-dive
reference; for the short version (commands, the image matrix) see the
**GraalVM Native Image** and **Image × Mode matrix** sections of `AGENTS.md`.

## What exists today

Native image is **opt-in**, gated entirely behind the `-Pnative` Gradle
property. Without it, nothing native-related is applied and the build is a
plain JVM build.

The project ships three Docker artifacts; two of them are native:

| Image                          | Toolchain | Transport |
|--------------------------------|-----------|-----------|
| `solr-mcp:<v>`                 | Jib       | stdio + http (runtime `PROFILES`) |
| `solr-mcp:<v>-native-stdio`    | Paketo    | stdio only |
| `solr-mcp:<v>-native-http`     | Paketo    | http only  |

The single most important fact about the native path: **a native binary is
AOT-pinned to exactly one Spring profile**, so we build one native image per
transport rather than one dual-mode image. Everything below follows from that.

## Why native at all

The motivating use case is local STDIO: an MCP client (e.g. Claude Desktop)
launches the container on demand, once per session. There the JVM's costs
dominate — cold-start warm-up on every session, a large idle RSS for a Spring
Boot + Spring AI + SolrJ process, and a hundreds-of-MB image carrying a JRE
layer. A native image trades build-time complexity for sub-second startup,
much lower RSS, and a smaller self-contained image. Spring AI 1.1's first-class
AOT support is what makes this tractable here.

The JVM image is **not** going away — it remains the default and the only multi-arch-from-one-build artifact. Native is an alternative, not a replacement.

## Build wiring

All native configuration lives in `build.gradle.kts` and is guarded by two
properties:

- **`-Pnative`** (`val nativeBuild = project.hasProperty("native")`) — the
  master switch. Only when set is the `org.graalvm.buildtools.native` plugin
  applied, the `graalvmNative { … }` block configured, and the `processAot`
  profile pin installed. `nativeCompile`, `nativeTest`, and the native variant
  of `bootBuildImage` all require it.
- **`-Pprofile=stdio|http`** (`nativeProfile`, default `stdio`) — selects the
  Spring profile baked in at AOT time, and therefore which transport the
  resulting binary serves. Validated early: an invalid value fails the
  configuration phase.

The GraalVM image arguments are defined once and shared by both the local
`graalvmNative` builds and the Paketo `bootBuildImage` builds, so the two paths
can never drift:

```kotlin
val nativeImageBuildArgs = listOf(
    "--no-fallback",
    "-H:+ReportExceptionStackTraces",
    "--initialize-at-build-time=io.opentelemetry.api",
    "--initialize-at-build-time=io.opentelemetry.context",
    "--initialize-at-build-time=io.opentelemetry.instrumentation.api",
    "--initialize-at-build-time=io.opentelemetry.instrumentation.logback",
)
```

`bootBuildImage` passes these through to the buildpack via
`BP_NATIVE_IMAGE_BUILD_ARGUMENTS`, names the image `solr-mcp:<v>-native-<profile>`,
and pins the runtime profile (`SPRING_PROFILES_ACTIVE` / `BPE_DEFAULT_PROFILES`).
Compilation happens **inside a Linux Paketo builder container**, so a macOS or
Windows host still produces a working Linux binary — no cross-compilation
toolchain to manage. The cost is a large (~1 GB) one-time builder download.

For local work without Docker, the plugin also registers `nativeCompile` (host-OS
binary) and `nativeTest` (run the test suite as a native image). Both need a
**GraalVM JDK 25** on `JAVA_HOME`/`PATH` (e.g. `sdk install java 25-graalce`);
the native build tools plugin reads it from the environment rather than via
Gradle toolchain auto-detection.

## The AOT-per-profile constraint (why two native images)

Spring AOT runs **once at build time** with a fixed set of active profiles and
freezes the resulting bean graph — including `spring.main.web-application-type`
— into the binary. The `http` profile sets that to `servlet`; `stdio` leaves it
`none`. If you activate both profiles during AOT, `http` wins, `servlet` is
baked in, and the binary **always starts Tomcat regardless of the runtime
`PROFILES` value**. That breaks STDIO at the protocol level (Tomcat logging and
startup noise corrupt the JSON-RPC stream).

There is no way to defer this decision to runtime in a native image. So we pin
it: `processAot` is configured to run with `--spring.profiles.active=$nativeProfile`,
and we produce a separate binary per profile. The `stdio` binary excludes the
web servlet beans; the `http` binary includes them.

```kotlin
tasks.named<JavaExec>("processAot") {
    args("--spring.profiles.active=$nativeProfile")
}
```

This is also why the JVM Jib image can be dual-mode while the native images
cannot: the JVM image makes the `web-application-type` decision at *runtime*
from the `PROFILES` env var; the native image already made it at *build time*.

## Why Jib for JVM, Paketo for native

- **Jib (JVM image).** Plain `java -jar` entrypoint, no launcher script, so
  stdout stays clean for MCP STDIO. Builds multi-arch (amd64 + arm64) from a
  single invocation without a Docker daemon. One image serves both transports.
- **Paketo `bootBuildImage` (native images).** Solves the cross-OS compile
  problem and runs the compiled binary directly as PID 1, so its stdout is
  clean too.
- **Why not Paketo for the JVM image:** Paketo's `libjvm` helpers (memory
  calculator, NMT, ca-certificates) write ~6 status lines to stdout *before*
  the JVM starts, which breaks MCP STDIO. Filed upstream as
  [paketo-buildpacks/libjvm#482](https://github.com/paketo-buildpacks/libjvm/issues/482).
  The native images don't hit this because there's no JVM launcher in front of
  the binary. Multi-arch for native is handled in CI via a GitHub Actions
  matrix rather than from one build.

## Reflection and resource hints

Spring AOT generates most hints automatically, but a few types are invisible to
it and must be registered by hand. They live in
`SolrConfig`'s sibling `SolrNativeHints.java`, a `@Configuration` annotated
`@ImportRuntimeHints(...)`. Registering hints through a `RuntimeHintsRegistrar`
(rather than scattering `@RegisterReflection` annotations) keeps the whole
reflective surface in one reviewable place. The class is registered
unconditionally; on the JVM path it is simply a no-op.

### Wire format choice underpins the small hint surface

`SolrConfig` deliberately builds the client to **avoid SolrJ's JavaBin codec**,
which uses deep reflection that would otherwise demand extensive native
metadata:

- Responses use the **JSON** parser (`JsonResponseParser` on
  `HttpJdkSolrClient`), not JavaBin.
- Updates use `XMLRequestWriter`, not the default `JavaBinRequestWriter`.

Because the JavaBin path is never taken, the hints reduce to a narrow set of
container and value types.

### What's registered, and why

- **SolrJ response/value types** — `QueryResponse`, `UpdateResponse`,
  `NamedList`, `SimpleOrderedMap`, `SolrDocument`, `SolrDocumentList`,
  `SolrInputDocument`, `SolrInputField`, `FacetField`, `FacetField.Count`.
  These are the JSON response containers and indexing inputs reflected over at
  runtime.
- **SolrJ schema request types** — `AnalyzerDefinition`, `FieldTypeDefinition`.
  Needed for Jackson's `convertValue` when `add-field-types` deserializes
  analyzer trees in native image.
- **SolrJ schema response type** — `SchemaRepresentation`, returned by the
  `get-schema` MCP tool. Without its hints, Spring AI's JSON in native image
  silently drops the `fields`/`fieldTypes`/`dynamicFields`/`copyFields` arrays —
  a quiet correctness bug, not a crash.
- **MCP tool response records** — `CollectionCreationResult`, `SolrHealthStatus`,
  `SolrMetrics`, `IndexStats`, `QueryStats`, `CacheStats`, `CacheInfo`,
  `HandlerStats`, `HandlerInfo`, `SearchResponse`, `SchemaUpdateResult`. These
  are package-private records the MCP framework dispatches via generic
  `Object`, so AOT can't see them. They're registered by name with
  `registerTypeIfPresent`.
- **`logback.xml` resource.** Registered as a resource pattern so logback's
  early (pre-Spring) initialization finds it and installs the `NopStatusListener`.
  Without it, logback falls through to `BasicConfigurator` and writes status
  lines to stdout, corrupting STDIO framing. (See the Logging Architecture
  section of `AGENTS.md`.)

### Adding a hint when a native run fails

1. Reproduce with `./gradlew nativeTest -Pnative` (or run the native binary and
   trigger the failing path). GraalVM reports the missing reflective/resource
   element.
2. Add a targeted registration in `SolrNativeHints.Registrar` — a single
   `registerType(...)` with `INVOKE_DECLARED_CONSTRUCTORS`,
   `INVOKE_DECLARED_METHODS`, `DECLARED_FIELDS`, or a `registerPattern(...)` for
   a resource. Prefer this over the tracing agent so the rule is explicit and
   reviewed.
3. Only fall back to the native-image agent (`-agentlib:native-image-agent`) if
   static analysis of the failure is too noisy; commit any generated metadata
   under `src/main/resources/META-INF/native-image/`.

## OpenTelemetry build-time initialization

The OTel instrumentation BOM is pinned at **2.11.0**, which ships **no**
native-image reachability metadata. The OTel logback appender's
`LoggingEventMapper` holds static `AttributeKey` fields (via
`InternalAttributeKeyImpl`) that land in the image heap, and GraalVM requires
their types to be initialized at build time. Hence the four
`--initialize-at-build-time` entries in `nativeImageBuildArgs`:

- `io.opentelemetry.api` — `InternalAttributeKeyImpl`, `AttributeType`
- `io.opentelemetry.context` — context propagation
- `io.opentelemetry.instrumentation.api` — `MapBackedCache`
- `io.opentelemetry.instrumentation.logback` — the logback appender

**Do not add `io.opentelemetry.instrumentation.spring`.** It contains CGLIB
proxy classes that cannot be build-time initialized; including it breaks the
build.

**Why not just bump OTel?** The version catalog declares `2.26.1`, which *does*
ship native metadata, but bumping fails at AOT time: 2.26.1 expects
`io.opentelemetry.common.ComponentLoader`, absent from the OTel SDK version
managed by Spring Boot 3.5.x. The bump is deferred until Spring Boot's managed
OTel SDK and the instrumentation BOM line up. The OTLP exporter is only wired in
the `http` profile, so the `stdio` native image never exercises its reflection
surface anyway.

The **native test binary** needs a few extra entries beyond the shared args
(see the `named("test")` block): `io.opentelemetry.sdk` (a build-time
`ServiceLoader` provider), `--initialize-at-run-time` for
`AndroidFriendlyRandomHolder` (it seeds a `java.util.Random` in `<clinit>`,
which GraalVM forbids in the image heap), and the JUnit Platform launcher/engine
packages (the native JUnit launcher embeds the test plan in the image heap).

## Security and profiles under native

Security stays globally available so the JVM `http` profile is unaffected — the
`@SpringBootApplication` on `Main` is **never** modified. Instead, the `stdio`
profile keeps security out of the AOT graph through configuration that already
exists for the JVM build:

- `application-stdio.properties` excludes `SecurityAutoConfiguration` and
  `ManagementWebSecurityAutoConfiguration` via `spring.autoconfigure.exclude`.
- `HttpSecurityConfiguration` and `MethodSecurityConfiguration` are
  `@Profile("http")`.

Because `processAot` runs with the pinned profile, the `stdio` native binary
captures the bean graph *with* those exclusions, and the security/OAuth2 classes
never enter its closed world.

## Building and running

```bash
# Local native binary (host OS; needs GraalVM JDK 25)
./gradlew nativeCompile -Pnative

# Native Docker images (compiled inside a Paketo Linux builder; any host OS)
./gradlew bootBuildImage -Pnative                      # solr-mcp:<v>-native-stdio
./gradlew bootBuildImage -Pnative -Pprofile=http       # solr-mcp:<v>-native-http

# Run
docker run -i --rm \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native-stdio
docker run -p 8080:8080 --rm -e PROFILES=http \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native-http
```

## Testing

- **`./gradlew nativeTest -Pnative`** runs the integration test suite compiled
  as a native image — the truest proof that the closed world is complete. It is
  deliberately **not** part of `./gradlew build` (a native compile per run is
  slow); it runs in its own CI job.
- **`./gradlew dockerIntegrationTest -Pnative`** builds the native-stdio image
  and re-runs the STDIO black-box scenarios against it;
  `-Pnative -Pprofile=http` does the same for the native-http image and its HTTP
  scenarios. The native-stdio image skips the HTTP test (no servlet beans in its
  closed world) and vice versa. See the Image × Mode test-coverage table in
  `AGENTS.md`.

Note that Mockito-based unit tests are `@DisabledInNativeImage` — ByteBuddy
proxies don't survive GraalVM's closed-world assumption.

## Benchmarking

`scripts/benchmark-native.sh` (Linux/CI) builds the JVM and native images and
compares them on the metrics that matter for the STDIO use case: image size on
disk, cold-start time, idle RSS after startup, and RSS after one `search` call.
Each measurement is the median of several runs; results are written to
`docs/specs/benchmark-results.md` (a generated artifact, not checked in).

The native image is considered a win when **all** hold: startup ≤ 25% of JVM,
idle RSS ≤ 50% of JVM, image size ≤ 60% of JVM. If a threshold misses, keep the
numbers and the opt-in flag and document the gap rather than blocking.

## CI

A separate `native.yml` workflow exercises the native path on PRs that touch
native-related files (this doc, `build.gradle.kts`, `gradle/libs.versions.toml`,
`scripts/benchmark-native.sh`, the workflow itself). It runs
`dockerIntegrationTest` over a `[stdio, http]` matrix so both native variants are
covered, and provides multi-arch images via a build matrix. **Native failures do
not block JVM-path merges** — the default PR build (`./gradlew build`) stays
JVM-only and fast.

## Known limitations and follow-ups

- **NOT CURRENTLY SHIPPING.**  Right now we don't as a project yet use the native code (or any code) to ship Docker based image.
- **OTel BOM bump blocked.** Stuck on 2.11.0 (no native metadata, worked around
  with build-time init) until Spring Boot's managed OTel SDK aligns with the
  2.26.x instrumentation BOM. Revisit on Spring Boot upgrades.
- **Native compile is resource-hungry.** Expect ~4–8 GB RAM per compile; ensure
  CI runners and dev boxes have headroom.
- **Paketo builder download.** First `bootBuildImage` run pulls a ~1 GB builder;
  CI caching mitigates it.
- **`mcp-server-security`.** Small, non-Spring-official library. If it ever
  grows an eager `@Configuration` that loads outside `@Profile("http")`, it
  could pull security classes into the STDIO AOT graph — watch for it when
  upgrading.
- **Profile-Guided Optimization (PGO)** and publishing native images to a public
  registry from CI are not done yet.
