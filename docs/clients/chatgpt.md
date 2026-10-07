# ChatGPT

[ChatGPT](https://chatgpt.com/) can use MCP servers through **developer mode** (see OpenAI's [developer mode guide](https://developers.openai.com/api/docs/guides/developer-mode) and [connecting a server](https://developers.openai.com/plugins/deploy/connect-chatgpt)). It differs from the other clients in this folder in three ways:

- **HTTP only.** ChatGPT connects to a remote MCP server over streamable HTTP. It cannot launch a local process, so there is no STDIO setup and no `java -jar` / `docker run -i` configuration to give it.
- **The server must be reachable from OpenAI.** `http://localhost:8080/mcp` does not work. Use a public HTTPS endpoint, a Secure MCP Tunnel, or a development tunnel such as ngrok or `cloudflared`.
- **Authentication is OAuth 2.1 or none.** ChatGPT does not support static bearer tokens or custom headers, so the `Authorization: Bearer …` approach used for [Claude Code](claude-code.md) is not available.

Developer mode is available on the web for Pro, Plus, Business, Enterprise and Education plans, and workspace admins may need to enable it.

***

## Run the server

Start Solr MCP in HTTP mode, from the JAR or the Docker image. Either way the endpoint is `http://localhost:8080/mcp`.

```bash
# JAR
PROFILES=http HTTP_SECURITY_ENABLED=false SOLR_URL=http://localhost:8983/solr/ \
    java -jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar

# Docker (local image — build first with ./gradlew jibDockerBuild)
docker run -p 8080:8080 --rm \
    -e PROFILES=http -e HTTP_SECURITY_ENABLED=false \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

> **Warning — read before exposing this.** `HTTP_SECURITY_ENABLED=false` makes every MCP tool anonymous, including the ones that index documents and modify schemas. Putting that behind a public tunnel lets anyone who learns the URL read and change your Solr data. Use it only for a short demo against a throwaway Solr, and stop the tunnel afterwards. See the [HTTP security model](../security/http.md).

## Expose it over HTTPS

Any HTTPS forwarder works. For example, with ngrok:

```bash
ngrok http 8080
```

Use the `https://…` address it prints, with `/mcp` appended.

## Add it in ChatGPT

1. **Settings → Security and login**: turn on **Developer mode**.
2. Open **ChatGPT Plugins**, select the **+** button and create a developer-mode app.
3. Enter a name and description, and under **Connection** give the public endpoint, ending in `/mcp`, for example `https://<your-tunnel>/mcp`. For a private server, choose **Tunnel** instead and select an available Secure MCP Tunnel.
4. Choose **No authentication**, create the connection and review the discovered tools. There should be twelve.
5. Start a chat, enable the app, and ask *"What Solr collections are available?"*.

ChatGPT asks for confirmation before tool calls it treats as writes. Tools without a `readOnlyHint` annotation are treated as writes, so expect prompts for most Solr MCP tools. After restarting the server, use **Refresh** on the app so ChatGPT re-reads its tools.

## Secured HTTP (OAuth2)

Not supported yet. ChatGPT's OAuth 2.1 flow expects the MCP server to publish protected-resource metadata and to challenge anonymous requests, then registers itself with the authorization server through CIMD or dynamic client registration. Solr MCP's secured mode is built around a pre-issued JWT bearer token and does not answer an anonymous `/mcp` request with `401` (see the [HTTP security model](../security/http.md)), so ChatGPT has nothing to start a login from. Until that is addressed, ChatGPT can only be used with security off, which is why the tunnel above should stay short-lived.

If you need ChatGPT against a shared Solr, put the server behind your own OAuth-capable gateway rather than exposing it directly.
