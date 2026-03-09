# Research: GraalVM Native Docker Image

**Feature**: 001-graalvm-native-image
**Date**: 2026-03-08
**Status**: Complete — all NEEDS CLARIFICATION resolved

---

## Decision 1: GraalVM Version

**Decision**: GraalVM for JDK 25 (GraalVM 25, latest patch 25.0.1+8)
**Rationale**: The project targets Java 25+. GraalVM 25 fully supports Java 25 language features and the baseline VM. It is the only GraalVM release compatible with the project's JDK requirement.
**Alternatives considered**: GraalVM 21 LTS — incompatible with Java 25 APIs used in the project.

---

## Decision 2: GraalVM Gradle Plugin

**Decision**: `org.graalvm.buildtools.native` version **0.11.4** (latest stable as of 2026-03)
**Rationale**: This is the official first-party GraalVM Native Build Tools plugin for Gradle. It integrates with Spring Boot's AOT processor automatically — no additional wiring needed. It provides `nativeCompile` and `nativeTest` tasks.
**Alternatives considered**: Manual `native-image` CLI invocation — rejected as it bypasses Spring AOT processing and is not repeatable across environments.

---

## Decision 3: Container Packaging Tool

**Decision**: Jib with the **`jib-native-image-extension-gradle`** official extension (v0.1.0)
**Rationale**: The project already uses Jib to avoid Docker daemon stdout pollution that breaks STDIO mode. The official Jib native image extension handles: (a) detecting the native binary in `build/native/nativeCompile/`, (b) setting the correct entrypoint, (c) making the binary executable, and (d) wiring the `nativeCompile` → Jib task dependency automatically.
**Alternatives considered**:
- Spring Boot Buildpacks (`bootBuildImage`) — **rejected**: writes build output to stdout, violating MCP Protocol Integrity (Constitution I).
- Traditional Dockerfile — rejected: requires a running Docker daemon and manual task wiring; loses Jib's reproducibility guarantees.
- Kaniko — rejected: overkill for this project's CI setup; adds Kubernetes dependency.

---

## Decision 4: Container Base Image

**Decision**: `gcr.io/distroless/base-debian12` (amd64 and arm64 variants)
**Rationale**: Spring Boot native executables are dynamically linked (require glibc, SSL certificates, DNS). Distroless provides exactly these without a full OS shell or package manager. It is ~20 MiB vs. ~400 MiB for eclipse-temurin:25-jre, achieving a significantly smaller image. Critically, distroless images do not write to stdout during container start — maintaining STDIO purity.
**Alternatives considered**:
- `gcr.io/distroless/static-debian12` — rejected: static-only; Spring Boot native apps use dynamic linking (glibc, libz, etc.).
- `debian:12-slim` — rejected: ~80 MiB; includes shell and package manager (unnecessary attack surface).
- `scratch` — rejected: requires fully static binary; Spring Boot native is dynamically linked by default.

---

## Decision 5: Multi-Platform Strategy

**Decision**: Separate native builds per architecture (amd64 and arm64), combined into a multi-arch manifest in CI.
**Rationale**: GraalVM native-image does NOT support cross-compilation. Each architecture's binary must be compiled on (or emulated by) that architecture's CPU. QEMU emulation of arm64 on amd64 for native compilation is prohibitively slow (~2–4 hours). The CI matrix strategy (one job per arch → manifest merge) is the standard approach for GraalVM projects.
**Alternatives considered**:
- QEMU cross-compilation — rejected: build times are impractical (>2h per platform).
- Single-architecture only — rejected: the spec requires both amd64 and arm64 (FR-007).
- Docker buildx — not applicable: buildx supports multi-stage Dockerfiles but not GraalVM cross-compilation.

**CI impact**: Requires two CI runners (ubuntu-latest amd64 + ubuntu-latest-arm64) and a manifest merge step. This is a known pattern for multi-arch native image projects (e.g., Quarkus, Micronaut CI).

---

## Decision 6: Native Image Build Tasks

