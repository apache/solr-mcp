Title: Zed
URL: mcp/clients/zed.html
save_as: mcp/clients/zed.html
template: mcp/client

[Zed](https://zed.dev/) supports MCP servers natively. Local servers run as a subprocess; remote servers are reached over streamable HTTP. Both are configured from **Settings → AI → MCP Servers** (the `agent: open settings` action) or by editing `settings.json` (`zed: open settings file`).

***

## STDIO Mode (Recommended) ##

### Settings UI ###

**Settings → AI → MCP Servers → Add Server → Add Local Server**, then:

| Field | JAR | Docker (local image, build first with `./gradlew jibDockerBuild`) |
|---|---|---|
| Server Name | `solr-mcp` | `solr-mcp` |
| Command | `java` | `docker` |
| Arguments | `-jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar` | `run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest` |
| Environment Variables | `SOLR_URL` = `http://localhost:8983/solr/` | |

Arguments go in one field, space-separated, not as a JSON array. The form writes the same `settings.json` entry shown below.

**Linux users**: add `--add-host=host.docker.internal:host-gateway` to the Docker arguments.

### `settings.json` ###

**JAR:**

```json
{
  "context_servers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Docker:**

```json
{
  "context_servers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
               "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
               "solr-mcp:latest"],
      "env": {}
    }
  }
}
```

`command` is a plain string with `args` and `env` alongside it; there is no `source` key, and adding one makes Zed's settings validator reject the entry (verified on Zed 1.16.2).

### Timeout ###

Zed gives a server 60 seconds to start. A first Docker run has to pull the image, which can outrun that on slow networks and then looks like a broken configuration. Pull the image once beforehand (`docker pull solr-mcp:latest`, or the registry image you use) and the problem disappears.

***

## HTTP Mode ##

Start the server in HTTP mode first (`PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar`, or `PROFILES=http ./gradlew bootRun`), then either **Settings → AI → MCP Servers → Add Server → Add Remote Server** with the URL `http://localhost:8080/mcp`, or:

```json
{
  "context_servers": {
    "solr-mcp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

The HTTP transport is secured by default and answers 401 until an OAuth2 issuer is configured. For a local experiment on your own machine only, add `HTTP_SECURITY_ENABLED=false` to the server's environment; see [Security](/mcp/security.html) before exposing it to anyone else.

The configuration is the same for secured and unsecured HTTP. When the server answers 401, Zed starts the standard MCP OAuth flow; with security disabled it connects without prompting. To send a fixed token instead, add `"headers": { "Authorization": "Bearer <token>" }` to the entry.

See the [Zed MCP documentation](https://zed.dev/docs/ai/mcp) for the latest configuration format.
