# Data Model: GraalVM Native Docker Image

**Feature**: 001-graalvm-native-image
**Date**: 2026-03-08

This feature introduces no persistent data entities. The "entities" in scope are build-time artifacts and their configuration contracts.

---

## Build Artifacts

### Native Executable

| Property | Value |
|----------|-------|
| **Name** | `solr-mcp` (binary) |
| **Produced by** | `./gradlew nativeCompile` |
| **Output path** | `build/native/nativeCompile/solr-mcp` |
| **Target OS** | Linux (amd64 or arm64 — one per build) |
| **Dependencies** | glibc, libz, OpenSSL (dynamically linked) |
| **State** | Immutable after compilation |

### Native Docker Image

| Property | Value |
|----------|-------|
| **Base image** | `gcr.io/distroless/base-debian12` |
| **Platforms** | `linux/amd64`, `linux/arm64` (separate builds, combined manifest) |
| **Tag format** | `solr-mcp:<version>-native`, `solr-mcp:latest-native` |
| **Entrypoint** | `/app/solr-mcp` |
| **Exposed port** | `8080` (HTTP mode only; STDIO mode uses no ports) |
| **Image size target** | Smaller than the JVM-based image |

---

## Configuration Model

### Environment Variables (Runtime Contract — unchanged from JVM image)

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `SOLR_URL` | `http://localhost:8983/solr/` | No | Solr base URL |
| `PROFILES` | `stdio` | No | Transport mode: `stdio` or `http` |
| `OAUTH2_ISSUER_URI` | _(none)_ | HTTP mode only | OAuth2 token issuer |

### Build Configuration Entities

**GraalVm Build Config** (in `build.gradle.kts`):

| Field | Value | Notes |
|-------|-------|-------|
| `plugin` | `org.graalvm.buildtools.native:0.11.4` | Added to plugins block |
| `mainClass` | `org.apache.solr.mcp.server.Main` | Explicit to skip AOT scanning |
| `imageName` | `solr-mcp` | Matches binary name used by Jib extension |
| `requiredVersion` | `25` | Enforces GraalVM 25+ toolchain |
| `buildArgs` | `[-H:+ReportExceptionStackTraces]` | Dev/debug aid |

**Jib Native Extension Config** (in `build.gradle.kts`):

| Field | Value | Notes |
|-------|-------|-------|
| `plugin` | `jib-native-image-extension-gradle:0.1.0` | Official Jib extension |
| `imageName` | `solr-mcp` | Matches GraalVM output binary name |
| `fromImage` | `gcr.io/distroless/base-debian12` | Native-appropriate base |
| `toImage` | `solr-mcp:<version>-native` | Separate tag from JVM image |
| `task dependency` | `nativeJibDockerBuild` depends on `nativeCompile` | Explicit Gradle wiring |

---

## Runtime Hints Configuration

The following hint files are introduced to enable native image compilation:

### Source: Automatic (Spring Boot AOT)

Generated in `build/generated/aotClasses/` — not committed to source.

Covers: Spring framework proxies, `@McpTool` registrations, Spring AI MCP schema types, Micrometer, `@Observed` aspects.

### Source: Custom RuntimeHints class

**Location**: `src/main/java/org/apache/solr/mcp/server/config/GraalVmRuntimeHints.java`

| Class Registered | Reason |
|-----------------|--------|
| `org.apache.solr.common.SolrDocument` | SolrJ field-level reflection |
| `org.apache.solr.common.SolrDocumentList` | SolrJ field-level reflection |
| `org.apache.solr.common.util.SimpleOrderedMap` | SolrJ serialization |
| `org.apache.solr.common.util.NamedList` | SolrJ serialization |
| `org.apache.solr.client.solrj.impl.HttpJdkSolrClient` | HTTP client discovery |
| `org.apache.solr.client.solrj.response.QueryResponse` | Response binding |
| `org.apache.solr.mcp.server.search.SearchResponse` | MCP tool return type |

### Source: Generated JSON hint files (via native-image agent)

**Location**: `src/main/resources/META-INF/native-image/org/apache/solr/mcp/`

Files produced by running the application under the GraalVM tracing agent while exercising all MCP tool paths:

| File | Covers |
|------|--------|
| `reflect-config.json` | SolrJ HTTP client internals, XML parser, JWT classes |
| `resource-config.json` | XML parser service loaders, i18n bundles |
| `proxy-config.json` | Dynamic proxies (OAuth2 security) |

---

## State Transitions

```
Source Code
    │
    ▼ ./gradlew nativeCompile
Native Executable (build/native/nativeCompile/solr-mcp)
    │
    ▼ ./gradlew nativeJibDockerBuild
Native Docker Image (local daemon, tag: solr-mcp:<version>-native)
    │
    ▼ ./gradlew nativeJib -Djib.to.image=...
Native Docker Image (remote registry)
    │
    ▼ docker run
Running Native MCP Server
```

---

## Relationship to Existing JVM Image

| Aspect | JVM Image | Native Image |
|--------|-----------|--------------|
| Build task | `./gradlew jibDockerBuild` | `./gradlew nativeJibDockerBuild` |
| Base image | `eclipse-temurin:25-jre` | `gcr.io/distroless/base-debian12` |
| Tag format | `solr-mcp:<version>` | `solr-mcp:<version>-native` |
| Startup time | Several seconds | < 1 second |
| Memory at idle | Baseline | < 50% of JVM baseline |
| Platform build | Single multi-arch build | Separate per-arch build + manifest merge |
| MCP tool surface | All 4 tool categories | All 4 tool categories (identical) |
| Transport modes | STDIO + HTTP | STDIO + HTTP |
| STDIO purity | ✓ | ✓ |
