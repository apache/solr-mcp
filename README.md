[![Project Status: Incubating](https://img.shields.io/badge/status-incubating-yellow.svg)](https://github.com/apache/solr-mcp)

# Solr MCP Server

A Spring AI Model Context Protocol (MCP) server that provides tools for interacting with Apache Solr. Enables AI assistants like Claude to search, index, and manage Solr collections through the MCP protocol.

## Features

- 🔍 **Search** - Query Solr collections with advanced filtering, faceting, and pagination
- 📝 **Index** - Add documents in JSON, CSV, and XML formats
- 📊 **Manage** - List collections, check health, and view statistics
- 🔧 **Schema** - Inspect and analyze Solr schema information
- 🐳 **Docker** - Containerized deployment with automated builds
- 🔌 **Dual Modes** - STDIO (Claude Desktop) and HTTP (MCP Inspector) transports

## Quick Start

### Prerequisites

- Java 25 or higher
- Docker and Docker Compose
- Gradle 9.1.0+ (wrapper included)

### Installation

```bash
# 1. Clone the repository
git clone https://github.com/apache/solr-mcp.git
cd solr-mcp

# 2. Start Solr with sample data
docker-compose up -d

# 3. Build the project
./gradlew build
```

This starts Solr with two sample collections: `books` and `films`.

## Usage

### Option 1: Run with JAR

```bash
# Start the server
java -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
```

### Option 2: Run with Docker

```bash
# Build Docker image
./gradlew jibDockerBuild

# Run the container
docker run -i --rm solr-mcp:0.0.1-SNAPSHOT
```

### Option 3: Use Published Image

```bash
# Pull from GitHub Container Registry
docker run -i --rm ghcr.io/apache/solr-mcp:latest
```

## Claude Desktop Integration

Add to your Claude Desktop configuration (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "ghcr.io/apache/solr-mcp:latest"],
      "env": {
        "SOLR_URL": "http://localhost:8983/solr/"
      }
    }
  }
}
```

Then restart Claude Desktop. See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for detailed configuration options.

## Available Tools

| Tool | Description |
|------|-------------|
| `search` | Search Solr collections with advanced query options |
| `index_documents` | Index documents from JSON, CSV, or XML |
| `listCollections` | List all available Solr collections |
| `getCollectionStats` | Get statistics and metrics for a collection |
| `checkHealth` | Check the health status of a collection |
| `getSchema` | Retrieve schema information for a collection |

## Example Usage

Once integrated with Claude Desktop, you can ask:

```
Search for fantasy books in the books collection
```

Claude will use the search tool to query Solr and return results.

```
Index this JSON document into the books collection:
{"id": "123", "title": "New Book", "author": "Jane Doe"}
```

Claude will use the index_documents tool to add the document to Solr.

## Documentation

- **[Architecture](docs/ARCHITECTURE.md)** - Project structure and design decisions
- **[Development Guide](docs/DEVELOPMENT.md)** - Development setup, testing, and building
- **[Deployment Guide](docs/DEPLOYMENT.md)** - Docker, CI/CD, and MCP Registry publishing
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** - Common issues and solutions
- **[Contributing](CONTRIBUTING.md)** - How to contribute to the project

## Transport Modes

### STDIO (Recommended for Claude Desktop)

- Secure, local-only communication
- No network exposure
- Default mode

### HTTP (For MCP Inspector / Remote Access)

- Web-based access for testing
- Requires additional security for production
- See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#security-considerations) for security setup

```bash
# Run in HTTP mode
./gradlew bootRun --args='--spring.profiles.active=http'
```

Access at http://localhost:8080

## Testing with MCP Inspector

```bash
# Start server in HTTP mode
./gradlew bootRun --args='--spring.profiles.active=http'

# In another terminal, start MCP Inspector
npx @modelcontextprotocol/inspector
```

See the [Development Guide](docs/DEVELOPMENT.md#test-with-mcp-inspector) for details.

## Development

```bash
# Run tests
./gradlew test

# Run with code formatting
./gradlew spotlessApply build

# Run Docker integration tests
./gradlew dockerIntegrationTest
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed development guidelines.

## Publishing to MCP Registry

The server is automatically published to the MCP Registry when you create a version tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

GitHub Actions will build, publish Docker images, and register the server in the MCP Registry.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#mcp-registry-publishing) for details.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on:

- Setting up your development environment
- Code style and formatting
- Testing requirements
- Submitting pull requests

## Support

- **Issues**: [GitHub Issues](https://github.com/apache/solr-mcp/issues)
- **Discussions**: [GitHub Discussions](https://github.com/apache/solr-mcp/discussions)
- **Documentation**: [docs/](docs/)

## Acknowledgments

Built with:
- [Spring AI MCP](https://spring.io/projects/spring-ai) - MCP protocol implementation
- [Apache Solr](https://solr.apache.org/) - Search platform
- [Jib](https://github.com/GoogleContainerTools/jib) - Containerization
- [Testcontainers](https://www.testcontainers.org/) - Integration testing
