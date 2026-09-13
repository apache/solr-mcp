# Claude Desktop

[Claude Desktop](https://claude.ai/download) is Anthropic's desktop application for Claude. It supports MCP servers via STDIO and HTTP transports.

### Configuration File

* **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
* **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

Restart Claude Desktop after any configuration change.

***

## STDIO Mode (Recommended) ##

STDIO mode communicates via stdin/stdout. This is the simplest setup for local use.

### JAR ###

Requires Java 25+ and a [built JAR](../../README.md#quick-start) (`./gradlew build`).

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": {
        "SOLR_URL": "http://localhost:8983/solr/"
      }
    }
  }
}
```

### Docker (local image) ###

Build the image first: `./gradlew jibDockerBuild`

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

**Linux users**: add `"--add-host=host.docker.internal:host-gateway"` to the `args` array.

***

## HTTP Mode ##

HTTP mode connects to a running MCP server over streamable HTTP. Start the server first, then configure Claude Desktop to connect through the `mcp-remote` bridge.

Do not use Claude Desktop's **Settings → Connectors → Add custom connector** for a local server: custom connectors are contacted from Anthropic's cloud, not from your machine, so a `localhost` URL is unreachable there and would need a public HTTPS endpoint with OAuth2 enabled. `mcp-remote` runs locally as a STDIO server and forwards to the HTTP endpoint on your machine, so no tunnel is needed.

### Start the Server ###

```bash
# JAR
PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar

# Or Gradle
PROFILES=http ./gradlew bootRun

# Or Docker (local image)
docker run -p 8080:8080 --rm \
    -e PROFILES=http \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

The HTTP transport is secured by default and answers 401 until an OAuth2 issuer is configured. For a local experiment on your own machine only, add `HTTP_SECURITY_ENABLED=false` to the server's environment; see the [HTTP security model](../security/http.md) before exposing it to anyone else.

### Configure Claude Desktop ###

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

### Secured HTTP (OAuth2) ###

When OAuth2 is enabled on the server, `mcp-remote` handles the authorization flow automatically&mdash;it discovers the authorization server and opens a browser for consent.

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "npx",
      "args": ["mcp-remote", "http://localhost:8080/mcp", "--allow-http"]
    }
  }
}
```

The `--allow-http` flag is needed for `http://` URLs (development). Omit it in production with HTTPS.

See the [HTTP security model](../security/http.md) for server-side OAuth2 setup.
