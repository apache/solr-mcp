# Changelog

## [Unreleased]

Apache Solr MCP 1.0.0 is the project's first release: a Model Context Protocol (MCP) server that lets AI assistants search, index and manage Apache Solr. It runs over STDIO for desktop and IDE clients, or over HTTP for remote use with OAuth2 authentication on by default. It is built on Spring Boot 4.2 and Spring AI 2.1, works with Solr 9.4 through 10, and ships as a JAR (Java 25), a multi-architecture JVM Docker image and GraalVM native images.

### Added

- **Search**: the `search` tool runs full-text queries with filter queries, faceting, sorting and pagination.
- **Indexing in four formats**: `index-json-documents`, `index-csv-documents`, `index-xml-documents` (Solr update XML; only `<add>` is accepted, so the tool cannot delete documents or issue commits) and `index-markdown-documents`, which extracts front matter, title, headings and body text from each document.
- **Collection management**: `list-collections`, `create-collection`, `check-health` and `get-collection-stats`, plus alias management with `list-aliases`, `create-alias` and `delete-alias`.
- **Schema inspection and additive changes**: `get-schema`, `list-fields` (the fields that hold data, from Solr's Luke handler), `add-fields` and `add-field-types`. The schema tools only add; they never change or remove existing fields or field types.
- **Prompts, resources and completions**: guided prompts (`explore-collections`, `setup-collection`, `search-collection`, `index-data`, `view-schema`, `design-schema`), the `solr://collections`, `solr://{collection}/schema` and `solr://{collection}/fields` resources, and collection-name completion for their arguments.
- **Typed tool contracts**: every tool, resource, prompt and completion takes and returns types the server defines, never Solr's own response shapes. Tools that return an object publish an MCP `outputSchema` and return `structuredContent` alongside the same JSON as text, and no result carries a `success` or `message` status field or a timestamp from the server's clock. Prompts return their description and a `user` message, resources return `application/json` contents, and completions report how many values matched. The outputs and inputs that changed during the 1.0.0 development cycle:
  - `search`: each hit in `documents` is `{id, score, fields}`, with `id` read from the collection's uniqueKey field, and `facets` is a list of `{field, values: [{value, count}]}` in Solr's count order.
  - `index-json-documents`, `index-csv-documents`, `index-xml-documents` and `index-markdown-documents` return `{collection, format}` instead of a sentence; JSON and Markdown add `documentsSubmitted`, `documentsIndexed`, `fieldNames` (up to 50) and `fieldCount`.
  - `get-schema` returns the schema's `fieldTypes`, `fields`, `dynamicFields` and `copyFields` with only the properties the server names, field-type settings under `parameters`, and analyzers as `charFilters`, `tokenizer` and `filters` components. `list-fields` returns a name-sorted list of `{name, type, flags, dynamicBase, docs}`, with Luke's flag letters decoded into names such as `docValues`.
  - `add-fields` takes a list of fields and `add-field-types` a list of field types, both in the shape `get-schema` reports, and both return `{collection, addedNames}`.
  - `list-collections` returns `[{name}]` instead of bare names. `create-collection` returns the `configSet`, `numShards` and `replicationFactor` it used. `get-collection-stats` returns `{index, query, caches, handlers}`. `check-health` returns `{collection, healthy, pingMillis, numDocs, reason}`, where `reason` is one of `COLLECTION_NOT_FOUND`, `SOLR_UNREACHABLE`, `PING_FAILED` and `QUERY_FAILED`, never Solr's exception text.
  - `list-aliases` returns `[{name, collections, properties}]`. `create-alias` takes `collections` as a list instead of a comma-joined string and returns the alias as Solr holds it after the call. `delete-alias` returns `{name}` and fails for an alias that does not exist.
- **Every tool failure is an `isError` result**: a tool that cannot do what was asked, Solr unreachable included, answers with a `tools/call` result whose `isError` is `true` and whose text says what went wrong, never with a JSON-RPC protocol error. When Solr rejects a request with its own error response, the text carries Solr's reason, so the model can correct its next call; an error page or other non-JSON answer is reported by its HTTP status alone; a missing collection names `list-collections`, a 401 or 403 says Solr denied access, and a timeout or connection failure says so in the server's own words. The Solr host, URLs and SolrJ's `Error from server at` wording never appear; the full exception goes to the server log.
- **STDIO and HTTP transports**: STDIO is the default; `PROFILES=http` serves stateless streamable HTTP.
- **MCP 2026-07-28 protocol, opt-in**: `MCP_PROTOCOL=modern` serves the newest MCP spec revision (no `initialize` handshake or session, `server/discover`, `Mcp-Method`/`Mcp-Name` headers) over HTTP or STDIO, with every tool, resource, prompt and completion. Built on the modern server in MCP Java SDK 2.2.0, the SDK's first GA release with this revision. The default stays on the 2025 revisions that current clients speak.
- **OAuth2 security for HTTP, on by default**: JWT bearer tokens from the issuer in `OAUTH2_ISSUER_URI`, with audience validation and configurable CORS origins (`MCP_CORS_ALLOWED_ORIGINS`). Setup guides cover Auth0 and Keycloak.
- **Docker images**: `apache/solr-mcp`, a JVM image (amd64 and arm64) that serves both transports, and GraalVM native images for each transport (`<version>-native-stdio`, `<version>-native-http`) on GHCR.
- **Observability in HTTP mode**: OpenTelemetry traces, metrics and logs exported over OTLP/HTTP, with a span for every tool call and logs correlated to the trace they were written in.
- **Software bill of materials and license disclosure**: a CycloneDX SBOM embedded in the JAR and every image and served at `/actuator/sbom/application` in HTTP mode, and a binary `LICENSE`/`NOTICE` that lists every bundled dependency.
- **Client setup guides** for Claude Desktop, Claude Code, Codex, ChatGPT, VS Code, Cursor, JetBrains, Zed and MCP Inspector.

### Changed

### Fixed

- A tool whose Solr call failed with SolrJ's checked `SolrServerException` or `IOException`, as every call does when Solr is unreachable, answered with a bare JSON-RPC `-32603 Internal error` instead of a tool error ([#213](https://github.com/apache/solr-mcp/issues/213)).

### Security

[Unreleased]: https://github.com/apache/solr-mcp/commits
