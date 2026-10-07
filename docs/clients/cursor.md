# Cursor

[Cursor](https://cursor.sh/) supports MCP servers natively via project configuration files or the Cursor Settings UI.

***

## STDIO Mode (Recommended) ##

### Project Configuration (`.cursor/mcp.json`) ###

Create `.cursor/mcp.json` in your project root:

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

**Docker (local image &mdash; build first with `./gradlew jibDockerBuild`):**

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

### Cursor Settings UI ###

1. Open **Cursor Settings** (gear icon or <kbd>Cmd+,</kbd> / <kbd>Ctrl+,</kbd>)
2. Navigate to **Features** > **MCP Servers**
3. Click **Add New MCP Server**
4. Enter:
    * **Name**: `solr-mcp`
    * **Type**: `command`
    * **Command**: `java -jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar`

***

## HTTP Mode ##

Start the server in HTTP mode first (`PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar`, or `PROFILES=http ./gradlew bootRun`), then:

The HTTP transport is secured by default: without a bearer token the client still connects and lists the tools, but every tool call returns `Access Denied`. The server never answers `/mcp` with `401`, so no OAuth login starts. For a local experiment on your own machine only, add `HTTP_SECURITY_ENABLED=false` to the server's environment; see the [HTTP security model](../security/http.md) before exposing it to anyone else.

```json
{
  "mcpServers": {
    "solr-mcp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

With security on, pass a token from your identity provider in `headers`; Cursor expands `${env:…}` so the secret stays out of the file:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer ${env:SOLR_MCP_TOKEN}" }
    }
  }
}
```

The token's `aud` claim must contain the exact `url` above, and it expires — see the [HTTP security model](../security/http.md#connecting-an-mcp-client-to-a-secured-server) for obtaining one and the lifetime knobs.

See the [Cursor MCP documentation](https://docs.cursor.com/context/model-context-protocol) for the latest configuration format.
