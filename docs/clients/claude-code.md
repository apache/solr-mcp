# Claude Code

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

Start the server in HTTP mode first (`PROFILES=http java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar`, or `PROFILES=http ./gradlew bootRun`), then:

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

### Secured HTTP (OAuth2) ###

Claude Code detects the OAuth2 challenge from the server and initiates the authorization flow automatically. The configuration is the same as unsecured HTTP.

See the [HTTP security model](../security/http.md) for server-side OAuth2 setup.
