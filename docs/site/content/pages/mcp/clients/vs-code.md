Title: VS Code / GitHub Copilot
URL: mcp/clients/vs-code.html
save_as: mcp/clients/vs-code.html
template: mcp/client

[VS Code](https://code.visualstudio.com/) supports MCP servers through built-in MCP support (VS Code 1.99+). Solr MCP tools are available in GitHub Copilot Chat when using Agent mode.

***

## STDIO Mode (Recommended) ##

### Workspace Configuration (`.vscode/mcp.json`) ###

Create `.vscode/mcp.json` in your project root:

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

**Docker (local image &mdash; build first with `./gradlew jibDockerBuild`):**

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

### User Settings (`settings.json`) ###

Open VS Code Settings (JSON) and add:

```json
{
  "mcp": {
    "servers": {
      "solr-mcp": {
        "type": "stdio",
        "command": "java",
        "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
        "env": { "SOLR_URL": "http://localhost:8983/solr/" }
      }
    }
  }
}
```

***

## HTTP Mode ##

Start the server first (see [Running the Server](https://github.com/apache/solr-mcp#running-the-server)), then:

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

The configuration is the same for secured and unsecured HTTP. VS Code handles the MCP OAuth2 flow automatically.

See the [VS Code MCP documentation](https://code.visualstudio.com/docs/copilot/chat/mcp-servers) for the latest configuration format.
