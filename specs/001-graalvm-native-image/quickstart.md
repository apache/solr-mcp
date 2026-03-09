# Quickstart: GraalVM Native Docker Image

**Feature**: 001-graalvm-native-image
**Audience**: Build engineers, operators, and contributors

---

## Prerequisites

| Requirement | Version | Check |
|-------------|---------|-------|
| GraalVM JDK | 25+ | `java -version` → must show GraalVM |
| Docker | 24+ | `docker --version` |
| Gradle wrapper | 8.x | included in repo (`./gradlew`) |

> **GraalVM Installation**: Download from [graalvm.org](https://www.graalvm.org/downloads/) or use [SDKMAN](https://sdkman.io): `sdk install java 25-graalce`. Set `JAVA_HOME` to the GraalVM installation.

---

## Build the Native Executable

```bash
# Compile to a native executable (requires GraalVM on JAVA_HOME)
./gradlew nativeCompile

# Output: build/native/nativeCompile/solr-mcp
```

First build takes several minutes (AOT + native compilation). Subsequent builds are faster when source hasn't changed.

---

## Build the Native Docker Image

```bash
# Build native Docker image (local daemon)
./gradlew nativeJibDockerBuild

# Image is tagged: solr-mcp:<version>-native and solr-mcp:latest-native
docker images | grep solr-mcp
```

---

## Run the Native Docker Image

### STDIO Mode (Claude Desktop)

```bash
docker run --rm -i \
  -e SOLR_URL=http://host.docker.internal:8983/solr/ \
  solr-mcp:latest-native
```

### HTTP Mode (MCP Inspector / Remote)

```bash
docker run --rm -p 8080:8080 \
  -e PROFILES=http \
  -e SOLR_URL=http://host.docker.internal:8983/solr/ \
  solr-mcp:latest-native
```

Then open `http://localhost:8080` with MCP Inspector.

---

## Run Tests Against Native Image

```bash
# Build native image first
./gradlew nativeJibDockerBuild

# Run Docker integration tests against native image
./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native
```

---

## Push to Registry

```bash
# Docker Hub
./gradlew nativeJib -Djib.to.image=docker.io/<username>/solr-mcp:<version>-native

# GitHub Container Registry
./gradlew nativeJib -Djib.to.image=ghcr.io/<org>/solr-mcp:<version>-native
```

---

## Troubleshooting

### "GraalVM required" error during nativeCompile
Your `JAVA_HOME` points to a standard JDK, not GraalVM. Switch:
```bash
sdk use java 25-graalce   # SDKMAN
# or
export JAVA_HOME=/path/to/graalvm-jdk-25
```

### "Missing reflection hint" error at runtime
A SolrJ or library class wasn't captured in hint files. Run the tracing agent:
```bash
./gradlew bootRun -Dspring.aot.enabled=true -Dspring.profiles.active=http \
  -Dagentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/generated/
# Exercise all MCP tools, then Ctrl+C
# Review generated hint files and merge into src/main/resources/META-INF/native-image/org/apache/solr/mcp/
```

### Native image starts but Solr calls fail
Check that all required SolrJ classes are in `reflect-config.json`. Look for `ReflectException` in container logs (redirect stderr: `docker run 2>err.log ...`).

### Build succeeds on amd64 but not arm64
Native image is not cross-platform. Build arm64 binary on an arm64 machine (Apple Silicon, AWS Graviton, etc.) or use GitHub Actions `ubuntu-latest-arm` runner.

---

## Performance Comparison

| Metric | JVM Image | Native Image |
|--------|-----------|--------------|
| Container ready | ~5–10 s | < 1 s |
| Idle memory | Baseline | < 50% of baseline |
| Image size | ~400 MB | ~30–60 MB |
| Build time | < 1 min | 5–10 min (first build) |

---

## Claude Desktop Configuration

After building the native image, update your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
        "solr-mcp:latest-native"
      ]
    }
  }
}
```
