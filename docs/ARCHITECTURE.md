# Architecture

This document describes the architecture and design decisions of the Solr MCP Server.

## Project Structure

The codebase follows a clean, modular architecture organized by functionality:

```
src/main/java/org/apache/solr/mcp/server/
├── Main.java                           # Application entry point
├── config/                             # Configuration classes
│   ├── SolrConfig.java                # Solr client bean configuration
│   └── SolrConfigurationProperties.java # Solr connection properties
├── search/                             # Search functionality
│   ├── SearchService.java             # MCP tool for searching Solr
│   └── SearchResponse.java            # Search result DTOs
├── indexing/                           # Document indexing functionality
│   ├── IndexingService.java           # MCP tool for indexing documents
│   └── documentcreator/               # Document format parsers
│       ├── IndexingDocumentCreator.java    # Interface for document creators
│       ├── JsonDocumentCreator.java        # JSON document parser
│       ├── CsvDocumentCreator.java         # CSV document parser
│       ├── XmlDocumentCreator.java         # XML document parser
│       ├── SolrDocumentCreator.java        # Factory for document creators
│       ├── FieldNameSanitizer.java         # Field name sanitization utility
│       └── DocumentProcessingException.java # Indexing exceptions
└── metadata/                           # Collection management functionality
    ├── CollectionService.java         # MCP tools for collection operations
    ├── SchemaService.java             # MCP tool for schema retrieval
    ├── CollectionUtils.java           # Collection utility methods
    └── Dtos.java                      # Collection-related DTOs (records)
```

## Key Components

### MCP Tools

Service classes annotated with `@McpTool` expose functionality to AI assistants:

- **SearchService** - Search queries with filtering, faceting, and pagination
- **IndexingService** - Document indexing with support for JSON, CSV, and XML formats
- **CollectionService** - Collection management (list, stats, health checks)
- **SchemaService** - Schema introspection

### Configuration

Spring Boot configuration using properties files:

- `application.properties` - Default configuration
- `application-stdio.properties` - STDIO transport profile
- `application-http.properties` - HTTP transport profile

### Document Creators

Strategy pattern implementation for parsing different document formats:

- Automatically sanitizes field names to comply with Solr schema requirements
- Supports nested JSON structures and multi-valued fields
- Factory pattern for selecting appropriate creator based on content type

### DTOs

Java records for immutable data transfer objects:

- Replaced Lombok to reduce dependencies
- Type-safe, immutable data structures
- Clean serialization/deserialization

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

### Document Processing

The document creator pattern allows for:
- **Extensibility**: Easy to add new format parsers
- **Testability**: Each creator can be tested independently
- **Field Sanitization**: Automatic conversion of field names to Solr-compatible format
- **Type Detection**: Automatic format detection from content

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
```
src/test/java/org/apache/solr/mcp/server/
├── McpToolRegistrationTest.java       # MCP tool registration tests
├── SampleClient.java                   # Example MCP client
├── search/
│   ├── SearchServiceTest.java         # Unit tests
│   └── SearchServiceDirectTest.java   # Integration tests
├── indexing/
│   ├── IndexingServiceTest.java
│   ├── IndexingServiceDirectTest.java
│   ├── CsvIndexingTest.java
│   └── XmlIndexingTest.java
├── metadata/
│   ├── CollectionServiceTest.java
│   ├── CollectionServiceIntegrationTest.java
│   ├── SchemaServiceTest.java
│   └── SchemaServiceIntegrationTest.java
└── containerization/
    ├── DockerImageStdioIntegrationTest.java
    ├── DockerImageHttpIntegrationTest.java
    └── BuildInfoReader.java          # Test utility for build metadata
```

## Dependency Management

All dependencies are managed via Gradle version catalogs in `gradle/libs.versions.toml`:

- Centralized version management
- Easy upgrades and consistency
- Clear dependency organization

## Configuration Properties

### Solr Connection
- `SOLR_URL`: Solr instance URL (default: `http://localhost:8983/solr/`)

### Transport Mode
- `PROFILES`: Set to `stdio` or `http` to select transport mode

### Docker Compose
- `SPRING_DOCKER_COMPOSE_ENABLED`: Enable/disable Docker Compose integration

## Future Considerations

### Potential Enhancements

1. **Authentication & Authorization**
   - OAuth2 support for HTTP mode
   - Token-based authentication
   - Role-based access control

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
