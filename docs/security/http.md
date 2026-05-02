# HTTP Transport — Security Model

This document captures the security posture of the Solr MCP server when run in
**HTTP mode** (`PROFILES=http`). Companion to [`stdio.md`](./stdio.md), which
covers the default STDIO transport.

## TL;DR

HTTP mode is **secured by default**: the OAuth2 filter chain enforces
authentication on every MCP tool call and every actuator endpoint except
`/actuator/health`. Operators must configure an OAuth2 authorization server.
The MCP Authorization specification mandates this — STDIO is the only transport
that legitimately runs without auth.

## Trust model

| | STDIO | HTTP |
|---|---|---|
| Network listener | None | Servlet container on configured port |
| Trust boundary | OS user that launched the process | OAuth2 access token (JWT bearer) per request |
| Auth required | No (per [MCP Authorization spec](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)) | Yes (per same spec) |
| Default in this codebase | Active | Active when `PROFILES=http` and `http.security.enabled=true` (default) |

## Configuration knobs

| Property | Env var | Default | Effect |
|---|---|---|---|
| `http.security.enabled` | `HTTP_SECURITY_ENABLED` | `true` | When `false`, the unsecured filter chain is active and every MCP/actuator endpoint is anonymous. Use only for local development. |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `OAUTH2_ISSUER_URI` | `https://your-auth0-domain.auth0.com/` (placeholder — replace) | OpenID Provider issuer URL. Used to fetch JWKS for signature validation. The MCP server fails to start if this URL is unreachable. |
| `mcp.cors.allowed-origins` | `MCP_CORS_ALLOWED_ORIGINS` | `http://localhost:6274,http://127.0.0.1:6274` | Explicit CORS allowlist. Wildcards are rejected because the filter chain uses credentials. |
| `solr.url` | `SOLR_URL` | `http://localhost:8983/solr/` | Same as STDIO. |

## Security architecture

### 1. Filter chain (`HttpSecurityConfiguration`)

```java
http.authorizeHttpRequests(auth -> {
    auth.requestMatchers("/actuator/health").permitAll();
    auth.requestMatchers("/actuator", "/actuator/**").authenticated();
    auth.requestMatchers("/mcp").permitAll();   // gated by @PreAuthorize, see below
    auth.anyRequest().authenticated();
})
.with(McpServerOAuth2Configurer.mcpServerOAuth2(),
        cfg -> cfg.authorizationServer(issuerUrl)
                  .resourcePath("/mcp")
                  .validateAudienceClaim(true))
.cors(...).csrf(CsrfConfigurer::disable);
```

