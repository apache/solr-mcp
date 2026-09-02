[![Project Status: Incubating](https://img.shields.io/badge/status-incubating-yellow.svg)](https://github.com/apache/solr-mcp)

# Solr MCP Server

Search, index, and manage [Apache Solr](https://solr.apache.org/) collections using **natural language** — no need to hand-craft Solr queries, build filter expressions, or memorize the admin API.

Instead of writing:

```
q=title:"star wars" AND genre_s:"sci-fi"&fq=year_i:[2000 TO *]&facet=true&facet.field=genre_s&sort=score desc&rows=10
```

Just ask your AI assistant:

> *"Find sci-fi movies with 'star wars' in the title released after 2000, show me the genre breakdown, and sort by relevance."*

This Spring AI [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server exposes Solr operations as tools that any MCP-compatible AI client (Claude Desktop, Claude Code, VS Code/Copilot, Cursor, JetBrains) can invoke.

## Quick start

**Prerequisites:** Java 25+, [Docker](https://docs.docker.com/get-docker/) and Docker Compose, Git.

**Compatibility:** works with Apache Solr **8.11–10** (the test suite runs against 9.9 by default — see [Solr version compatibility](dev-docs/DEVELOPMENT.md#solr-version-compatibility)).

#### 1. Start Solr with sample data

```bash
git clone https://github.com/apache/solr-mcp.git
cd solr-mcp
docker compose up -d
```

This starts Solr in SolrCloud mode with two sample collections: **films** (1,100+ movies) and **books** (empty, ready for indexing). Wait ~30 seconds, then verify at <http://localhost:8983/solr/>.

#### 2. Build the server

```bash
./gradlew build
```

This produces `build/libs/solr-mcp-1.0.0-SNAPSHOT.jar`.

#### 3. Connect your AI client

Add the server to your MCP client. For **Claude Desktop**, edit
`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or
`%APPDATA%\Claude\claude_desktop_config.json` (Windows), then restart Claude:

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

Using a different client, or want STDIO/HTTP/Docker options? See the per-client guides:
**[Claude Desktop](docs/clients/claude-desktop.md)** ·
**[Claude Code](docs/clients/claude-code.md)** ·
**[VS Code / Copilot](docs/clients/vs-code.md)** ·
**[Cursor](docs/clients/cursor.md)** ·
**[JetBrains](docs/clients/jetbrains.md)** ·
**[MCP Inspector](docs/clients/mcp-inspector.md)**.

#### 4. Try it out

- *"What collections are available in Solr?"*
- *"Search the films collection for movies directed by Steven Spielberg"*
- *"Show me the schema for the films collection"*
- *"Index this JSON into the books collection: [{"id": "1", "title": "The Great Gatsby", "author": "F. Scott Fitzgerald"}]"*

## Example prompts

**Searching**
- *"Find sci-fi movies released after 2000 and show the genre breakdown"*
- *"Search films for movies with 'war' in the title, sorted by year"*
- *"Show me the top 5 most recent films"*

**Indexing**
- *"Index this JSON into the books collection: [{"id": "1", "title": "1984", "author": "George Orwell"}]"*
- *"Create a new collection called products"*

**Managing**
- *"Is the films collection healthy?"*
- *"How many documents are in the films collection?"*
- *"Show me the schema for the films collection"*

## What you can do

### Tools

| Tool | Description |
|------|-------------|
| `search` | Full-text search with filtering, faceting, sorting, and pagination |
| `index-json-documents` | Index documents from a JSON string into a collection |
| `index-csv-documents` | Index documents from a CSV string into a collection |
| `index-xml-documents` | Index documents from an XML string into a collection |
| `index-markdown-documents` | Index a markdown document into a collection, extracting front matter, title, headings, and body text |
| `create-collection` | Create a collection (configSet, numShards, replicationFactor optional — default `_default`, `1`, `1`) |
| `list-collections` | List all available Solr collections |
| `get-collection-stats` | Get statistics and metrics for a collection |
| `check-health` | Check the health status of a collection |
| `add-fields` | Add fields to a collection schema (additive only; existing fields cannot be modified) |
| `add-field-types` | Add field types — custom analyzers, `DenseVectorField` for semantic search, etc. |
| `get-schema` | Retrieve schema information for a collection |

Every tool advertises MCP behavior hints (`readOnlyHint`, `destructiveHint`, `idempotentHint`) so clients can build sensible approval UX — `search` and the metadata tools are read-only, indexing is destructive but idempotent, schema modification is additive.

### Resources

| Resource URI | Description |
|--------------|-------------|
| `solr://collections` | List of all Solr collections in the cluster |
| `solr://{collection}/schema` | Schema definition for a collection (supports autocompletion) |

### Prompts

Slash-command-style workflow templates that walk the assistant through a canonical Solr workflow.

| Prompt | Arguments | Purpose |
|--------|-----------|---------|
| `explore-collections` | — | List collections and characterise each by stats and health |
| `setup-collection` | `name`, `purpose` (optional) | Pick configset / shards / replication factor, create the collection, verify it |
| `view-schema` | `collection` | Read-only schema walkthrough |
| `design-schema` | `collection`, `datasetDescription`, `sampleDocument` (optional) | Choose field types and apply additive schema changes |
| `index-data` | `collection`, `format` (`json` / `csv` / `xml`), `sample` (optional) | Pick the right indexing tool and confirm the result |
| `search-collection` | `collection`, `question` | Translate a natural-language question into a Solr query |

### Completions

The server implements MCP argument autocompletion, so clients can suggest valid values as you type:

- **Resource argument** — the `{collection}` segment of `solr://{collection}/schema` completes to live collection names.
- **Prompt arguments** — the `collection` argument of the `search-collection`, `index-data`, `view-schema`, and `design-schema` prompts completes to live collection names.

Suggestions are matched case-insensitively by prefix and capped per request.

## Configuration

The server reads configuration from environment variables. The essentials:

| Variable | Description | Default |
|----------|-------------|---------|
| `SOLR_URL` | Solr base URL | `http://localhost:8983/solr/` |
| `PROFILES` | Transport mode: `stdio` (default, for Claude Desktop) or `http` (remote / multi-client) | `stdio` |

Running in **HTTP mode** — OAuth2, CORS, and the `HTTP_SECURITY_ENABLED` toggle (secured by default) — is covered in the [security docs](docs/security/). Tracing and metrics env vars (`OTEL_SAMPLING_PROBABILITY`, `OTEL_TRACES_URL`) are covered in [Observability](docs/observability.md).

## Documentation

**Using it**
- [Quick start](#quick-start) · [Client setup](docs/clients/) — Claude Desktop, Claude Code, VS Code, Cursor, JetBrains, MCP Inspector
- [Observability](docs/observability.md) — OpenTelemetry traces, metrics, logs
- Security: [Deployment model (single-tenant)](docs/security/deployment-model.md) · [STDIO model](docs/security/stdio.md) · [HTTP model](docs/security/http.md) · OAuth2 setup: [Auth0](docs/security/auth0.md) · [Keycloak](docs/security/keycloak.md)

**Developing it**
- [Development guide](dev-docs/DEVELOPMENT.md) — build, run, test, IDE, native image, SBOM · [Architecture](dev-docs/ARCHITECTURE.md)
- [Deployment](dev-docs/DEPLOYMENT.md) — Docker images, the three-image matrix, registries, Kubernetes · [Troubleshooting](dev-docs/TROUBLESHOOTING.md)
- [GraalVM native image spec](dev-docs/graalvm-native-image.md) · [Contributing](CONTRIBUTING.md)

> **Container images:** published images are not yet available on a public registry. The Docker examples in the client guides use a **locally built** image — build it with `./gradlew jibDockerBuild` (produces `solr-mcp:latest`). See [Building Docker images](dev-docs/DEPLOYMENT.md#docker-images-with-jib).

## Community

- **Website:** https://solr.apache.org/mcp
- **Slack:** [`#solr-mcp`](https://the-asf.slack.com/archives/C09TVG3BM1P) in the `the-asf` workspace
- **Mailing lists:** Shared with Apache Solr — see [mailing lists](https://solr.apache.org/community.html#mailing-lists-chat)
- **Issues:** https://github.com/apache/solr-mcp/issues
- **Discussions:** https://github.com/apache/solr-mcp/discussions

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Acknowledgments

Built with [Spring AI MCP](https://spring.io/projects/spring-ai), [Apache Solr](https://solr.apache.org/), [Jib](https://github.com/GoogleContainerTools/jib), [Paketo Buildpacks](https://paketo.io/), [Testcontainers](https://www.testcontainers.org/), and [Spring AI MCP Security](https://github.com/spring-ai-community/mcp-security).
