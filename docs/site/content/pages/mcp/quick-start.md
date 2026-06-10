Title: Quick Start
URL: mcp/quick-start.html
save_as: mcp/quick-start.html
template: mcp/quick-start

## Prerequisites ##

* Java 25+ ([Eclipse Temurin](https://adoptium.net/) recommended)
* [Docker](https://docs.docker.com/get-docker/) and Docker Compose
* An MCP client &mdash; this guide uses [Claude Desktop](https://claude.ai/download), but any MCP-compatible client works. See [Adding to AI Clients](/mcp/clients/claude-desktop.html) for other options.

## Start Solr with Sample Data ##

Clone the repository and start Solr in SolrCloud mode:

```bash
git clone https://github.com/apache/solr-mcp.git
cd solr-mcp
docker compose up -d
```

This starts Solr with ZooKeeper and creates two sample collections pre-loaded with data:

* **films** &mdash; 1,100+ movie records with titles, directors, genres, and release dates
* **books** &mdash; empty collection ready for indexing

Wait ~30 seconds for Solr to fully initialize. Verify at [http://localhost:8983/solr/](http://localhost:8983/solr/).

## Build the Server ##

```bash
./gradlew build
```

This produces `build/libs/solr-mcp-1.0.0-SNAPSHOT.jar`.

## Configure Your MCP Client ##

Add the following to your Claude Desktop configuration file:

* **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
* **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

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

Restart Claude Desktop after saving.

**Alternatively**, you can use a local Docker image:

```bash
./gradlew jibDockerBuild
```

Then configure Claude Desktop with:

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

**Linux users**: add `"--add-host=host.docker.internal:host-gateway"` to the args array.

## Native Image (experimental) ##

Opt-in GraalVM native image builds are available for both **STDIO** and **HTTP** transports. Native binaries start in milliseconds and use substantially less memory than the JVM image. Spring AOT bakes a profile-specific bean graph at build time, so each native image is single-transport — pick the one that matches your client.

```bash
# Build the native STDIO image
./gradlew bootBuildImage -Pnative
# Produces: solr-mcp:1.0.0-SNAPSHOT-native-stdio (also tagged :latest-native-stdio)

# Build the native HTTP image
./gradlew bootBuildImage -Pnative -Pprofile=http
# Produces: solr-mcp:1.0.0-SNAPSHOT-native-http (also tagged :latest-native-http)
```

Configure Claude Desktop with the native STDIO image:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
               "solr-mcp:latest-native-stdio"]
    }
  }
}
```

For dual-transport support from a single image (e.g. switch between modes via `PROFILES` env var without rebuilding), use the JVM Jib image above. The JVM image trades startup time and memory footprint for transport flexibility.

## Try It Out ##

Open Claude Desktop and try these prompts:

* *"Search the films collection for movies directed by Steven Spielberg"*
* *"What collections are available in Solr?"*
* *"Show me the schema for the films collection"*
* *"Find all sci-fi movies released after 2000 and show the genre breakdown"*
* *"Index this JSON into the books collection: [{"id": "1", "title": "The Great Gatsby", "author": "F. Scott Fitzgerald"}]"*

***

## Next Steps ##

* **[Adding to AI Clients](/mcp/clients/claude-desktop.html)** &mdash; configure Claude Code, VS Code, Cursor, JetBrains, or MCP Inspector
* **[Features](/mcp/features.html)** &mdash; explore all available tools and resources
* **[Security](/mcp/security.html)** &mdash; set up OAuth2 authentication for HTTP mode
* **[Observability](/mcp/observability.html)** &mdash; enable tracing, metrics, and logging
