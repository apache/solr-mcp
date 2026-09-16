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

### Secured HTTP (bearer token) ###

`mcp-remote` starts an OAuth flow only when the server answers `401`. This server never does that on `/mcp` (the handshake is anonymous; `@PreAuthorize` denies inside each tool), so with the URL alone Claude Desktop connects, lists the tools, and every call returns `Access Denied`. Pass a token from your identity provider with `--header`; `mcp-remote` expands `${TOKEN}` from the `env` block so the secret stays out of the argument list:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "npx",
      "args": [
        "mcp-remote", "http://localhost:8080/mcp", "--allow-http",
        "--header", "Authorization: Bearer ${TOKEN}"
      ],
      "env": { "TOKEN": "<access token>" }
    }
  }
}
```

The `--allow-http` flag is needed for `http://` URLs (development). Omit it in production with HTTPS. The token's `aud` claim must contain the exact URL given here, and when it expires calls fail with `401` until you paste a fresh token and restart Claude Desktop.

See the [HTTP security model](../security/http.md) for server-side OAuth2 setup.
