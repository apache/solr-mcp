# Architecture

This document describes the architecture and design decisions of the Solr MCP Server.

## Project Structure

The codebase follows a clean, modular architecture organized by functionality:

Each direct sub-package of `server/` is a Spring Modulith module; `ModularityTests`
fails the build on a cycle between them or on use of another module's internal
sub-package (such as `indexing/documentcreator/`).

```
src/main/java/org/apache/solr/mcp/server/
├── Main.java                           # Application entry point
├── collection/                         # Collection and alias management
│   ├── CollectionService.java         # MCP tools: list, stats, health, create; every @McpComplete handler
│   ├── AliasService.java              # MCP tools: list, create, delete aliases
│   ├── AliasResult.java               # Alias operation result
│   ├── CollectionUtils.java           # Typed reads from Solr NamedList responses
│   └── Dtos.java                      # Collection metrics records
├── config/                             # Wiring for SolrJ, observability and transports
│   ├── SolrConfig.java                # SolrJ client: JSON responses, XML update requests
│   ├── SolrConfigurationProperties.java # solr.* connection properties
│   ├── JsonResponseParser.java        # Solr JSON response → SolrJ NamedList
│   ├── SolrNativeHints.java           # GraalVM reflection/resource hints
│   ├── InstallOpenTelemetryAppender.java # Hands OpenTelemetry to the Logback appender
│   └── ModernStdioTransportConfiguration.java # STDIO under MCP_PROTOCOL=modern
├── indexing/                           # Document indexing
│   ├── IndexingService.java           # MCP tools: JSON, CSV, XML, Markdown
│   ├── SolrUpdateXml.java             # Rejects anything but an <add> block before XML is forwarded to Solr
│   └── documentcreator/               # Parsers for the formats Solr cannot parse itself
│       ├── IndexingDocumentCreator.java    # Orchestrator that delegates to format-specific creators
│       ├── JsonDocumentCreator.java        # JSON document parser (implements SolrDocumentCreator)
│       ├── MarkdownDocumentCreator.java    # Markdown document parser (implements SolrDocumentCreator)
│       ├── SolrDocumentCreator.java        # Common interface for document creators
│       └── DocumentProcessingException.java # Indexing exceptions
├── schema/                             # Schema introspection and additive changes
│   ├── SchemaService.java             # MCP tools: get-schema, list-fields, add-fields, add-field-types
│   ├── CollectionSchema.java          # get-schema result
│   ├── FieldListing.java              # list-fields result (Luke)
│   ├── FieldFlags.java                # Luke flag letters → SolrJ FieldFlag
│   └── SchemaUpdateResult.java        # add-fields / add-field-types result
├── search/                             # Search
│   ├── SearchService.java             # MCP tool: search
│   ├── SearchResponse.java            # Search result record
│   └── SortClause.java                # One sort clause of a search
├── security/                           # HTTP profile only
│   ├── HttpSecurityConfiguration.java # OAuth2 resource server; /mcp open at the URL level
│   └── MethodSecurityConfiguration.java # Enables the @PreAuthorize checks on tool methods
└── util/                               # Shared prompt definitions
    ├── PromptNames.java               # Prompt names shared by @McpPrompt and @McpComplete
    └── PromptText.java                # Prompt bodies
```

## Key Components

### MCP Tools

Service classes annotated with `@McpTool` expose functionality to AI assistants:

- **SearchService** - Search queries with filtering, faceting, sorting and pagination
- **IndexingService** - Document indexing for JSON, CSV, XML and Markdown
- **CollectionService** - Collection management (list, stats, health checks, create)
- **AliasService** - Alias management (list, create, delete)
- **SchemaService** - Schema introspection, field listing and additive changes

### Configuration

Spring Boot configuration using properties files:

- `application.properties` - Default configuration
- `application-stdio.properties` - STDIO transport profile
- `application-http.properties` - HTTP transport profile

### Document Creators

Server-side parsing exists only for JSON and Markdown, the formats Solr's update
handlers do not accept directly:

- Uses field names as given, as the CSV and XML pass-throughs do
- Supports nested JSON structures and multi-valued fields
- Delegation via service composition (IndexingDocumentCreator) to the appropriate format-specific creator

CSV and Solr update XML are forwarded to Solr as-is, field names as given. The only
server-side step is `SolrUpdateXml`, which rejects any XML root other than `<add>`
so the indexing tool cannot carry `<delete>` or `<commit>`.

### DTOs

Java `record` types used as data transfer objects:

- No Lombok dependency; the record declaration is the whole definition
- Designed for straightforward serialization/deserialization
- Records are immutable by construction, so the response types handed to
  MCP clients cannot be mutated after they are built

## Design Decisions

