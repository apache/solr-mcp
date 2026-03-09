# Implementation Plan: GraalVM Native Docker Image

**Branch**: `001-graalvm-native-image` | **Date**: 2026-03-08 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-graalvm-native-image/spec.md`

## Summary

Introduce GraalVM native image compilation for the Solr MCP Server, producing a Docker image with a statically-compiled native executable. The native image starts in under 1 second and uses less than 50% of the JVM image's memory at idle, while preserving all four MCP tool categories, both transport modes (STDIO and HTTP), multi-platform support (amd64 + arm64), and full Solr version compatibility (8.11–10).

The approach: add `org.graalvm.buildtools.native:0.11.4` Gradle plugin for compilation and the `jib-native-image-extension-gradle:0.1.0` Jib extension for packaging, using `gcr.io/distroless/base-debian12` as the base image. The JVM image build is preserved unchanged; the native image is an additional build target producing `solr-mcp:<version>-native` tagged images.

## Technical Context

**Language/Version**: Java 25 (GraalVM 25+, enforced via Gradle toolchain)
**Primary Dependencies**: Spring Boot 4.0.2, Spring AI 2.0.0-M2, SolrJ 10.0.0, `org.graalvm.buildtools.native:0.11.4`, `jib-native-image-extension-gradle:0.1.0`, Jib 3.4.5
**Storage**: N/A
**Testing**: JUnit 5, Testcontainers (Solr matrix), JaCoCo, existing `dockerIntegrationTest` task
**Target Platform**: Linux Docker containers, linux/amd64 and linux/arm64 (separate per-arch native builds)
**Project Type**: MCP server (CLI/service hybrid) with build system enhancement
**Performance Goals**: Container startup < 1s; idle memory < 50% of JVM baseline; image size smaller than JVM image
**Constraints**: STDIO stdout purity (MCP JSON-RPC only); multi-arch support; all existing integration tests must pass; JVM image unchanged

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Gate Question | Status |
|-----------|---------------|--------|
| I. MCP Protocol Integrity | Does this feature write anything to stdout outside of MCP JSON-RPC messages in STDIO mode? | ✓ No — native binary uses same Spring Boot bootstrap; distroless base adds no stdout |
| I. MCP Protocol Integrity | Are new tools exposed exclusively via `@McpTool` annotations? | ✓ N/A — no new tools introduced |
| II. Solr Version Compatibility | Have all version-specific Solr API calls been tested or gracefully degraded across versions 8.11, 9.4, 9.9, 9.10, 10? | ✓ Existing test matrix covers this; `dockerIntegrationTest` runs against all versions |
| III. Test-First Development | Are unit tests (`*Test.java`) written with mocked Solr, and integration tests (`*IntegrationTest.java`) with real Solr via Testcontainers? | ✓ Existing tests cover all tool behavior; `GraalVmRuntimeHints` needs a unit test |
| III. Test-First Development | Does `./gradlew build` pass with no test failures? | ✓ Native build is opt-in (`nativeCompile`); `./gradlew build` is unchanged |
| IV. Security by Default | Do sensitive operations (collection create/delete, schema modification) require authentication in HTTP mode? | ✓ No change to security model; native image uses same Spring Security configuration |
| V. Simplicity and YAGNI | Is every new abstraction justified by three or more callers / use cases? | ✓ `GraalVmRuntimeHints` registers multiple SolrJ types; no single-use abstractions |

**Post-design re-check**: All gates pass. The `nativeJibDockerBuild` task is a simple Gradle task delegation — not an abstraction. The `GraalVmRuntimeHints` class is a single-purpose Spring registration hook, which is the canonical Spring pattern (not a new abstraction).

## Project Structure

### Documentation (this feature)

```text
specs/001-graalvm-native-image/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: technology decisions
├── data-model.md        # Phase 1: build artifacts and configuration model
├── quickstart.md        # Phase 1: developer/operator quickstart
├── contracts/
│   ├── build-commands.md     # Build interface contract
│   └── docker-image-spec.md  # Runtime interface contract
└── checklists/
    └── requirements.md  # Spec quality checklist (all pass)
