Title: Claude Desktop
URL: mcp/clients/claude-desktop.html
save_as: mcp/clients/claude-desktop.html
template: mcp/client

[Claude Desktop](https://claude.ai/download) is Anthropic's desktop application for Claude. It supports MCP servers via STDIO and HTTP transports.

### Configuration File

* **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
* **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

Restart Claude Desktop after any configuration change.

***

## STDIO Mode (Recommended) ##

STDIO mode communicates via stdin/stdout. This is the simplest setup for local use.

### JAR ###

Requires Java 25+ and a [built JAR](https://github.com/apache/solr-mcp#running-the-server) (`./gradlew build`).

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

HTTP mode connects to a running MCP server via REST endpoints. Start the server first, then configure Claude Desktop to connect using `mcp-remote`.

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

See [Security](/mcp/security.html) for server-side OAuth2 setup.