### Why Spring AI MCP?

Spring AI MCP provides a robust framework for implementing the Model Context Protocol with:
- Built-in transport layer support (STDIO and HTTP)
- Annotation-based tool registration
- Spring Boot integration for configuration and dependency injection

### Why Jib instead of Spring Boot Buildpacks?

**STDIO Mode Compatibility**: Docker images built with Spring Boot Buildpacks output logs and diagnostic information to stdout, which interferes with the MCP protocol's STDIO transport. The MCP protocol requires a clean stdout channel for protocol messages.

Additional Jib benefits:
- **Clean stdout**: No pollution of protocol messages
- **No Docker daemon required**: Can build images without Docker installed
- **Faster builds**: Layered image building with better caching
- **Smaller images**: More efficient layer organization
- **Multi-platform support**: Easy cross-platform image building for amd64 and arm64

### Transport Modes

#### STDIO (Recommended for Claude Desktop)

- Communication via standard input/output streams
- No network exposure
- OS-level process isolation
- Secure for local deployments

#### HTTP (For MCP Inspector / Remote Access)

- RESTful endpoints using Spring Web
- Streamable HTTP transport
- Requires additional security measures for production
- Useful for testing and remote deployments

#### MCP protocol revision

Both transports speak the 2025 MCP revisions by default. `MCP_PROTOCOL=modern` serves the
2026-07-28 revision instead (no `initialize` handshake or session, `server/discover`), using
the modern server in MCP Java SDK 2.2.0 that Spring AI 2.1.0 auto-configures. The tools,
resources and prompts are the same in both. See [modern-protocol.md](../docs/modern-protocol.md).

### Document Processing

The document creator pattern allows for:
- **Extensibility**: Easy to add new format parsers
- **Testability**: Each creator can be tested independently
- **Explicit Format Selection**: Separate methods for JSON/CSV/XML (no automatic detection)

### Error Handling

- Custom exceptions for domain-specific errors
- Proper error messages propagated to MCP clients
- Validation at tool entry points

## Testing Architecture

### Unit Tests
- Test individual components in isolation
- Mock external dependencies (Solr, Spring beans)
- Fast execution for quick feedback

### Integration Tests
- Use Testcontainers for real Solr instances
- Test end-to-end workflows
- Verify Docker image functionality

### Test Structure
Test packages mirror the main ones; each holds `<Class>Test` unit tests and
`<Class>IntegrationTest` Testcontainers tests for that package. The top level and the
test-only packages:

```
src/test/java/org/apache/solr/mcp/server/
├── MainTest.java, McpToolRegistrationTest.java
├── McpClientIntegrationTest.java      # MCP workflow over the in-process client
├── McpClientStdioIntegrationTest.java # MCP workflow against `java -jar` over STDIO
├── ModularityTests.java               # Spring Modulith module boundaries
├── LoggingConfigurationTest.java      # Guards the single logback-spring.xml rule
├── TestcontainersConfiguration.java   # Shared Solr container
├── collection/ config/ indexing/ schema/ search/ security/
├── modern/                            # MCP_PROTOCOL=modern over HTTP and STDIO
├── observability/                     # Tracing and OTLP export (LGTM container)
└── containerization/                  # @Tag("docker-integration") only, run by dockerIntegrationTest
```

## Dependency Management

All dependencies are managed via Gradle version catalogs in `gradle/libs.versions.toml`:

- Centralized version management
- Easy upgrades and consistency
- Clear dependency organization

## Configuration Properties

### Solr Connection
- `SOLR_URL`: Solr instance URL (default: `http://localhost:8983/solr/`)
- `SOLR_USERNAME`: HTTP Basic Authentication username (optional; required together with `SOLR_PASSWORD`)
- `SOLR_PASSWORD`: HTTP Basic Authentication password (optional; required together with `SOLR_USERNAME`)

### Transport Mode
- `PROFILES`: Set to `stdio` or `http` to select transport mode

### Docker Compose
- `SPRING_DOCKER_COMPOSE_ENABLED`: Enable/disable Docker Compose integration

## Future Considerations

### Potential Enhancements

1. **Authentication & Authorization**
   - Role-based access control
   (OAuth2 resource-server support and bearer-token authentication for HTTP
   mode are already implemented — see `security/HttpSecurityConfiguration`.)

2. **Additional Tools**
   - Bulk operations
   - Query suggestions
   - Analytics and reporting

3. **Performance**
   - Response streaming for large result sets
   - Query caching
   - Connection pooling optimization

4. **Monitoring**
   - Metrics collection
   - Health checks
   - Performance monitoring

5. **Multi-Solr Support**
   - Connect to multiple Solr instances
   - Cross-cluster operations
