[![Project Status: Incubating](https://img.shields.io/badge/status-incubating-yellow.svg)](https://github.com/apache/solr-mcp)

# Solr MCP Server

A Spring AI Model Context Protocol (MCP) server that provides tools for interacting with Apache Solr. Enables AI assistants like Claude to search, index, and manage Solr collections through the MCP protocol.

## What’s inside

- 🔍 Search Solr collections with filtering, faceting, and pagination
- 📝 Index documents in JSON, CSV, and XML
- 📊 Manage collections and view statistics
- 🔧 Inspect schema
- 🔌 Transports: STDIO (Claude Desktop) and HTTP (MCP Inspector)
- 🐳 Docker images built with Jib

## Get started (users)

- Prerequisites: Java 25+, Docker (and Docker Compose), Git
- Start Solr with sample data:
  ```bash
  docker compose up -d
  ```
- Run the server:
    - **STDIO mode (default)**:
        - Gradle:
          ```bash
          ./gradlew bootRun
          ```
        - JAR:
          ```bash
          ./gradlew build
          java -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
          ```
        - Docker:
          ```bash
          docker run -i --rm ghcr.io/apache/solr-mcp:latest
          ```
    - **HTTP mode**:
        - Gradle:
          ```bash
          PROFILES=http ./gradlew bootRun
          ```
        - JAR:
          ```bash
          PROFILES=http java -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
          ```
        - Docker:
          ```bash
          docker run -p 8080:8080 --rm -e PROFILES=http ghcr.io/apache/solr-mcp:latest
          ```

For more options (custom SOLR_URL, Linux host networking) see the Deployment Guide: docs/DEPLOYMENT.md

### Claude Desktop

Add this to your Claude Desktop config (macOS path shown); then restart Claude.

**STDIO mode (default)**

Using Docker:
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

Using JAR:

```json
{
    "mcpServers": {
        "solr-mcp": {
            "command": "java",
            "args": [
                "-jar",
                "/absolute/path/to/solr-mcp-0.0.1-SNAPSHOT.jar"
            ],
            "env": {
                "SOLR_URL": "http://localhost:8983/solr/"
            }
        }
    }
}
```

**HTTP mode**

Using Docker:

```json
{
    "mcpServers": {
        "solr-mcp": {
            "command": "docker",
            "args": [
                "run",
                "-p",
                "8080:8080",
                "--rm",
                "ghcr.io/apache/solr-mcp:latest"
            ],
            "env": {
                "PROFILES": "http",
                "SOLR_URL": "http://localhost:8983/solr/"
            }
        }
    }
}
```

Using JAR:

```json
{
    "mcpServers": {
        "solr-mcp": {
            "command": "java",
            "args": [
                "-jar",
                "/absolute/path/to/solr-mcp-0.0.1-SNAPSHOT.jar"
            ],
            "env": {
                "PROFILES": "http",
                "SOLR_URL": "http://localhost:8983/solr/"
            }
    }
  }
}
```

**Connecting to a running HTTP server**

If you already have the MCP server running in HTTP mode (via Gradle, JAR, or Docker), you can connect Claude Desktop to
it using `mcp-remote`:

Running via Gradle:

```bash
PROFILES=http ./gradlew bootRun
```

Running locally (JAR):

```bash
PROFILES=http java -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
```

Running via Docker:

```bash
docker run -p 8080:8080 --rm -e PROFILES=http ghcr.io/apache/solr-mcp:latest
```

Then add to your `claude_desktop_config.json`:

```json
{
    "mcpServers": {
        "solr-mcp-http": {
            "command": "npx",
            "args": [
                "mcp-remote",
                "http://localhost:8080/mcp"
            ]
        }
    }
}
```

More configuration options: docs/DEPLOYMENT.md#docker-images-with-jib

## Available MCP tools

| Tool | Description |
|------|-------------|
| `search` | Search Solr collections with advanced query options |
| `index_documents` | Index documents from JSON, CSV, or XML |
| `listCollections` | List all available Solr collections |
| `getCollectionStats` | Get statistics and metrics for a collection |
| `checkHealth` | Check the health status of a collection |
| `getSchema` | Retrieve schema information for a collection |

## Screenshots

- Claude Desktop (STDIO):

  ![Claude Desktop STDIO](images/claude-stdio.png)

- MCP Inspector (HTTP):

  ![MCP Inspector HTTP](images/mcp-inspector-http.png)

- MCP Inspector (STDIO):

  ![MCP Inspector STDIO](images/mcp-inspector-stdio.png)

## Documentation

- Architecture: docs/ARCHITECTURE.md
- Development (build, run, test, add features): docs/DEVELOPMENT.md
- Deployment (Docker, HTTP vs STDIO, CI/CD, MCP Registry): docs/DEPLOYMENT.md
- Troubleshooting: docs/TROUBLESHOOTING.md

## Contributing

We welcome contributions!

- Start here: CONTRIBUTING.md
- Developer workflows, coding standards, and tests: docs/DEVELOPMENT.md

## Support

- Issues: https://github.com/apache/solr-mcp/issues
- Discussions: https://github.com/apache/solr-mcp/discussions

## License

Apache License 2.0 — see LICENSE

## Acknowledgments

Built with:

- Spring AI MCP — https://spring.io/projects/spring-ai
- Apache Solr — https://solr.apache.org/
- Jib — https://github.com/GoogleContainerTools/jib
- Testcontainers — https://www.testcontainers.org/
