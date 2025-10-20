[![Project Status: Incubating](https://img.shields.io/badge/status-incubating-yellow.svg)](https://github.com/apache/solr-mcp)
# Solr MCP Server

A Spring AI Model Context Protocol (MCP) server that provides tools for interacting with Apache Solr. This server
enables AI assistants like Claude to search, index, and manage Solr collections through the MCP protocol.

## Overview

This project provides a set of tools that allow AI assistants to interact with Apache Solr, a powerful open-source
search platform. By implementing the Spring AI MCP protocol, these tools can be used by any MCP-compatible client,
including Claude Desktop. The project uses SolrJ, the official Java client for Solr, to communicate with Solr instances.

The server provides the following capabilities:

- Search Solr collections with advanced query options
- Index documents into Solr collections
- Manage and monitor Solr collections
- Retrieve and analyze Solr schema information

### Transport Profiles

The server supports two transport modes:

- **STDIO (Standard Input/Output)** - Recommended for local development and production use with Claude Desktop. This is
  the default and most secure option for local deployments.
- **HTTP (Streamable HTTP)** - For testing with MCP Inspector and remote deployments. ⚠️ **Note:** HTTP
  mode is inherently insecure without additional security measures (see Security Considerations below).

## Prerequisites

- Java 25 or higher
- Docker and Docker Compose (for running Solr)
- Gradle 9.1.0+ (wrapper included in project)

## Installation and Setup

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/solr-mcp-server.git
cd solr-mcp-server
```

### 2. Start Solr using Docker Compose

```bash
docker-compose up -d
```

This will start a Solr instance in SolrCloud mode with ZooKeeper and create two sample collections:

- `books` - A collection with sample book data
- `films` - A collection with sample film data

### 3. Build the Project

This project uses Gradle with version catalogs for dependency management. All dependencies and their versions are
centrally managed in `gradle/libs.versions.toml`.

```bash
# Build the project and run tests
./gradlew build

# Build without tests (faster)
./gradlew assemble

# Clean and rebuild
./gradlew clean build
```

The build produces two JAR files in `build/libs/`:

- `solr-mcp-server-0.0.1-SNAPSHOT.jar` - Executable JAR with all dependencies (fat JAR)
- `solr-mcp-server-0.0.1-SNAPSHOT-plain.jar` - Plain JAR without dependencies

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

### Key Components

- **MCP Tools**: Service classes annotated with `@McpTool` expose functionality to AI assistants
    - `SearchService` - Search queries with filtering, faceting, and pagination
    - `IndexingService` - Document indexing with support for JSON, CSV, and XML formats
    - `CollectionService` - Collection management (list, stats, health checks)
    - `SchemaService` - Schema introspection

- **Configuration**: Spring Boot configuration using properties files
    - `application.properties` - Default configuration
    - `application-stdio.properties` - STDIO transport profile
    - `application-http.properties` - HTTP transport profile

- **Document Creators**: Strategy pattern implementation for parsing different document formats
    - Automatically sanitizes field names to comply with Solr schema requirements
    - Supports nested JSON structures and multi-valued fields

- **DTOs**: Java records for immutable data transfer objects (removed Lombok dependency)

## Available Tools

The server provides the following tools that can be used by MCP clients:

### 1. Search

Search a Solr collection with advanced query options.

```
Tool: Search
Description: Search specified Solr collection with query, optional filters, facets, sorting, and pagination.
Parameters:
  - collection: Solr collection to query
  - query: Solr q parameter (defaults to "*:*" if not specified)
  - filterQueries: Solr fq parameter (optional)
  - facetFields: Solr facet fields (optional)
  - sortClauses: Solr sort parameter (optional)
  - start: Starting offset for pagination (optional)
  - rows: Number of rows to return (optional)
```

### 2. Index Documents

Index JSON documents into a Solr collection.

```
Tool: index_documents
Description: Index documents from JSON string into Solr collection
Parameters:
  - collection: Solr collection to index into
  - json: JSON string containing documents to index
```

### 3. List Collections

List all available Solr collections.

```
Tool: listCollections
Description: List solr collections
Parameters: None
```

### 4. Get Collection Stats

Get detailed statistics and metrics for a Solr collection.

```
Tool: getCollectionStats
Description: Get stats/metrics on a Solr collection
Parameters:
  - collection: Name of the collection
```

### 5. Check Collection Health

Check the health status of a Solr collection.

```
Tool: checkHealth
Description: Check health of a Solr collection
Parameters:
  - collection: Name of the collection
```

### 6. Get Schema

Retrieve the schema for a Solr collection.

```
Tool: getSchema
Description: Get schema for a Solr collection
Parameters:
  - collection: Name of the collection
```

## Adding to Claude Desktop

To add this MCP server to Claude Desktop:

1. Build the project as a standalone JAR:

```bash
./gradlew build
```

2. In Claude Desktop, go to Settings > Developer > Edit Config

3. Add the following configuration to your MCP settings:

```json
{
    "mcpServers": {
        "solr-search-mcp": {
            "command": "java",
            "args": [
                "-jar",
                "/absolute/path/to/solr-mcp-server/build/libs/solr-mcp-server-0.0.1-SNAPSHOT.jar"
            ],
            "env": {
                "SOLR_URL": "http://localhost:8983/solr/",
                "PROFILES": "stdio"
            }
        }
    }
}
```

**Note:** Replace `/absolute/path/to/solr-mcp-server` with the actual path to your project directory.

### 4. Restart Claude Desktop & Invoke

![claude-stdio.png](images/claude-stdio.png)

## Testing with MCP Inspector

For development and testing, you can use the [MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```bash
# Install the MCP Inspector (requires Node.js)
➜  ~ npx @modelcontextprotocol/inspector

Starting MCP inspector...
⚙️ Proxy server listening on localhost:6277
🔑 Session token: 12345
   Use this token to authenticate requests or set DANGEROUSLY_OMIT_AUTH=true to disable auth

🚀 MCP Inspector is up and running at:
   http://localhost:6274/?MCP_PROXY_AUTH_TOKEN=12345

🌐 Opening browser...
```

This provides a web interface to test MCP tools interactively.
![mcp-inspector-stdio.png](images/mcp-inspector-stdio.png)

# HTTP Mode

![mcp-inspector-http.png](images/mcp-inspector-http.png)

```shell
./gradlew bootRun --args='--spring.profiles.active=http'
```

## Tools Usage Examples

Here are some examples of how to use the tools from an MCP client like Claude:

### Searching for Books

```
I need to search for fantasy books in the Solr collection. Can you help me?
```

Claude can use the Search tool to query the books collection:

```
I'll search for fantasy books in the Solr collection.

Using the Search tool with:
- collection: books
- query: genre_s:fantasy
- rows: 5

Here are the fantasy books I found:
1. "A Game of Thrones" by George R.R. Martin
2. "A Clash of Kings" by George R.R. Martin
...
```

### Indexing New Documents

```
I have some new book data I'd like to add to Solr. Can you help me index it?
```

Claude can use the index_documents tool:

```
I'll help you index that book data into Solr.

Using the index_documents tool with:
- collection: books
- json: [{"id":"123456789","name":["The New Book"],"author":["Jane Author"],"genre_s":"mystery","price":[12.99]}]

The document has been successfully indexed into the books collection.
```

### Listing Collections

```
What Solr collections are available in the system?
```

Claude can use the listCollections tool:

```
I'll check what Solr collections are available.

Using the listCollections tool.

The following collections are available:
1. books
2. films
```

### Getting Collection Statistics

```
Can you tell me more about the 'books' collection? I'd like to know its size and other metrics.
```

Claude can use the getCollectionStats tool:

```
I'll get statistics for the 'books' collection.

Using the getCollectionStats tool with:
- collection: books

Here are the statistics for the 'books' collection:
- Number of documents: 1,245
- Segment count: 5
- Query performance:
  - Average query time: 12ms
  - Cache hit ratio: 0.85
- Handler statistics:
  - Select handler requests: 1,532
  - Update handler requests: 245
```

### Checking Collection Health

```
Is the 'films' collection healthy and responding properly?
```

Claude can use the checkHealth tool:

```
I'll check the health of the 'films' collection.

Using the checkHealth tool with:
- collection: films

Health status:
- Healthy: Yes
- Response time: 8ms
- Total documents: 532
- Last checked: 2023-06-15 14:32:45
```

### Retrieving Schema Information

```
What fields are defined in the 'books' collection schema?
```

Claude can use the getSchema tool:

```
I'll retrieve the schema for the 'books' collection.

Using the getSchema tool with:
- collection: books

The 'books' collection has the following fields:
- id (string): Unique identifier
- name (text_general): Book title
- author (text_general): Book author
- genre_s (string): Genre
- price (float): Book price
- in_stock_b (boolean): Whether the book is in stock
- publish_date (date): Publication date
```

## Security Considerations

### STDIO Transport Security

STDIO transport is the recommended option for local deployments because:

- Communication occurs within the same machine through process pipes
- No network exposure or open ports
- OS-level process isolation provides security boundaries
- Credentials are not exposed over the network

### HTTP Transport Security Risks

⚠️ **Warning**: The current HTTP implementation is **insecure** for production use without additional security
measures.

HTTP transport has the following security vulnerabilities when deployed without authentication:

1. **No Authentication or Authorization**: By default, the HTTP endpoints are publicly accessible without any
   authentication mechanism
2. **No Transport Encryption**: HTTP traffic is unencrypted and can be intercepted (use HTTPS in production)
3. **No Origin Validation**: Without proper origin header validation, the server is vulnerable to DNS rebinding attacks
4. **Network Exposure**: Unlike STDIO, HTTP endpoints are exposed over the network and accessible to any client that can
   reach the server

### Securing HTTP Deployments

If you need to deploy the MCP server with HTTP transport for remote access, you **must** implement security
controls:

1. **Use HTTPS**: Always use TLS/SSL encryption for production deployments
2. **Implement OAuth2 Authentication**: Follow
   the [Spring AI MCP OAuth2 guide](https://spring.io/blog/2025/04/02/mcp-server-oauth2/) to add authentication
3. **Validate Origin Headers**: Implement origin header validation to prevent DNS rebinding attacks
4. **Network Isolation**: Deploy behind a firewall or VPN, restricting access to trusted networks
5. **Use API Gateways**: Consider deploying behind an API gateway with authentication and rate limiting

### Recommendation

- **Local development/testing**: Use HTTP mode for testing with MCP Inspector, but only on localhost
- **Claude Desktop integration**: Always use STDIO mode
- **Production remote deployments**: Only use HTTP with OAuth2 authentication, HTTPS, and proper network security
  controls

## Troubleshooting

If you encounter issues:

1. Ensure Solr is running and accessible. By default, the server connects to http://localhost:8983/solr/, but you can
   set the `SOLR_URL` environment variable to point to a different Solr instance.
2. Check the logs for any error messages
3. Verify that the collections exist using the Solr Admin UI
4. If using HTTP mode, ensure the server is running on the expected port (default: 8080)
5. For STDIO mode with Claude Desktop, verify the JAR path is absolute and correct in the configuration

## License

This project is licensed under the Apache License 2.0.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
