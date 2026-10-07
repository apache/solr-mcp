# Changelog

## [Unreleased]

Apache Solr MCP 1.0.0 is the project's first release: a Model Context Protocol (MCP) server that lets AI assistants search, index and manage Apache Solr. It runs over STDIO for desktop and IDE clients, or over HTTP for remote use with OAuth2 authentication on by default. It is built on Spring Boot 4.1 and Spring AI 2.0, works with Solr 9.4 through 10, and ships as a JAR (Java 25), a multi-architecture JVM Docker image and GraalVM native images.

### Added

- **Search**: the `search` tool runs full-text queries with filter queries, faceting, sorting and pagination.
- **Indexing in four formats**: `index-json-documents`, `index-csv-documents`, `index-xml-documents` (Solr update XML; only `<add>` is accepted, so the tool cannot delete documents or issue commits) and `index-markdown-documents`, which extracts front matter, title, headings and body text from each document.
- **Collection management**: `list-collections`, `create-collection`, `check-health` and `get-collection-stats`, plus alias management with `list-aliases`, `create-alias` and `delete-alias`.
- **Schema inspection and additive changes**: `get-schema`, `add-fields` and `add-field-types`. The schema tools only add; they never change or remove existing fields or field types.
- **Prompts, resources and completions**: guided prompts (`explore-collections`, `setup-collection`, `search-collection`, `index-data`, `view-schema`, `design-schema`), the `solr://collections` and `solr://{collection}/schema` resources, and collection-name completion for their arguments.
- **STDIO and HTTP transports**: STDIO is the default; `PROFILES=http` serves stateless streamable HTTP.
- **OAuth2 security for HTTP, on by default**: JWT bearer tokens from the issuer in `OAUTH2_ISSUER_URI`, with audience validation and configurable CORS origins (`MCP_CORS_ALLOWED_ORIGINS`). Setup guides cover Auth0 and Keycloak.
- **Docker images**: `apache/solr-mcp`, a JVM image (amd64 and arm64) that serves both transports, and GraalVM native images for each transport (`<version>-native-stdio`, `<version>-native-http`) on GHCR.
- **Observability in HTTP mode**: OpenTelemetry traces, metrics and logs exported over OTLP/HTTP, with a span for every tool call and logs correlated to the trace they were written in.
- **Software bill of materials and license disclosure**: a CycloneDX SBOM embedded in the JAR and every image and served at `/actuator/sbom/application` in HTTP mode, and a binary `LICENSE`/`NOTICE` that lists every bundled dependency.
- **Client setup guides** for Claude Desktop, Claude Code, Codex, ChatGPT, VS Code, Cursor, JetBrains, Zed and MCP Inspector.

### Changed

### Fixed

### Security

[Unreleased]: https://github.com/apache/solr-mcp/commits
