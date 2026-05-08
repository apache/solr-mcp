# STDIO Transport — Security Model

This document captures the security posture of the Solr MCP server when run in
**STDIO mode** (the default), with citations to the primary specifications and
framework guidance that justify it.

## TL;DR

In STDIO mode the server has **no in-process authentication or authorization,
and that is intentional and spec-aligned**. Trust is inherited from the OS user
that launched the process. No code changes are required for STDIO security.

## Why STDIO has no auth layer

| Property | Where it's set | Why it's safe |
|---|---|---|
| No network listener | `application-stdio.properties` → `spring.main.web-application-type=none` | No socket exists for a remote attacker to reach |
| Communication is stdin/stdout only | MCP framing per spec | Only the parent process (e.g. Claude Desktop) can write to `stdin` |
| Trust boundary = OS process owner | Launcher runs the binary | Same model as any local CLI; OS user permissions are the auth |
| Spring Security autoconfig disabled | `application-stdio.properties` excludes `SecurityAutoConfiguration` and `ManagementWebSecurityAutoConfiguration` | Belt-and-suspenders; the filter chain has nothing to do without a servlet container |
| `stdout` is reserved for JSON-RPC | `logback.xml` + empty `logging.pattern.console` (see [Logging Architecture in CLAUDE.md](../../CLAUDE.md#logging-architecture)) | Prevents log lines from being mis-parsed as MCP frames |

## Operational guidance for STDIO deployments

- **Do not run under a shared or elevated account.** The MCP tools execute with
  the launcher's privileges. Run as the same unprivileged user that owns the
  MCP client.
- **Treat `SOLR_URL` as deployer-controlled config**, not user-controlled input.
  It is read once at startup. Never wire it from an MCP tool argument.
- **Scope the Solr instance.** STDIO mode delegates Solr-side authorization to
  Solr itself (Basic Auth, mTLS, network policy). Point at a Solr that the
  launching user is already authorized to use.
- **Do not redirect stdout.** Stray writes to stdout corrupt the JSON-RPC
  stream. Application logging in STDIO mode is suppressed for this reason; do
  not add `System.out.println` or appenders that target stdout.

## Primary sources

### Model Context Protocol specification (2025-06-18)

- **Authorization spec** — explicitly excludes STDIO from the OAuth flow:
  > *"Implementations using an STDIO transport SHOULD NOT follow this
  > specification, and instead retrieve credentials from the environment."*
  >
  > <https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization>

- **Transports spec** — security warnings (Origin validation, localhost binding,
  authentication) apply only to Streamable HTTP. STDIO has no auth section.
  > <https://modelcontextprotocol.io/specification/2025-06-18/basic/transports>

- **Security Best Practices** — recommends STDIO **as a mitigation** against
  local-server compromise:
  > *"MCP servers intending for their servers to be run locally SHOULD
  > implement measures to prevent unauthorized usage from malicious
  > processes: Use the `stdio` transport to limit access to just the MCP
  > client […]"*
  >
  > <https://modelcontextprotocol.io/specification/2025-06-18/basic/security_best_practices>

### Spring AI

- **Spring AI MCP Security reference** — security module is HTTP-only:
  > *"This module is compatible with Spring WebMVC-based servers only."*
  >
  > <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html>

- **`spring-ai-community/mcp-security`** (the library backing
  `org.springaicommunity:mcp-server-security`) — README documents Streamable
  HTTP / stateless transport support; STDIO is out of scope by design because
  `McpServerOAuth2Configurer` plugs into Spring Security's `HttpSecurity`
  filter chain.
  > <https://github.com/spring-ai-community/mcp-security>

- **Spring Blog — *Securing Spring AI MCP servers with OAuth2*** (2025-04-02):
  > *"MCP Servers can run locally, using the STDIO transport. To expose an MCP
  > server to the outside world, it must expose a few standard HTTP endpoints."*
  >
  > <https://spring.io/blog/2025/04/02/mcp-server-oauth2/>

- **Spring Blog — *Securing MCP Servers with Spring AI*** (2025-09-30):
  > <https://spring.io/blog/2025/09/30/spring-ai-mcp-server-security/>

### Spring Security / Spring Boot

- **`SecurityAutoConfiguration` Javadoc** — only activates with a servlet web
  application; inert when `spring.main.web-application-type=none`.
  > <https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/security/servlet/SecurityAutoConfiguration.html>

- **Spring Security Servlet Architecture** — the filter chain is built around
  `HttpServletRequest`. Without a servlet container there is no chain to
  configure.
  > <https://docs.spring.io/spring-security/reference/servlet/architecture.html>

## Related documents

- HTTP transport security model — *planned, will live at `docs/security/http.md`*
- [GraalVM native image spec](../specs/graalvm-native-image.md)
- [Logging architecture in `CLAUDE.md`](../../CLAUDE.md#logging-architecture)
