Title: Cursor
URL: mcp/clients/cursor.html
save_as: mcp/clients/cursor.html
template: mcp/client

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

The configuration is the same for secured and unsecured HTTP. Cursor handles the MCP OAuth2 flow automatically.

See the [Cursor MCP documentation](https://docs.cursor.com/context/model-context-protocol) for the latest configuration format.
