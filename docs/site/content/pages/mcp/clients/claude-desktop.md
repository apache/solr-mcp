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

### Configuration ###

Requires Java 25+ and a [built JAR](https://github.com/apache/solr-mcp#running-the-server) (`./gradlew build`) for the JAR option.

**JAR:**

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

**Docker (local image — build first with `./gradlew jibDockerBuild`):**

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

# Or Docker (local image — build first with ./gradlew jibDockerBuild)
docker run -p 8080:8080 --rm \
    -e PROFILES=http \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

**Linux users** (Docker option): add `--add-host=host.docker.internal:host-gateway` to the `docker run` command.

The HTTP transport is secured by default: without a bearer token the client still connects and lists the tools, but every tool call returns `Access Denied`. The server never answers `/mcp` with `401`, so no OAuth login starts. For a local experiment on your own machine only, add `HTTP_SECURITY_ENABLED=false` to the server's environment; see [Security](/mcp/security.html) before exposing it to anyone else.

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

With security on, pass a token from your identity provider with `--header`; `mcp-remote` expands `${TOKEN}` from the `env` block so the secret stays out of the argument list:

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

See [Security](/mcp/security.html) for server-side OAuth2 setup.
