# Build Commands Contract: GraalVM Native Docker Image

**Feature**: 001-graalvm-native-image
**Type**: Build Interface Contract

This document defines the build command surface — commands that build engineers and CI pipelines use to produce, test, and publish the native Docker image.

---

## Native Image Build Commands

| Command | Input | Output | Notes |
|---------|-------|--------|-------|
| `./gradlew nativeCompile` | Source code | `build/native/nativeCompile/solr-mcp` (binary) | Requires GraalVM 25 JDK on PATH |
| `./gradlew nativeJibDockerBuild` | Source code | Native Docker image in local daemon | Chains `nativeCompile` → Jib packaging |
| `./gradlew nativeJib -Djib.to.image=<registry>/<image>:<tag>` | Source code | Pushes native image to remote registry | Requires registry credentials |

---

## Unchanged JVM Build Commands

These commands continue to work without modification:

| Command | Output |
|---------|--------|
| `./gradlew build` | JAR + tests (no native compilation) |
| `./gradlew jibDockerBuild` | JVM Docker image (tag: `solr-mcp:<version>`) |
| `./gradlew jib -Djib.to.image=...` | JVM image pushed to registry |

---

## Image Tag Convention

| Image Type | Local Tag | Registry Tag |
|------------|-----------|--------------|
| JVM image | `solr-mcp:<version>` | `<registry>/solr-mcp:<version>` |
| Native image | `solr-mcp:<version>-native` | `<registry>/solr-mcp:<version>-native` |
| Native latest | `solr-mcp:latest-native` | `<registry>/solr-mcp:latest-native` |

---

## Docker Integration Test Command

```bash
# Build native image first, then run Docker integration tests
./gradlew nativeJibDockerBuild
./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native
```

---

## CI Matrix Contract

The multi-arch native image requires two CI jobs:

| Job | Runner | Produces |
|-----|--------|---------|
| `native-amd64` | `ubuntu-latest` (x86_64) | `<registry>/solr-mcp:<version>-native-amd64` |
| `native-arm64` | `ubuntu-latest-arm` (arm64) | `<registry>/solr-mcp:<version>-native-arm64` |
| `native-manifest` | Any (after both above) | Multi-arch manifest `solr-mcp:<version>-native` |

The manifest job runs `docker manifest create` / `docker buildx imagetools create` to combine per-arch images.

---

## Required Toolchain

| Tool | Version | Where Configured |
|------|---------|-----------------|
| GraalVM JDK | 25+ | `JAVA_HOME` or Gradle toolchain |
| Gradle | 8.x | `./gradlew` wrapper |
| Docker (for local build) | 24+ | `DOCKER_EXECUTABLE` env var or `PATH` |

---

## Platform Limitations

- Native image compilation is **not cross-platform**: an amd64 host produces an amd64 binary only.
- QEMU emulation can compile arm64 on amd64 but is impractical (build time >2 hours).
- The JVM image (`jibDockerBuild`) retains multi-platform capability in a single command.
