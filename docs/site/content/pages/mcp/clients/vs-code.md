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
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

With security enabled (the default), this server does not answer an anonymous `/mcp` request with `401`, so VS Code will not start an OAuth flow by itself: it connects, lists the tools, and every call returns `Access Denied`. Pass a token from your identity provider in `headers`; an `inputs` entry prompts for it once and keeps it out of the file:

```json
{
  "inputs": [
    { "type": "promptString", "id": "solr-mcp-token", "description": "Solr MCP access token", "password": true }
  ],
  "servers": {
    "solr-mcp": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer ${input:solr-mcp-token}" }
    }
  }
}
```

The token's `aud` claim must contain the exact `url` above, and it expires — see the [Security](/mcp/security.html) for obtaining one and the lifetime knobs.

See the [VS Code MCP documentation](https://code.visualstudio.com/docs/copilot/chat/mcp-servers) for the latest configuration format.
