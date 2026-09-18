# Client Setup Guides

Per-client instructions for connecting an MCP client to the Solr MCP Server.
Each guide covers the transports the client supports: **STDIO** (the server
runs as a local subprocess — JAR or Docker) and **HTTP** (connect to a running
server's streamable HTTP endpoint at `http://localhost:8080/mcp`).

| Client | Guide |
|--------|-------|
| Claude Desktop | [claude-desktop.md](claude-desktop.md) |
| Claude Code | [claude-code.md](claude-code.md) |
| VS Code / GitHub Copilot | [vs-code.md](vs-code.md) |
| Cursor | [cursor.md](cursor.md) |
| JetBrains IDEs | [jetbrains.md](jetbrains.md) |
| MCP Inspector | [mcp-inspector.md](mcp-inspector.md) |

Before connecting, start Solr and build the server — see the
[Quick start](../../README.md#quick-start). For OAuth2 on the HTTP transport,
see the [security docs](../security/).
