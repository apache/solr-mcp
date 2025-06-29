# Solr MCP Server

A Spring Boot application that integrates Apache Solr with Spring AI's Model Context Protocol (MCP) server. This server allows you to use Apache Solr as a context source for AI models, enhancing responses with relevant information from your Solr index.

## Features

- Spring AI MCP server for AI model integration
- Apache Solr integration for context retrieval
- REST API for retrieving context from Solr
- Support for Anthropic Claude AI model
- PostgreSQL-based chat memory repository

## Prerequisites

- Java 21 or higher
- Apache Solr 9.x running on your local machine or a remote server
- PostgreSQL database for chat memory storage
- Anthropic API key for using Claude

## Configuration

### Application Properties

Configure the application by editing `src/main/resources/application.properties`:

```properties
# Solr Configuration
solr.server.url=http://localhost:8983/solr
solr.core.name=mcp-core

# MCP Server Configuration
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.port=8090
spring.ai.mcp.server.cors.allowed-origins=*

# Database Configuration for Chat Memory
spring.datasource.url=jdbc:postgresql://localhost:5432/mcpdb
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.datasource.driver-class-name=org.postgresql.Driver

# Anthropic API Configuration
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
```

### Environment Variables

Set the following environment variables:

- `ANTHROPIC_API_KEY`: Your Anthropic API key for using Claude

## Building and Running

### Build the Application

```bash
./gradlew build
```

### Run the Application

```bash
./gradlew bootRun
```

Or run the JAR file directly:

```bash
java -jar build/libs/solr-mcp-server-0.0.1-SNAPSHOT.jar
```

## Usage

### MCP Server

The MCP server runs on port 8090 by default and follows the [Model Context Protocol](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) specification.

### Solr Context API

Use the Solr Context API to retrieve relevant context from your Solr index:

```bash
curl -X POST http://localhost:8080/api/solr/context \
  -H "Content-Type: application/json" \
  -d '{"query": "your search query", "maxDocuments": 5}'
```

Response:

```json
{
  "query": "your search query",
  "documents": [
    "Document content 1",
    "Document content 2",
    "..."
  ],
  "count": 5
}
```

### Integrating with MCP Clients

1. Retrieve context from the Solr Context API
2. Include the context in your MCP client request as system instructions
3. Send the request to the MCP server

## Solr Configuration

Ensure your Solr core is properly configured with the fields you want to search. The application assumes a field named `content` contains the document text.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.