# MCP 2026-07-28 protocol (`MCP_PROTOCOL=modern`)

Solr MCP Server can serve the
[2026-07-28 revision](https://modelcontextprotocol.io/specification/2026-07-28) of the
Model Context Protocol, using the modern server that ships with Spring AI 2.1 and the
MCP Java SDK 2.2.

It is **opt-in**. The 2026-07-28 revision is not wire-compatible with the 2025 revisions,
and most clients in use today (MCP Inspector, Claude Desktop, the Java, Python and TypeScript
SDK clients) still speak the 2025 revisions. Turn it on only when your clients speak the new
revision.

| Transport | Default (`MCP_PROTOCOL` unset) | `MCP_PROTOCOL=modern` |
|-----------|--------------------------------|-----------------------|
| HTTP (`PROFILES=http`) | `stateless` streamable HTTP, 2025 revisions | 2026-07-28 revision on `POST /mcp` |
| STDIO (default profile) | `streamable`, 2025 revisions | 2026-07-28 revision, newline-delimited JSON-RPC |

## Turning it on

```bash
# HTTP
PROFILES=http MCP_PROTOCOL=modern SOLR_URL=http://localhost:8983/solr/ \
  java -jar build/libs/solr-mcp-<version>.jar

# STDIO
MCP_PROTOCOL=modern SOLR_URL=http://localhost:8983/solr/ \
  java -jar build/libs/solr-mcp-<version>.jar
```

`MCP_PROTOCOL` sets `spring.ai.mcp.server.protocol`, so
`-Dspring.ai.mcp.server.protocol=modern` works the same way.

Every tool, resource, resource template, prompt and completion is served unchanged in
either mode, and the `@PreAuthorize` scopes on tools apply the same way.

## What changes for clients

- **No `initialize` handshake and no session.** A client calls `server/discover` to learn
  the server's capabilities, `serverInfo` and instructions. There is no `Mcp-Session-Id`,
  and `ping` is gone.
- **Every request carries `_meta`** with the protocol version, the client's capabilities and
  the client's info:

  ```json
  {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "list-collections",
      "arguments": {},
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {},
        "io.modelcontextprotocol/clientInfo": { "name": "my-client", "version": "1.0.0" }
      }
    }
  }
  ```

- **Over HTTP, requests carry routing headers.** `MCP-Protocol-Version: 2026-07-28` and
  `Mcp-Method` on every request, plus `Mcp-Name` (the tool, prompt or resource URI) on
  `tools/call`, `prompts/get` and `resources/read`. A header that doesn't match the body is
  rejected with HTTP 400 and JSON-RPC error `-32020`.

## CORS

The HTTP profile's CORS configuration allows the `Mcp-Method`, `Mcp-Name` and
`MCP-Protocol-Version` headers, so browser-based clients can send them from the allowed
origins.

## Running more than one instance

When a request takes more than one round trip, the modern server seals a `requestState`
for the client to send back. Unless a secret is set, each instance seals it with its own
random key, so the follow-up must reach the same instance. Behind a load balancer, give every
instance the same secret of at least 32 bytes:

```properties
spring.ai.mcp.server.modern.request-state-secret=${MCP_REQUEST_STATE_SECRET}
```

## Native images

The transport is chosen by `@ConditionalOnProperty` conditions, which a GraalVM native image
fixes at build time. To get a native image that serves the modern protocol, set
`MCP_PROTOCOL=modern` when running `nativeCompile`. This has not been tested yet.

## Spring AI 2.1.0-RC2 STDIO workaround

In Spring AI 2.1.0-RC2, the modern STDIO transport has two problems when the server runs
without a web server, as it does in the STDIO profile:

1. The SDK's stdin reader runs on daemon threads and Spring AI does not block on it, so the
   JVM exits as soon as startup finishes and the first request gets no answer.
2. The reader starts before the application context has finished starting, so a request sent
   right at launch can fail with `-32602` (`clientCapabilities is malformed`), `-32603`, or no
   answer at all.

`ModernStdioTransportConfiguration` replaces the transport bean in modern STDIO mode only. It
starts reading stdin once the application is ready and keeps the JVM alive on a non-daemon
thread until stdin closes. It can be removed once Spring AI fixes both upstream.