```

### Source Code (repository root)

```text
build.gradle.kts                    # Add GraalVM + Jib native extension plugins and tasks
gradle/libs.versions.toml           # Add graalvm-buildtools-native and jib-native-image-extension versions

src/main/java/org/apache/solr/mcp/server/
└── config/
    └── GraalVmRuntimeHints.java    # NEW: RuntimeHintsRegistrar for SolrJ + custom types

src/main/resources/
└── META-INF/
    └── native-image/
        └── org/apache/solr/mcp/
            ├── reflect-config.json     # NEW: SolrJ + XML + JWT reflection hints
            ├── resource-config.json    # NEW: XML service loader resources
            └── proxy-config.json       # NEW: OAuth2 dynamic proxies

src/test/java/org/apache/solr/mcp/server/
└── config/
    └── GraalVmRuntimeHintsTest.java   # NEW: Unit test for hints registration

.github/workflows/
└── native-image.yml    # NEW: CI workflow for per-arch native builds + manifest merge
```

**Structure Decision**: Single-project layout (no structural change). All new files are additive. The `config/` package already exists (or is created) as the natural home for Spring configuration classes.

## Complexity Tracking

> No Constitution violations requiring justification.

## Implementation Phases

### Phase A: Build Infrastructure (unblocks everything else)

**Deliverables**:
1. Add `graalvm-buildtools-native:0.11.4` to `gradle/libs.versions.toml` and `build.gradle.kts` plugins block.
2. Add `jib-native-image-extension-gradle:0.1.0` to version catalog and Jib plugin extension configuration.
3. Configure `graalvmNative` block: `imageName = "solr-mcp"`, `requiredVersion = "25"`, `mainClass = "org.apache.solr.mcp.server.Main"`, build arg `-H:+ReportExceptionStackTraces`.
4. Add `nativeJibDockerBuild` task: `dependsOn(tasks.nativeCompile)`, configure Jib `from.image = "gcr.io/distroless/base-debian12"` for native variant, `to.image = "solr-mcp:$version-native"`.
5. Strip JVM-specific `jvmFlags` (e.g., `-XX:+UseContainerSupport`) from the native Jib container config.
6. Verify `./gradlew nativeCompile` succeeds with Spring Boot AOT processing.

**Test gate**: `./gradlew nativeCompile` completes without error on the developer's GraalVM 25 machine.

---

### Phase B: Runtime Hints (unblocks native correctness)

**Deliverables**:
1. Write `GraalVmRuntimeHints.java` implementing `RuntimeHintsRegistrar`. Register: `SolrDocument`, `SolrDocumentList`, `SimpleOrderedMap`, `NamedList`, `HttpJdkSolrClient`, `QueryResponse`, `SearchResponse`, `JsonDocumentCreator`, `CsvDocumentCreator`, `XmlDocumentCreator`.
2. Annotate with `@ImportRuntimeHints(GraalVmRuntimeHints.class)` on a `@Configuration` class.
3. Run native-image tracing agent against the running application while exercising all MCP tool paths. Produce initial `reflect-config.json`, `resource-config.json`, `proxy-config.json`.
4. Review and curate generated files: remove ephemeral/build-time classes, keep runtime-needed entries.
5. Place curated files in `src/main/resources/META-INF/native-image/org/apache/solr/mcp/`.
6. Write `GraalVmRuntimeHintsTest.java` using `RuntimeHintsPredicates` to assert each registered type.

**Test gate**: `./gradlew nativeTest` passes (runs JUnit tests compiled to native).

---

### Phase C: Native Docker Image Integration Tests

**Deliverables**:
1. Update `dockerIntegrationTest` task (or add `nativeDockerIntegrationTest` task) to accept a configurable image name via system property `solr.mcp.image`.
2. Run existing Docker integration test suite (`DockerImageStdioIntegrationTest`, `DockerImageHttpIntegrationTest`) against the native image.
3. Fix any failures caused by missing reflection hints (iterate Phase B).
4. Document the native integration test command in `CLAUDE.md`.

**Test gate**: `./gradlew nativeJibDockerBuild && ./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native` passes for all Solr versions in the matrix.

---

### Phase D: CI Workflow

**Deliverables**:
1. Add `.github/workflows/native-image.yml` with:
   - Two matrix jobs: `native-amd64` (ubuntu-latest) and `native-arm64` (ubuntu-latest-arm).
   - Each job: set up GraalVM 25, run `./gradlew nativeJibDockerBuild`, push arch-specific image to registry.
   - `native-manifest` job: merge arch images into multi-arch manifest with `docker buildx imagetools create`.
2. Ensure `./gradlew build` (JVM) CI job is unmodified and still runs in parallel.
3. Trigger native CI on release branches and nightly; not on every PR (build time ~10 min per arch).

**Test gate**: CI workflow completes successfully on both architectures and produces a working multi-arch manifest.

---

### Phase E: Constitution Amendment

**Deliverables**:
1. Open a PR adding GraalVM native image compilation to the Technology Stack section of `.specify/memory/constitution.md`:
   ```
   - **Native Image**: GraalVM 25+ with `org.graalvm.buildtools.native` Gradle plugin;
     native images tagged `solr-mcp:<version>-native`; packaged via Jib native image extension
   ```
2. Increment constitution to version 1.1.0 (new section added — MINOR bump per governance rules).
3. Run the constitution consistency propagation checklist against all `.specify/templates/` files.

**Test gate**: PR approved by at least one other contributor per governance rules.

---

### Phase F: Documentation

**Deliverables**:
1. Update `CLAUDE.md`:
   - Add `./gradlew nativeCompile` and `./gradlew nativeJibDockerBuild` to Common Commands section.
   - Add `./gradlew nativeTest` (runs tests as native executable).
   - Add native Docker integration test command.
   - Note GraalVM 25 toolchain requirement.
2. Update `CLAUDE.md` Solr 10 Compatibility section to note native image test coverage.

**Test gate**: `./gradlew build` still passes (spotlessCheck on CLAUDE.md changes).

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| SolrJ has uncaptured reflection paths | High | High | Native-image agent tracing; iterative `nativeTest` runs |
| Spring AI 2.0.0-M2 AOT gaps (pre-release) | Medium | High | Thorough `nativeTest`; file upstream issues if found |
| arm64 CI runner availability | Medium | Medium | Use GitHub Actions `ubuntu-latest-arm` (available since 2024) |
| OAuth2 JWT reflection missing | Medium | Medium | Explicit `reflect-config.json` entries researched; test HTTP mode in CI |
| Native build time in CI (>10 min) | High | Low | Trigger on release/nightly only; parallel per-arch jobs |

## Post-Design Constitution Check

All five principles verified against the Phase 1 design:

- **I. MCP Protocol Integrity**: Distroless base + native binary produce no startup stdout. `@McpTool` surface unchanged. ✓
- **II. Solr Version Compatibility**: Docker integration tests run against all five Solr versions; same test matrix applies to native image. ✓
- **III. Test-First Development**: `GraalVmRuntimeHintsTest` written before implementation (Phase B); `nativeTest` task provides native compilation regression safety. ✓
- **IV. Security by Default**: Spring Security configuration is unchanged; OAuth2 flows through the same code path in native mode. ✓
- **V. Simplicity and YAGNI**: One new class (`GraalVmRuntimeHints`), one new task (`nativeJibDockerBuild`), one new CI workflow, three hint JSON files. No unnecessary abstractions. ✓
