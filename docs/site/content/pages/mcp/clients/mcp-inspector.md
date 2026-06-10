Title: MCP Inspector
URL: mcp/clients/mcp-inspector.html
save_as: mcp/clients/mcp-inspector.html
template: mcp/client

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is a web-based tool for testing and debugging MCP servers. It lets you browse available tools, invoke them interactively, and inspect responses.

### Install ###

```bash
npx @modelcontextprotocol/inspector
```

This starts the Inspector UI at `http://localhost:6274`.

***

## STDIO Mode ##

1. In MCP Inspector, select **STDIO** transport
2. **Command**: `java`
3. **Arguments**: `-jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar`
4. Click **Connect**

***

## HTTP Mode ##

1. Start the server in HTTP mode:

        # JAR
        PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar

        # Or Gradle
        PROFILES=http ./gradlew bootRun

        # Or Docker (local image — build first with ./gradlew jibDockerBuild)
        docker run -p 8080:8080 --rm \
            -e PROFILES=http \
            -e SOLR_URL=http://host.docker.internal:8983/solr/ \
            solr-mcp:latest

2. In MCP Inspector, enter: `http://localhost:8080/mcp`
3. Click **Connect**

***

## OAuth2 ##

When OAuth2 is enabled on the server, configure the Inspector's OAuth settings before connecting:

1. Click the **OAuth** settings in the Inspector
2. Enter your provider's Authorization URL, Token URL, Client ID, and Redirect URI (`http://localhost:6274/oauth/callback`)
3. Complete the OAuth flow
4. The Inspector will include the Bearer token in all subsequent requests

See [Security](/mcp/security.html) for server-side OAuth2 setup with Auth0 and Keycloak.
