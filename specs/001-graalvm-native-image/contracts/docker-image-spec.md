# Docker Image Contract: GraalVM Native Image

**Feature**: 001-graalvm-native-image
**Type**: Runtime Interface Contract

This document defines the runtime interface of the native Docker image — what operators can rely on when deploying it.

---

## Image Specification

| Property | Value |
|----------|-------|
| **Base image** | `gcr.io/distroless/base-debian12` |
| **Entrypoint** | `/app/solr-mcp` (native binary) |
| **Platforms** | `linux/amd64`, `linux/arm64` |
| **Exposed port** | `8080` (HTTP mode only) |
| **Shell** | None (distroless) |
| **JVM runtime** | None required |

---

## Environment Variables

All environment variables from the JVM image are supported without change:

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `SOLR_URL` | `http://localhost:8983/solr/` | No | Solr base URL including trailing slash |
| `PROFILES` | `stdio` | No | Transport: `stdio` or `http` |
| `OAUTH2_ISSUER_URI` | _(none)_ | HTTP mode only | OAuth2 token issuer URL |

**Note**: JVM-specific variables (`JAVA_OPTS`, `-XX:` flags) have no effect in the native image.

---

## Transport Modes

### STDIO Mode (default)

```bash
docker run --rm -i \
  -e SOLR_URL=http://solr:8983/solr/ \
  solr-mcp:<version>-native
```

- Reads MCP JSON-RPC from stdin
- Writes MCP JSON-RPC to stdout only (no startup banners)
- Suitable for Claude Desktop integration

### HTTP Mode

```bash
docker run --rm -p 8080:8080 \
  -e PROFILES=http \
  -e SOLR_URL=http://solr:8983/solr/ \
  -e OAUTH2_ISSUER_URI=https://your-issuer.com \
  solr-mcp:<version>-native
```

- Listens on port 8080
- Requires OAuth2 JWT bearer token for sensitive operations
- Suitable for MCP Inspector and remote access

---

## MCP Tool Surface (unchanged)

The native image exposes the same four tool categories as the JVM image:

| Tool Category | Service Class | Operations |
|--------------|---------------|------------|
| Search | SearchService | Full-text search, faceting, filtering, sorting, pagination |
| Indexing | IndexingService | JSON, CSV, XML document ingestion |
| Collection Management | CollectionService | List collections, stats, health |
| Schema Introspection | SchemaService | Fields, types, schema info |

All tools produce byte-for-byte identical responses to the JVM image when given the same inputs.

---

## Health and Observability

| Endpoint | Available in STDIO | Available in HTTP |
|----------|--------------------|-------------------|
| `/actuator/health` | No | Yes |
| `/actuator/info` | No | Yes |
| `/actuator/metrics` | No | Yes |
| OpenTelemetry metrics export | Configurable | Configurable |

---

## Startup Guarantee

The server is ready to accept its first MCP connection within **1 second** of container start (measured from `docker run` invocation to first successful tool response on the same machine).

---

## Compatibility Guarantee

The native image is tested against all supported Solr versions:
- Solr 8.11
- Solr 9.4
- Solr 9.9
- Solr 9.10
- Solr 10

Behavior across Solr versions is identical to the JVM image.
