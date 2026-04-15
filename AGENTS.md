# AGENTS.md

This file provides guidance to AI coding assistants when working with code in this repository.

## Project Overview

Solr MCP Server is a Spring AI Model Context Protocol (MCP) server that enables AI assistants to interact with Apache Solr. It provides tools for searching, indexing, and managing Solr collections through the MCP protocol.

- **Status:** Apache incubating project (v0.0.2-SNAPSHOT)
- **Java:** 25+ (centralized in build.gradle.kts)
- **Framework:** Spring Boot 3.5.8, Spring AI 1.1.2
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

### Why Jib Instead of Spring Boot Buildpacks

Spring Boot Buildpacks output logs to stdout, breaking MCP's STDIO protocol. Jib produces clean images with no stdout pollution, plus faster builds and multi-platform support (amd64/arm64).

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