- `/mcp` is permitted at the HTTP layer because Spring AI MCP routes the entire
  JSON-RPC stream through one path. **Per-tool authorization is enforced via
  `@PreAuthorize("isAuthenticated()")` on every `@McpTool` method.** This is
  the canonical pattern from
  [`spring-ai-community/mcp-security` "secured tools" sample](https://github.com/spring-ai-community/mcp-security/blob/main/samples/sample-mcp-server-secured-tools/src/main/java/org/springaicommunity/mcp/security/sample/server/securedtools/HistoricalWeatherService.java).
- `/actuator/health` stays anonymous so load balancers and orchestrators can
  probe liveness. Everything else under `/actuator` requires auth so an
  unauthenticated caller cannot read the SBOM, scrape Prometheus metrics that
  map the tool surface, or change log levels.
- CSRF is disabled because the API is stateless Bearer-token: no cookies, no
  session, no auto-attached credentials by browsers. See [Spring Security —
  When to use CSRF protection](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html#csrf-when).

### 2. JWT validation

The `mcpServerOAuth2()` configurer wires a Spring Security
`JwtDecoder` that validates:

1. **Signature** against the JWKS fetched from `issuer-uri`/`.well-known/openid-configuration`.
2. **Issuer** matches the configured `issuer-uri`.
3. **Expiration** (`exp`) and not-before (`nbf`).
4. **Audience** (`aud`) matches the canonical resource indicator declared by
   `resourcePath("/mcp")` — per
   [RFC 8707 Resource Indicators](https://www.rfc-editor.org/rfc/rfc8707.html)
   and
   [the MCP Authorization spec's Token Audience Binding requirement](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization).

Without audience validation, any valid JWT from the same IdP issued for any
sibling application would be accepted (CWE-345).

### 3. Per-IdP setup for the audience claim

The MCP server requires the JWT to carry an `aud` claim matching the canonical
resource URI. Per IdP:

| IdP | How to populate `aud` |
|---|---|
| **Auth0** | Pass `audience=<MCP server URL>` on the auth request. Auth0 reflects it into `aud` automatically. Configure the API in the Auth0 dashboard with the same identifier. [Auth0 docs](https://auth0.com/docs/secure/tokens/access-tokens). |
| **Okta** | Configure the audience on the Authorization Server (`Security → API → Authorization Servers → Settings`). Tokens issued from that AS will carry the configured `aud`. |
| **Keycloak** | Keycloak does **not** yet honor RFC 8707 `resource=` natively (see [Keycloak issue #41526](https://github.com/keycloak/keycloak/issues/41526)). Workaround: add an **Audience** protocol mapper on a client scope, set `Included Custom Audience` to the MCP server URL, and assign that client scope as a default scope on the MCP client. [Keycloak MCP integration docs](https://www.keycloak.org/securing-apps/mcp-authz-server). |

### 4. CORS

The CORS allowlist is intentionally narrow:

- **Origins**: explicit allowlist (default: MCP Inspector's local proxy at
  `http://localhost:6274`). Wildcards forbidden — combining `*` origin with
  credentials is the [classic CWE-942 misconfiguration](https://cwe.mitre.org/data/definitions/942.html).
- **Methods**: `GET, POST, DELETE, OPTIONS` — the methods used by the MCP
  Streamable HTTP transport.
- **Headers**: `Authorization, Content-Type, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID`.
- **Credentials**: allowed (Bearer-token flows).

Add origins via `MCP_CORS_ALLOWED_ORIGINS` (comma-separated). Real production
MCP clients (Claude Desktop, Spring AI MCP client, etc.) speak HTTP from a
backend or native process and don't trigger CORS preflights — the allowlist
exists for browser-based tooling.

## Operational guidance

### Required for production

1. **Set a real `OAUTH2_ISSUER_URI`** pointing at your authorization server.
   The placeholder default fails to start.
2. **Configure your IdP to populate `aud`** with the MCP server's URL (see
   table above).
3. **Bind to a private network or behind an authenticated ingress**. The MCP
   transport spec recommends localhost binding for local servers and
   authentication for everything else.

### Forbidden

- `mcp.cors.allowed-origins=*` together with `allowCredentials=true`.
- `http.security.enabled=false` on a network-reachable deployment.
- Passing `SOLR_URL` from MCP tool input — it must come from deployer-controlled
  environment.

## Primary sources

### MCP specification (2025-06-18)

- [MCP Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization) — OAuth2 resource server requirements, audience binding, token validation rules.
- [MCP Transports — Streamable HTTP security](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http) — Origin validation, localhost binding for local deployments, authentication requirements.
- [MCP Security Best Practices](https://modelcontextprotocol.io/specification/2025-06-18/basic/security_best_practices) — Confused deputy, token passthrough prohibition, SSRF, session hijacking.

### Spring AI / Spring AI Community

- [Spring AI MCP Security reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
- [`spring-ai-community/mcp-security` README](https://github.com/spring-ai-community/mcp-security)
- [Sample: secured-tools `McpServerConfiguration`](https://github.com/spring-ai-community/mcp-security/blob/main/samples/sample-mcp-server-secured-tools/src/main/java/org/springaicommunity/mcp/security/sample/server/securedtools/McpServerConfiguration.java)
- [Spring Blog — *Securing Spring AI MCP servers with OAuth2* (2025-04-02)](https://spring.io/blog/2025/04/02/mcp-server-oauth2/)
- [Spring Blog — *Securing MCP Servers with Spring AI* (2025-09-30)](https://spring.io/blog/2025/09/30/spring-ai-mcp-server-security/)

### Spring Security / Spring Boot

- [Spring Security — OAuth2 Resource Server / JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security — CSRF reference](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html)
- [Spring Boot — Actuator Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

### IdP-specific

- [Auth0 — Validate JSON Web Tokens](https://auth0.com/docs/secure/tokens/json-web-tokens/validate-json-web-tokens)
- [Keycloak — Integrating with Model Context Protocol](https://www.keycloak.org/securing-apps/mcp-authz-server)
- [Keycloak Issue #41526 — RFC 8707 resource parameter for MCP](https://github.com/keycloak/keycloak/issues/41526)

### Standards

- [RFC 7519 — JSON Web Token (JWT)](https://datatracker.ietf.org/doc/html/rfc7519)
- [RFC 6750 — OAuth 2.0 Bearer Token Usage](https://datatracker.ietf.org/doc/html/rfc6750)
- [RFC 8707 — Resource Indicators for OAuth 2.0](https://www.rfc-editor.org/rfc/rfc8707)
- [RFC 9728 — OAuth 2.0 Protected Resource Metadata](https://datatracker.ietf.org/doc/html/rfc9728)

### CWE / OWASP

- [CWE-306 (Missing Authentication for Critical Function)](https://cwe.mitre.org/data/definitions/306.html)
- [CWE-345 (Insufficient Verification of Data Authenticity)](https://cwe.mitre.org/data/definitions/345.html)
- [CWE-732 (Incorrect Permission Assignment)](https://cwe.mitre.org/data/definitions/732.html)
- [CWE-942 (Permissive Cross-domain Policy)](https://cwe.mitre.org/data/definitions/942.html)
- [CWE-943 (Query Logic Injection)](https://cwe.mitre.org/data/definitions/943.html)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x00-header/)

## Related documents

- [STDIO transport security model](./stdio.md)
- [GraalVM native image spec](../specs/graalvm-native-image.md)
- [Logging architecture in `CLAUDE.md`](../../CLAUDE.md#logging-architecture)
