# Codex

[OpenAI Codex CLI](https://developers.openai.com/codex/mcp) supports MCP servers over **STDIO** and **streamable HTTP**. Servers live in `~/.codex/config.toml` (or a project-scoped `.codex/config.toml` in a trusted project) under `[mcp_servers.<name>]`, and you can manage them with `codex mcp add`. The Codex IDE extension shares the same configuration.

The four ways to run Solr MCP with Codex:

| | JAR | Docker |
|---|---|---|
| **STDIO** | Codex launches `java -jar …` | Codex launches `docker run -i …` |
| **HTTP** | You run `java -jar …` with `PROFILES=http` | You run `docker run -p 8080:8080 …` with `PROFILES=http` |

***

## CLI Syntax ##

The general form of `codex mcp add` is:

```bash
codex mcp add <name> [--env KEY=VALUE]... -- <command> [args...]   # STDIO
codex mcp add <name> --url <url> [--bearer-token-env-var VAR]      # HTTP
```

For a **STDIO** server, pass any `--env KEY=VALUE` options (repeatable) before the `--`, then the launch command after it. `--env` is only valid for STDIO servers; `--bearer-token-env-var` only for HTTP ones.

***

## STDIO Mode (Recommended) ##

### CLI ###

```bash
# JAR
codex mcp add solr-mcp \
    --env SOLR_URL=http://localhost:8983/solr/ \
    -- java -jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar

# Docker (local image — build first with ./gradlew jibDockerBuild)
codex mcp add solr-mcp \
    -- docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

### `config.toml` ###

Equivalent to the commands above; add to `~/.codex/config.toml`:

**JAR:**

```toml
[mcp_servers.solr-mcp]
command = "java"
args = ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"]

[mcp_servers.solr-mcp.env]
SOLR_URL = "http://localhost:8983/solr/"
```

**Docker (local image):**

```toml
[mcp_servers.solr-mcp]
command = "docker"
args = ["run", "-i", "--rm",
        "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
        "solr-mcp:latest"]
```

**Linux users**: add `"--add-host=host.docker.internal:host-gateway"` to the `args` array (or `--add-host=host.docker.internal:host-gateway` before the image name in the CLI form).

If the server is slow to start (a cold JVM, or Docker pulling the image on first use), raise the handshake limit with `startup_timeout_sec = 60` in the same table. Pull the image once beforehand to avoid it.

***

## HTTP Mode ##

Start the server in HTTP mode first, in a terminal you keep open. For a local, unsecured server set `HTTP_SECURITY_ENABLED=false` — HTTP mode requires OAuth2 by default (see the [HTTP security model](../security/http.md)):

```bash
# JAR
PROFILES=http HTTP_SECURITY_ENABLED=false SOLR_URL=http://localhost:8983/solr/ \
    java -jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar

# Docker (local image)
docker run -p 8080:8080 --rm \
    -e PROFILES=http -e HTTP_SECURITY_ENABLED=false \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

Either way the endpoint is `http://localhost:8080/mcp`; Codex does not care which one is behind it. Then:

### CLI ###

```bash
codex mcp add solr-mcp --url http://localhost:8080/mcp
```

### `config.toml` ###

```toml
[mcp_servers.solr-mcp]
url = "http://localhost:8080/mcp"
```

Verify with `codex mcp list`, or `/mcp` inside a Codex session. The server must already be running when Codex starts.

### Secured HTTP (OAuth2) ###

A secured server never answers an anonymous `/mcp` request with `401`, so Codex will not start an OAuth login on its own — give it the access token explicitly. Export the token in the shell that launches Codex and name the variable:

```bash
export SOLR_MCP_TOKEN=<access-token>
codex mcp add solr-mcp --url http://localhost:8080/mcp --bearer-token-env-var SOLR_MCP_TOKEN
```

```toml
[mcp_servers.solr-mcp]
url = "http://localhost:8080/mcp"
bearer_token_env_var = "SOLR_MCP_TOKEN"
```

The `aud` claim of the token must match the URL Codex dials. Codex re-reads the variable each time it starts, so restart it after refreshing an expired token. See the [HTTP security model](../security/http.md) for server-side OAuth2 setup, and the [Keycloak](../security/keycloak.md) and [Auth0](../security/auth0.md) guides for obtaining a token.

### Troubleshooting ###

**`codex mcp list` shows the server and Codex finds its tools, but every call returns `Access Denied`.** The server is running with security on (the default in HTTP mode) and Codex is not sending a token. This is not a protocol-version problem — the server negotiates every MCP version. Either restart the server with `HTTP_SECURITY_ENABLED=false` for local use, or follow the secured-HTTP steps above. Confirm which one you have without involving Codex:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list-collections","arguments":{}}}'
```

`Access Denied` in the result means security is on.
