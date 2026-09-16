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

**Linux users** (Docker option): add `--add-host=host.docker.internal:host-gateway` to the `docker run` command.

***

## Secured HTTP (bearer token) ##

The Inspector starts its OAuth flow only when the server answers `401`. This server never does that on `/mcp` — the handshake is anonymous and `@PreAuthorize` denies inside each tool — so with the URL alone the Inspector connects, lists every tool, and every call returns `Access Denied`. Pass a token from your identity provider with `--header` instead. It applies to the ad-hoc server the Inspector opens with:

```bash
# Web UI
npx @modelcontextprotocol/inspector --server-url http://localhost:8080/mcp --transport http \
    --header "Authorization: Bearer $TOKEN"

# CLI: one tool call, no browser
npx @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
    --header "Authorization: Bearer $TOKEN" --method tools/call --tool-name list-collections
```

The token's `aud` claim must contain the exact URL given here (`http://localhost:8080/mcp`), and when it expires calls fail with `401` until you relaunch with a fresh one.

See [Security](/mcp/security.html) for server-side OAuth2 setup with Auth0 and Keycloak.