**Decision**: Add a dedicated `nativeJibDockerBuild` Gradle task separate from `jibDockerBuild`.
**Rationale**: Keeping the JVM Docker image build (`jibDockerBuild`) and the native Docker image build (`nativeJibDockerBuild`) as separate tasks ensures: (a) the existing JVM workflow is unchanged, (b) the native build is opt-in during the transition period, (c) CI can run both independently.
**Alternatives considered**:
- Replace `jibDockerBuild` with native — rejected: removes the JVM image fallback and risks breaking existing deployments.
- Single task that produces both — rejected: unnecessary complexity; Jib produces one image variant per invocation.

---

## Decision 7: Reflection Hints Strategy

**Decision**: Three-layer strategy — (1) automatic Spring Boot AOT, (2) custom `GraalVmRuntimeHints` class, (3) generated JSON hint files from native-image agent for SolrJ.
**Rationale**: Spring Boot 4.0's AOT processor automatically covers Spring framework classes, MCP tools (`@McpTool`), and most Spring AI/Web/Security classes. SolrJ has no published native image metadata (confirmed: no `META-INF/native-image/` in SolrJ 10.0.0 JAR). XML parsing (DocumentBuilderFactory) and OAuth2 JWT classes need explicit registration.
**Alternatives considered**:
- All-JSON hint files — rejected: breaks with library upgrades; Spring AOT is more maintainable.
- Skip SolrJ hints (trial and error) — rejected: causes silent runtime failures on tool invocations.

### Compatibility Matrix

| Library | Version | Native Status | Action |
|---------|---------|---------------|--------|
| SolrJ | 10.0.0 | ⚠ No published hints | Generate via native-image agent + `GraalVmRuntimeHints` |
| Spring AI MCP | 2.0.0-M2 | ✓ `McpHints` class present | No action |
| Spring Boot Web/MVC | 4.0.2 | ✓ AOT automatic | No action |
| Spring Security OAuth2 | 4.0.2 | ⚠ JWT reflection gaps | Add reflect-config.json entries |
| tools.jackson | bundled | ⚠ Vendored; verify metadata | Test; add hints if needed |
| XML (DocumentBuilder) | JDK built-in | ⚠ Service loader pattern | Add resource-config.json |
| Commons CSV | 1.10.0 | ✓ No reflection | No action |
| OpenTelemetry | 2.21.0-alpha | ⚠ Mostly covered | Verify at test time |
| Spring AspectJ | 4.0.2 | ✓ AOT handles `@Observed` | No action |
| Micrometer | bundled | ✓ Full AOT support | No action |

**Primary risk**: SolrJ runtime failures due to missing hints. The native-image agent tracing approach (run the app under the agent while executing all MCP tools) is the accepted mitigation.

---

## Decision 8: Native Image Build Output Tag Strategy

**Decision**: Native images tagged with `-native` suffix (e.g., `solr-mcp:0.0.2-SNAPSHOT-native`). JVM image tag unchanged.
**Rationale**: Allows parallel availability of both image variants without a breaking change to existing deployments. Operators can explicitly opt into the native image. Tag strategy is consistent with ecosystem conventions (e.g., Quarkus, Spring Native projects use `-native` or `-distroless` suffixes).
**Alternatives considered**:
- Replace JVM image — rejected: breaks existing users; not reversible if native image has gaps.
- Same tag, different digest — rejected: misleading; users cannot choose between variants.

---

## Resolved Unknowns from Technical Context

| Unknown | Resolution |
|---------|-----------|
| Which GraalVM for Java 25? | GraalVM 25 (25.0.1+8) |
| Plugin version? | `org.graalvm.buildtools.native:0.11.4` |
| Jib + native executable? | Official `jib-native-image-extension-gradle:0.1.0` |
| Multi-platform native? | Separate CI jobs per arch + manifest merge |
| SolrJ native support? | No upstream metadata; requires generated hints |
| Spring AI 2.0.0-M2 native? | ✓ `McpHints` class covers MCP tool surface |
| nativeCompile output path? | `build/native/nativeCompile/solr-mcp` (Linux) |
