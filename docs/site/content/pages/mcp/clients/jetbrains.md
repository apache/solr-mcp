Title: JetBrains IDEs
URL: mcp/clients/jetbrains.html
save_as: mcp/clients/jetbrains.html
template: mcp/client

[JetBrains IDEs](https://www.jetbrains.com/) (IntelliJ IDEA, WebStorm, PyCharm, etc.) support MCP servers through the AI Assistant plugin.

***

## STDIO Mode (Recommended) ##

### Project Configuration (`.junie/mcp/mcp.json`) ###

Create `.junie/mcp/mcp.json` in your project root:

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

### IDE Settings ###

1. Open **Settings** (<kbd>Cmd+,</kbd> / <kbd>Ctrl+Alt+S</kbd>)
2. Navigate to **Tools** > **AI Assistant** > **MCP Servers**
3. Click **Add** (`+`)
4. Configure:
    * **Name**: `solr-mcp`
    * **Transport**: `STDIO`
    * **Command**: `java`
    * **Arguments**: `-jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar`

***

## HTTP Mode ##

Start the server first (see [Running the Server](https://github.com/apache/solr-mcp#running-the-server)), then:

```json
{
  "mcpServers": {
    "solr-mcp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Or in IDE Settings, select **SSE** transport and enter `http://localhost:8080/mcp` as the URL.

With security enabled (the default), this server does not answer an anonymous `/mcp` request with `401`, so the IDE will not start an OAuth flow by itself: it connects, lists the tools, and every call returns `Access Denied`. Supply a token from your identity provider. The portable way is to register the server as a STDIO command through `mcp-remote`, which forwards the header on every request and expands `${TOKEN}` from `env`:

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

The token's `aud` claim must contain the exact URL above, and it expires — see the [Security](/mcp/security.html) for obtaining one and the lifetime knobs.

MCP support requires the AI Assistant plugin. See the [JetBrains MCP documentation](https://www.jetbrains.com/help/idea/model-context-protocol.html) for the latest configuration format.
