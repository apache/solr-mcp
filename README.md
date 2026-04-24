[![Project Status: Incubating](https://img.shields.io/badge/status-incubating-yellow.svg)](https://github.com/apache/solr-mcp)

# Apache Solr MCP Server

Search, index, and manage [Apache Solr](https://solr.apache.org/) collections using **natural language** -- no need to hand-craft Solr queries, build filter expressions, or memorize the admin API.

Instead of writing:

```
q=title:"star wars" AND genre_s:"sci-fi"&fq=year_i:[2000 TO *]&facet=true&facet.field=genre_s&sort=score desc&rows=10
```

Just ask your AI assistant:

> *"Find sci-fi movies with 'star wars' in the title released after 2000, show me the genre breakdown, and sort by relevance."*

The Solr MCP Server implements the [Model Context Protocol (MCP)](https://spec.modelcontextprotocol.io/) to expose Solr operations as tools that any MCP-compatible AI client can invoke.

**[Website](https://solr.apache.org/mcp)** ·
**[Quick Start](https://solr.apache.org/mcp/quick-start.html)** ·
**[Client Setup](https://solr.apache.org/mcp/clients/claude-desktop.html)** ·
**[Features](https://solr.apache.org/mcp/features.html)**

## Features

### MCP Tools

| Tool | Description |
|------|-------------|
| `search` | Full-text search with filtering, faceting, sorting, and pagination |
| `index-json-documents` | Index documents from a JSON array |
| `index-csv-documents` | Index documents from CSV (first row = headers) |
| `index-xml-documents` | Index documents from XML |
| `list-collections` | List all Solr collections |
| `get-collection-stats` | Collection metrics: index stats, query performance, cache hit ratios |
| `check-health` | Health check with status, document count, and responsiveness |
| `create-collection` | Create a collection with configurable shards, replicas, and configset |
| `get-schema` | Retrieve field definitions, field types, dynamic fields, copy fields |

### MCP Resources

| Resource URI | Description |
|---|---|
| `solr://collections` | List of all Solr collections in the cluster |
| `solr://{collection}/schema` | Schema definition for a collection (supports autocompletion) |

### Platform

- **Transports**: STDIO (Claude Desktop, Claude Code) and HTTP (remote access, multi-client)
- **Security**: OAuth2 with JWT validation (Auth0, Keycloak, Okta) in HTTP mode
- **Observability**: OpenTelemetry traces, Prometheus metrics, structured logs via LGTM stack
- **Docker**: Multi-platform images (amd64 + arm64) built with Jib

## Quick Start

Get from zero to a working Claude + Solr integration in under 2 minutes.

**Prerequisites:** Java 25+, [Docker](https://docs.docker.com/get-docker/) and Docker Compose, an MCP client (e.g., [Claude Desktop](https://claude.ai/download))

### 1. Start Solr with sample data

```bash
git clone https://github.com/apache/solr-mcp.git
cd solr-mcp
docker compose up -d
```

This starts Solr in SolrCloud mode with two sample collections: **films** (1,100+ movies) and **books** (empty, ready for indexing). Wait ~30 seconds, then verify at http://localhost:8983/solr/.

### 2. Build the server

```bash
./gradlew build
```

### 3. Configure Claude Desktop

Add to your config file (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS, `%APPDATA%\Claude\claude_desktop_config.json` on Windows):

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp/build/libs/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

Restart Claude Desktop.

### 4. Try it out

- *"Search the films collection for movies directed by Steven Spielberg"*
- *"What collections are available in Solr?"*
- *"Show me the schema for the films collection"*
- *"Index this JSON into the books collection: [{"id": "1", "title": "The Great Gatsby", "author": "F. Scott Fitzgerald"}]"*

> For more clients (Claude Code, VS Code, Cursor, JetBrains), see **[Adding to AI Clients](https://solr.apache.org/mcp/clients/claude-desktop.html)** on the website.

## Adding to AI Clients

<details>
<summary><strong>Claude Desktop</strong></summary>

#### STDIO Mode (recommended)

**JAR:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Docker (local image):**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
               "solr-mcp:latest"]
    }
  }
}
```

Build the local image first: `./gradlew jibDockerBuild`

**Linux users:** add `--add-host=host.docker.internal:host-gateway` to Docker args.

#### HTTP Mode

Start the server first (`PROFILES=http ./gradlew bootRun`), then:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "npx",
      "args": ["mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

</details>

<details>
<summary><strong>Claude Code</strong></summary>

#### STDIO Mode (recommended)

**CLI:**

```bash
# JAR
claude mcp add --transport stdio \
    -e SOLR_URL=http://localhost:8983/solr/ \
    solr-mcp -- java -jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar

# Docker (local image)
claude mcp add --transport stdio solr-mcp -- \
    docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

**`.mcp.json`:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

#### HTTP Mode

Start the server first, then:

```bash
claude mcp add --transport http solr-mcp http://localhost:8080/mcp
```

Or in `.mcp.json`:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

</details>

<details>
<summary><strong>VS Code / GitHub Copilot</strong></summary>

Create `.vscode/mcp.json` in your project root:

#### STDIO Mode

**JAR:**

```json
{
  "servers": {
    "solr-mcp": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Docker (local image):**

```json
{
  "servers": {
    "solr-mcp": {
      "type": "stdio",
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
               "solr-mcp:latest"]
    }
  }
}
```

#### HTTP Mode

```json
{
  "servers": {
    "solr-mcp": {
      "type": "sse",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

After adding the configuration, Solr MCP tools are available in GitHub Copilot Chat (Agent mode).

</details>

<details>
<summary><strong>Cursor</strong></summary>

Create `.cursor/mcp.json` in your project root:

#### STDIO Mode

**JAR:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Docker (local image):**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
               "solr-mcp:latest"]
    }
  }
}
```

#### HTTP Mode

```json
{
  "mcpServers": {
    "solr-mcp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Or use **Cursor Settings > Features > MCP Servers > Add New MCP Server**.

</details>

<details>
<summary><strong>JetBrains IDEs</strong></summary>

Create `.junie/mcp.json` in your project root:

#### STDIO Mode

**JAR:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Docker (local image):**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
               "solr-mcp:latest"]
    }
  }
}
```

#### HTTP Mode

```json
{
  "mcpServers": {
    "solr-mcp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Or use **Settings > Tools > AI Assistant > MCP Servers > Add**.

</details>

<details>
<summary><strong>MCP Inspector</strong></summary>

```bash
npx @modelcontextprotocol/inspector
```

**HTTP:** connect to `http://localhost:8080/mcp`

**STDIO:** command `java`, arguments `-jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar`

</details>

## Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `SOLR_URL` | Solr base URL | `http://localhost:8983/solr/` |
| `PROFILES` | Transport mode: `stdio` or `http` | `stdio` |
| `SECURITY_ENABLED` | Enable OAuth2 authentication (HTTP only) | `false` |
| `OAUTH2_ISSUER_URI` | OAuth2 issuer URL (Auth0, Keycloak, Okta) | -- |
| `OTEL_SAMPLING_PROBABILITY` | Tracing sampling rate (0.0--1.0) | `1.0` |
| `OTEL_TRACES_URL` | OTLP collector endpoint | `http://localhost:4317` |

## Example Prompts

**Searching:**
- *"Find sci-fi movies released after 2000 and show the genre breakdown"*
- *"Search films for movies with 'war' in the title, sorted by year"*
- *"Show me the top 5 most recent films"*

**Indexing:**
- *"Index this JSON into the books collection: [{"id": "1", "title": "1984", "author": "George Orwell"}]"*
- *"Create a new collection called products"*

**Managing:**
- *"What collections are available?"*
- *"Is the films collection healthy?"*
- *"Show me the schema for the films collection"*
- *"How many documents are in the films collection?"*

## Running the Server

### STDIO mode (default)

```bash
# JAR
./gradlew build
java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar

# Gradle (development)
./gradlew bootRun

# Docker (local image — build first with ./gradlew jibDockerBuild)
docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest
```

### HTTP mode

```bash
# JAR
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar

# Gradle (development)
PROFILES=http ./gradlew bootRun

# Docker (local image)
docker run -p 8080:8080 --rm -e PROFILES=http -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest
```

The MCP endpoint is available at `http://localhost:8080/mcp`. Verify with `curl http://localhost:8080/actuator/health`.

### Building a Docker image

```bash
./gradlew jibDockerBuild
```

This creates `solr-mcp:latest` locally with multi-platform support (amd64 + arm64).

## Native Image (Experimental)

An opt-in GraalVM native image build is available for the STDIO profile:

```bash
./gradlew bootBuildImage
docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native
```

See [docs/specs/graalvm-native-image.md](docs/specs/graalvm-native-image.md) for details.

## Community

- **Website:** https://solr.apache.org/mcp
- **Slack:** [`#solr-mcp`](https://the-asf.slack.com/archives/C09TVG3BM1P) in the `the-asf` workspace
- **Mailing lists:** Shared with Apache Solr -- see [mailing lists](https://solr.apache.org/community.html#mailing-lists-chat)
- **Issues:** https://github.com/apache/solr-mcp/issues

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, testing, and PR guidelines.

## License

Apache License 2.0 -- see [LICENSE](LICENSE)

## Acknowledgments

Built with [Spring AI MCP](https://spring.io/projects/spring-ai), [Apache Solr](https://solr.apache.org/), [Jib](https://github.com/GoogleContainerTools/jib), [Testcontainers](https://www.testcontainers.org/), and [Spring AI MCP Security](https://github.com/spring-ai-community/mcp-security).
