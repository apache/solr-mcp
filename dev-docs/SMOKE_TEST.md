# solr-mcp — Quick Test (Claude Code · Claude Desktop · MCP Inspector)

A fast end-to-end smoke test for verifying a `solr-mcp` JAR works across the
three common MCP clients. Use it when validating a release candidate or a fresh build.

Every client needs the same three things: `command: java`, `args: -jar /path/to/solr-mcp-X.Y.Z.jar`, `env: SOLR_URL`.

## Step 1 — Start Solr with the techproducts example

```bash
bin/solr start -e techproducts
```

You can start any modern version of Solr, but if it's before Solr 10 make sure you start in Cloud mode.

## Step 2 — Register the server (pick your client)

**Claude Code**
```bash
claude mcp add solr-mcp --transport stdio \
  --env SOLR_URL=http://localhost:8983/solr \
  -- java -jar /path/to/solr-mcp-X.Y.Z.jar
claude mcp list        # confirm it connects   (/mcp inside a session)
```

**Claude Desktop** — add to `claude_desktop_config.json`, then restart:
```json
{ "mcpServers": { "solr-mcp": {
  "command": "java",
  "args": ["-jar", "/path/to/solr-mcp-X.Y.Z.jar"],
  "env": { "SOLR_URL": "http://localhost:8983/solr" }
} } }
```

**MCP Inspector**
```bash
SOLR_URL=http://localhost:8983/solr \
  npx @modelcontextprotocol/inspector java -jar /path/to/solr-mcp-X.Y.Z.jar
```

## Step 3 — Smoke test

Just ask in plain English.

```text
"what collections are available?"              → techproducts
"how many documents are in techproducts?"      → numDocs: 31
"show me all the electronics, priciest first"  → 12 hits
```
