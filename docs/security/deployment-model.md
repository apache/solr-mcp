# Deployment Model — Single-Tenant by Design

**The Solr MCP server is single-tenant. It does not support multi-tenancy.**

The intended deployment is one instance per user, configured with that user's
own Solr credentials. Every action the configured Solr identity is allowed to
perform — including admin, schema, and collection operations — is by design
reachable through the MCP tools. The server performs no per-user authorization
or tenant isolation of its own; to limit what the tools can do, scope the Solr
identity in Solr (Basic Auth, roles, mTLS, network policy).

In HTTP mode, OAuth2 authenticates *that* a caller is allowed in, but every
authenticated caller shares the same single Solr identity — it is an access
gate, not a tenant boundary.

Sharing one instance across multiple users, or otherwise treating it as
multi-tenant, is unsupported: you are on your own for isolation and any
resulting exposure. Multi-tenancy may be considered in a future release.

## Related documents

- [STDIO transport security model](./stdio.md)
- [HTTP transport security model](./http.md)
