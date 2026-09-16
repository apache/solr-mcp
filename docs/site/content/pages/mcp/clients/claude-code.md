Title: Claude Code
URL: mcp/clients/claude-code.html
save_as: mcp/clients/claude-code.html
template: mcp/client

[Claude Code](https://docs.anthropic.com/en/docs/claude-code) is Anthropic's CLI tool for Claude. It supports MCP servers via the `claude mcp add` command or a `.mcp.json` project file.

***

## CLI Syntax ##

The general form of `claude mcp add` is (see [Claude Code MCP docs](https://code.claude.com/docs/en/mcp)):

```bash
claude mcp add [options] <name> <commandOrUrl> [args...]
```

The server `<name>` comes first. For a **STDIO** server, pass any `-e KEY=value` options (repeatable) after the name, then `--`, then the launch command. The `--` stops Claude Code from reparsing the server's own flags as its own options, and `-e` stops consuming tokens at the `--`:

```bash
claude mcp add <name> -e KEY=value -- <command> [args...]
```

For an **HTTP** server, no `--` is needed — pass the URL with `--transport http`:

```bash
claude mcp add --transport http <name> <url>
```

***

## STDIO Mode (Recommended) ##

### CLI ###

```bash
# JAR
claude mcp add solr-mcp \
    -e SOLR_URL=http://localhost:8983/solr/ \
    -- java -jar /absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar

# Docker (local image — build first with ./gradlew jibDockerBuild)
claude mcp add solr-mcp \
    -- docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

### `.mcp.json` ###

Add to your project root:

**JAR:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-1.0.0-SNAPSHOT.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Docker (local image):**

```json
{
  "mcpServers": {
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

**Linux users**: add `"--add-host=host.docker.internal:host-gateway"` to the `args` array.

***

## HTTP Mode ##

Start the server first (see [Running the Server](https://github.com/apache/solr-mcp#running-the-server)), then:

### CLI ###

```bash
claude mcp add --transport http solr-mcp http://localhost:8080/mcp
```

### `.mcp.json` ###

```json
{
  "mcpServers": {
    "solr-mcp": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### Secured HTTP (bearer token) ###

Claude Code starts an OAuth flow only when a server answers `401`/`403`. This server never does that on `/mcp` — the handshake is anonymous and `@PreAuthorize` denies inside each tool — so with the URL alone Claude Code shows the server as **connected** and every tool call returns `Access Denied`. Pass a token from your identity provider instead:

```bash
claude mcp add --transport http solr-mcp http://localhost:8080/mcp \
    --header "Authorization: Bearer $TOKEN"
```

The token's `aud` claim must contain the exact URL you register here (`http://localhost:8080/mcp`), and when it expires the connection fails rather than re-authenticating; re-run `claude mcp add` with a fresh token. See [Security](/mcp/security.html) for obtaining a token.
