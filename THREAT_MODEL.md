<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# Apache Solr MCP Server — Threat Model (v0 DRAFT)

## §1 Header

- **Project:** Apache Solr MCP Server (`apache/solr-mcp`) — a Spring AI
  [Model Context Protocol](https://spec.modelcontextprotocol.io/) server that
  exposes Apache Solr search, indexing, schema, and collection-management
  operations as natural-language **tools** to MCP-compatible AI clients
  (Claude Desktop/Code, VS Code/Copilot, Cursor, JetBrains, MCP Inspector).
- **Written against:** `main` @ HEAD (2026-07), project version `1.0.0-SNAPSHOT`
  / incubating.
- **Author:** ASF Security team, via the threat-model-producer rubric.
- **Date:** 2026-07-14.
- **Status:** **v1 — maintainer-answered.** Drafted by the ASF Security team,
  then reviewed against the source by Aditya Parikh (Apache Solr) on
  2026-07-16, who answered every §14 question; Eric Pugh endorsed the answers
  on 2026-07-22. The claims below are now maintainer positions, not the
  Security team's reading. Ratification (merge) is the PMC's call. Every claim
  is *(documented)* against the repo or *(maintainer)* per that review; the one
  question still under discussion is tracked in §14.
- **Version binding:** intended to be versioned alongside the project once
  adopted; a report against version *N* is triaged against the model as it
  stood at *N*, not at HEAD.
- **Reporting cross-reference:** findings that fall under §8 (claimed
  properties) should be reported via the ASF security process (the project
  shares Apache Solr's disclosure channel — `security@solr.apache.org`,
  confirmed by the PMC). Findings that fall under §3 or §9 are closed citing this
  document.
- **Relationship to the search engine:** This models the **MCP bridge server**,
  not Apache Solr itself. The backend Solr's own hardening posture is out of
  scope and is covered by
  [apache/solr's `THREAT_MODEL.md`](https://github.com/apache/solr/blob/main/THREAT_MODEL.md);
  this document cross-references it wherever a threat lands on the Solr side of
  the boundary.
- **Provenance legend:** *(documented)* — stated in the repo/docs, cited;
  *(maintainer)* — confirmed by a maintainer through this process (Aditya
  Parikh, 2026-07-16); *(inferred)* — reasoned from code/docs and routed to a
  §14 open question. No *(inferred)* claims remain.
- **Confidence:** ~24 documented / 13 maintainer / 0 inferred.

**What it is.** The Solr MCP Server is a **single-process bridge**. On one side
it speaks MCP (JSON-RPC) to an AI client over one of two transports — **STDIO**
(default; the client launches the server as a child process) or **HTTP**
(streamable-HTTP; a network listener). On the other side it speaks SolrJ HTTP to
**one** backend Solr instance whose location and credentials the operator fixes
at startup via environment (`SOLR_URL`, optional `SOLR_USERNAME`/`SOLR_PASSWORD`).
It exposes eleven tools (search, three indexing formats, collection create/list/
stats/health, schema get/add-fields/add-field-types), two resources
(`solr://collections`, `solr://{collection}/schema`), and prompt/completion
helpers. It translates natural-language requests — as structured by the calling
LLM into tool arguments — into Solr API calls, and returns Solr results back to
the model. *(documented — README; AGENTS.md; `src/main/java/.../server/`.)*

## §2 Scope and intended use

The Solr MCP Server is a **network/IPC service and tool provider**, not an
in-process library and not a search engine. Its job is to be a *typed adapter*
between an LLM client and a Solr instance the operator has chosen to expose to
that client.

**Roles at the boundary.**

- **AI client / LLM (tool-argument source)** — the immediate caller. It is
  **semi-trusted**: the operator chose to connect it, but the *arguments* it
  supplies to each tool are shaped by an LLM acting on natural language that may
  include attacker-influenced content (indexed documents, pasted text, upstream
  prompt injection). Treat tool arguments as untrusted data, the client's
  *identity* as operator-authorized. *(maintainer — Q-clienttrust.)*
- **Operator/deployer** — trusted. Owns `SOLR_URL`, the backend credentials, the
  transport choice, the OAuth2 issuer config, CORS allowlist, and which Solr the
  server points at. *(documented — docs/security/stdio.md, http.md.)*
- **HTTP network peer** — in scope **only** in HTTP transport: anyone who can
  reach the servlet listener. Gated by OAuth2 bearer auth in the default
  posture. *(documented — docs/security/http.md.)*

**Component-family table.**

| Family | Entry point | Touches outside process? | In model? |
| --- | --- | --- | --- |
| STDIO transport | stdin/stdout JSON-RPC | child process of the client only | **Yes** |
| HTTP transport | servlet on `:8080/mcp` + OAuth2 filter chain | **network listener** | **Yes (highest network exposure)** |
| Read tools | `search`, `list-collections`, `get-collection-stats`, `check-health`, `get-schema` | reads backend Solr | **Yes** |
| Write/index tools | `index-json/csv/xml-documents` | writes backend Solr index | **Yes** |
| Admin/schema tools | `create-collection`, `add-fields`, `add-field-types` | mutates backend Solr collections/schema | **Yes (privileged)** |
| Backend SolrJ client | `SolrConfig` → `HttpJdkSolrClient` | outbound HTTP to `SOLR_URL` | **Yes (auth passthrough)** |
| Actuator endpoints (HTTP) | `/actuator/*` (sbom, metrics, prometheus, loggers, info) | network | **Yes** |
| Observability export | OTLP traces/metrics/logs to collector | outbound network | partial — Q-otel |
| Backend Apache Solr itself | Solr HTTP API | — | **No — §3** (see apache/solr model) |
| `docker compose` sample stack (Solr, ZooKeeper, LGTM) | local dev containers | — | **No — §3** (dev fixture) |

## §3 Out of scope (explicit non-goals)

- **The backend Solr's own security.** Query-parser features, SSRF via Solr's
  `shards`/streaming expressions, Solr auth/authz, ZooKeeper hardening, Solr
  RCE surfaces — all belong to
  [apache/solr's threat model](https://github.com/apache/solr/blob/main/THREAT_MODEL.md).
  This server is a client of Solr; it inherits, and cannot fix, Solr's posture.
  *(documented — the server delegates Solr-side authorization to Solr:
  "STDIO mode delegates Solr-side authorization to Solr itself", docs/security/stdio.md.)*
- **The operator's configuration choices.** Which Solr `SOLR_URL` points at,
  whether that Solr requires auth, which AI client is connected and whether it
  is trustworthy, and whether the operator flips a documented dev-only toggle
  (`HTTP_SECURITY_ENABLED=false`). A finding whose precondition is "the operator
  pointed the server at a Solr the client should not reach" or "the operator
  disabled HTTP security on a network-reachable deployment" is operator
  misconfiguration. *(documented — docs/security/http.md "Forbidden" list;
  docs/security/stdio.md operational guidance.)*
- **The AI client and the model behind it.** Whether the LLM faithfully relays
  user intent, whether the client presents approval UX, and whether a malicious
  *client* is connected are the operator's and the client vendor's concern, not
  the server's. *(maintainer — Q-clienttrust.)*
- **The `docker compose` / `init-solr.sh` sample stack.** The bundled Solr,
  ZooKeeper, and Grafana LGTM containers are an unauthenticated local
  development fixture, not a supported deployment. *(documented — compose.yaml;
  README "Quick start".)*
- **Supply-chain / build hygiene** (SBOM generation, Jib/Paketo image builds,
  license/NOTICE generation, action pinning). Out of layer per the rubric.

## §4 Trust boundaries and data flow

Two boundaries stack, and the server sits between them:

```
                 (boundary A: MCP transport)          (boundary B: SolrJ HTTP)
 AI client  ───────────────────────────────►  MCP server  ──────────────────────►  backend Solr
 (LLM shapes                                   (this repo)      fixed SOLR_URL +      (trusted infra
  tool args)     STDIO: OS-user trust                           optional static        the operator
                 HTTP:  OAuth2 bearer + @PreAuthorize            Basic Auth creds       configured)
                                                     │
   tool ARGUMENTS (collection, q, fq, JSON/CSV/XML docs, field defs) = UNTRUSTED data
   Solr RESULTS (document content, schema, stats) flow BACK to the model  ──► prompt-injection surface (§9)
```

- **Boundary A (transport).** In **STDIO** the boundary is the OS process owner:
  only the parent client can write to stdin; there is no network listener and no
  in-process auth, by design and per the MCP spec. *(documented —
  docs/security/stdio.md.)* In **HTTP** the boundary is an **OAuth2 access
  token**: every tool call is gated by `@PreAuthorize("isAuthenticated()")` and
  every actuator endpoint except `/actuator/health` requires auth. *(documented
  — docs/security/http.md; `HttpSecurityConfiguration`.)*
- **Boundary B (backend).** Once past boundary A, the server issues SolrJ calls
  to the single configured Solr using a **single, static, deployer-supplied
  credential** (or none). The server does **not** carry the AI client's identity
  through to Solr; all MCP callers share the same Solr authorization. *(documented
  — `SolrConfig.solrClient`; `SolrConfigurationProperties`.)*

**Reachability precondition (triager's test).** A finding is in-model only if it
is reachable **across boundary A** — i.e. it is exercised through an MCP tool
argument or an MCP transport request — and the damage lands on *the server's own
behaviour or trust decisions*, not on the backend Solr's. A finding that merely
demonstrates "a fully-authorized tool call did exactly what the tool is for,
against a Solr the operator connected" is by-design (§9). A finding that requires
reaching the backend Solr directly, bypassing this server, is out of model (§3).

## §5 Assumptions about the environment

- **STDIO:** the server is launched as a child of a single trusted client, runs
  as an unprivileged OS user, and has no network listener. Trust is inherited
  from the launching OS user. *(documented — docs/security/stdio.md.)*
- **HTTP:** the server runs behind a reachable network listener and is expected
  to sit on a private network or behind an authenticated ingress, with a real
  OAuth2 issuer configured. *(documented — docs/security/http.md "Required for
  production".)*
- **Backend Solr** at `SOLR_URL` is reachable, is the Solr the operator intends
  this client to use, and enforces whatever Solr-side authorization the operator
  wants (this server does not add any). *(documented — docs/security/stdio.md
  "Scope the Solr instance".)*
- `SOLR_URL`, `SOLR_USERNAME`, `SOLR_PASSWORD` are **deployer-controlled config
  read once at startup**, never sourced from a tool argument. *(documented —
  docs/security/stdio.md and http.md both state this explicitly.)*
- **Side effects on the host:** the server opens an outbound HTTP connection to
  Solr always; opens a servlet listener only in HTTP mode; exports OTLP
  telemetry when a collector is configured; and, in HTTP `bootRun`, may start
  `docker compose`-declared services in local dev. It does not spawn child
  processes for tool execution or read arbitrary files from tool input.
  *(maintainer — Q-sideeffects.)*

## §5a Configuration variants — the security-relevant knobs

| Knob (env) | Default | Effect on the model | Maintainer stance |
| --- | --- | --- | --- |
| `PROFILES` | `stdio` | Selects transport. `stdio` = no network listener, OS-user trust. `http` = network listener, OAuth2. The two are **different threat models**; this document covers both. | Q-transport |
| `HTTP_SECURITY_ENABLED` | `true` | HTTP mode is **secured by default**. `false` activates an unsecured filter chain — every MCP/actuator endpoint anonymous. Documented as "local development only… unsafe on any network-reachable deployment." | Q-httpsec |
| `OAUTH2_ISSUER_URI` | empty (placeholder) | With HTTP security on and no issuer, the chain still returns 401/403 on every non-permitted endpoint (locked down, no token validator). A real issuer enables JWT signature/issuer/exp/**audience** validation. | Q-httpsec |
| `MCP_CORS_ALLOWED_ORIGINS` | MCP Inspector localhost proxy | Explicit CORS allowlist; wildcard-with-credentials is rejected by construction (`setAllowedOrigins`, not patterns). | *(documented)* |
| `SOLR_USERNAME` / `SOLR_PASSWORD` | unset | When both set, static HTTP Basic Auth to backend Solr on every request; when unset, unauthenticated backend calls. | Q-backendcreds |

**How HTTP mode enforces auth** *(maintainer — Q-transport.)*: the transport
is streamable HTTP running in **stateless** mode
(`spring.ai.mcp.server.protocol=stateless`), so there is no sampling, progress
or elicitation channel and no per-request context feature. `/mcp` is
`permitAll()` at the filter-chain level; authentication is enforced instead by
`@PreAuthorize("isAuthenticated()")` on **every** MCP entry point — all 11
tools, both resources, every prompt and completion handler — following the
spring-ai-community/mcp-security "secured tools" pattern. A finding that reads
`permitAll()` on `/mcp` as an authentication bypass without checking the
method-level annotations is a `KNOWN-NON-FINDING` (§11a).

**The insecure-default question is `PROFILES=stdio` vs `http`, and it is
already answered by design:** STDIO's "no auth" is the *intended, spec-aligned*
posture (trust = OS user), and HTTP's default *is* secured (`HTTP_SECURITY_ENABLED=true`).
So neither default is an unguarded insecure default — but the §14 wave-1
questions confirm this reading and pin whether a report against
`HTTP_SECURITY_ENABLED=false` is `OUT-OF-MODEL: non-default-build`.

## §6 Assumptions about inputs

Every tool argument crosses boundary A as **untrusted data**. The per-surface
trust table:

| Surface | Parameter | Attacker-controllable? | Server enforces / caller must |
| --- | --- | --- | --- |
| transport (HTTP) | `Authorization` bearer JWT | **yes** | server validates sig/iss/exp/aud (default posture) |
| transport (HTTP) | `Origin` header | **yes** | CORS allowlist (no wildcard+credentials) |
| transport (STDIO) | stdin JSON-RPC frame | only parent process | OS-user trust; no in-process check |
| `search` | `collection` | **yes** | used only as a path segment against the fixed `SOLR_URL` base; **cannot redirect to another host**. What a path reaches *within* that Solr is the backend's authorization call. *(maintainer — Q-collection.)* |
| `search` | `query` (`q`), `filterQueries` (`fq`) | **yes** | passed into `SolrQuery`; Solr query-parser semantics apply — Q-queryinj |
| `search` | `facetFields`, `sortClauses`, `start`, `rows` | **yes** | forwarded to Solr; `rows` unbounded? — Q-resource |
| `index-*` | `collection`, `json`/`csv`/`xml` body | **yes** | parsed then written to index; XML parser is XXE-hardened *(documented)* |
| `create-collection` | `name`, `configSet`, `numShards`, `replicationFactor` | **yes** | issues `CollectionAdminRequest.createCollection` to backend — Q-adminexposure |
| `add-fields` / `add-field-types` | `collection`, field/type defs | **yes** | additive schema change (existing fields cannot be modified per README) |
| config (startup only) | `SOLR_URL`, `SOLR_USERNAME`, `SOLR_PASSWORD` | **no — deployer config** | never wire from a tool argument *(documented)* |

**Shape/rate:** indexing bodies and `rows` are caller-sized, and the server
imposes **no** bound before handing them to Solr — resource limits are the
backend Solr's. This is a settled maintainer position, not an oversight: PR
#127 (pagination / validation bounds) was closed on the rationale that such
hardening belongs in Solr/SolrJ rather than in this bridge. *(maintainer —
Q-resource.)*

## §7 Adversary model

Two adversaries are in scope; several are explicitly not.

- **A manipulated tool-argument stream (primary).** The LLM client, acting on
  natural language that may embed attacker content, emits tool arguments the
  operator never vetted. Capability: call any exposed tool with any argument
  values, in any order, up to the client's rate. Goal: get the server to
  perform an index write, a collection/schema mutation, or a query the human
  did not intend — or to smuggle instructions back to the model via tool output.
  In scope: whether the *server's* tool surface and trust decisions contain the
  blast radius. *(maintainer — Q-clienttrust.)*
- **An HTTP network peer (HTTP transport only).** Anyone able to reach the
  servlet listener. Capability: send HTTP requests to `/mcp` and `/actuator/*`;
  attempt auth bypass, token confusion (wrong-audience JWT), CORS abuse, or
  actuator scraping. In scope against the default secured posture. *(documented
  — docs/security/http.md.)*
- **Out of scope:** a malicious *AI client* the operator chose to connect (they
  granted it trust); an attacker who can already write to the server's stdin in
  STDIO mode (they own the parent process — they have won); an attacker who can
  reach the backend Solr directly (that is Solr's boundary, §3); an operator who
  points `SOLR_URL` at a Solr the client should not touch, or who disables HTTP
  security on a network deployment (§3/§5a). *(documented — docs/security/*.md.)*

## §8 Security properties the project provides

1. **HTTP transport authentication (default posture).** With `PROFILES=http` and
   `HTTP_SECURITY_ENABLED=true`, every MCP tool call and every actuator endpoint
   except `/actuator/health` requires a valid OAuth2 bearer token; `@PreAuthorize
   ("isAuthenticated()")` gates each `@McpTool` method. *Violation:* an
   unauthenticated caller invokes a tool or reads a protected actuator endpoint.
   *Severity:* critical. *(documented — docs/security/http.md;
   `HttpSecurityConfiguration`, `MethodSecurityConfiguration`.)*
2. **JWT audience binding.** In the default posture the server validates the JWT
   `aud` claim against its canonical resource indicator (`resourcePath("/mcp")`,
   RFC 8707), rejecting sibling-app tokens from the same IdP. *Violation:* a
   token minted for another audience is accepted (CWE-345). *Severity:* high.
   *(documented — docs/security/http.md §2; `HttpSecurityConfiguration`.)*
3. **CORS is restrictive by construction.** Origins come from an explicit
   allowlist via `setAllowedOrigins` (not patterns), so a `*` origin cannot be
   combined with credentials (CWE-942). *Violation:* a wildcarded credentialed
   origin is honoured. *Severity:* high (browser-client exposure). *(documented
   — docs/security/http.md §4; `corsConfigurationSource`.)*
4. **STDIO has no network attack surface.** In the default transport there is no
   listener (`spring.main.web-application-type=none`); only the parent client can
   drive the server, and Spring Security servlet autoconfig is excluded.
   *Violation:* a network-reachable socket appears in STDIO mode. *Severity:*
   high. *(documented — docs/security/stdio.md; `application-stdio.properties`.)*
5. **Backend credentials are startup config, not caller input.** `SOLR_URL` and
   the optional Basic-Auth credentials are read once from the environment and are
   never taken from a tool argument, so the AI client cannot repoint the server
   or inject a target URL. *Violation:* a tool argument alters the backend
   target or credential. *Severity:* critical (SSRF/credential-redirect if
   broken). *(documented — docs/security/stdio.md & http.md; `SolrConfig`,
   `SolrConfigurationProperties`.)*
6. **XML indexing is XXE-hardened.** `XmlDocumentCreator` builds a
   `DocumentBuilderFactory` with secure processing on, DOCTYPE disallowed,
   external general/parameter entities off, XInclude off, entity-expansion off.
   *Violation:* an XXE/entity-expansion payload in an `index-xml-documents` body
   reads a local file or hangs the parser. *Severity:* high. *(documented —
   `XmlDocumentCreator.createSecureDocumentBuilderFactory`.)*
7. **Tool behaviour hints are advertised honestly.** Every tool carries MCP
   annotations (`readOnlyHint` on the five read tools, `idempotentHint` on the
   three index tools, `destructiveHint=false` on schema/create tools) so clients
   can build approval UX. *Violation:* a tool that mutates state advertises
   `readOnlyHint=true`. *Severity:* medium (client-UX safety). *(documented —
   README; `@McpTool.McpAnnotations` on each service method.)*

## §9 Security properties the project does *not* provide

- **It does not defend against prompt injection / tool poisoning via Solr
  content.** Search results, schema, and stats returned by a tool flow **back
  into the model's context**. A document indexed into the backend Solr (by
  anyone with write access to that Solr) can carry text that the LLM then reads
  as instructions. The server neither sanitises nor sandboxes tool output
  against this. Defending the model against injected content is the AI client's
  and operator's responsibility. *(maintainer — Q-promptinj.)*
  - *False friend:* the static, project-authored tool **descriptions** are
    trustworthy, but the **data** those tools return is not — do not treat Solr
    result content as trusted narration.
- **It does not add authorization on top of Solr, nor per-user identity
  passthrough.** All MCP callers share one static backend credential; the server
  makes no per-caller decision about which collections a caller may read or
  write. The credential is read once from `SOLR_USERNAME`/`SOLR_PASSWORD` at
  startup, applied only when both are set, and never sourced from a tool
  argument *(maintainer — Q-backendcreds.)*. Whatever the configured Solr
  credential can do, any authorized MCP caller can do. Scoping the backend
  Solr's permissions is the operator's job. (Should #66 land, authorization
  becomes finer-grained *server-side*; the backend Solr credential stays
  shared.)
  *(documented — docs/security/stdio.md "Scope the Solr instance"; `SolrConfig`.)*
  - *False friend:* the HTTP-mode OAuth2 layer authenticates *that a caller may
    use the server* — it is **not** an authorization model over Solr collections
    or actions. A caller who passes `isAuthenticated()` can invoke every tool.
- **It does not make Solr query construction injection-proof.** `search`
  arguments (`q`, `fq`) are forwarded into a `SolrQuery` with Solr query-parser
  semantics; a crafted argument can express any query the Solr parser accepts
  (local-params, function queries, join/subquery syntax, expensive queries).
  The server performs **no escaping** of `q`/`fq` — full query-parser
  expressiveness is the feature, and the blast radius is bounded by the backend
  Solr's own posture (CWE-943; see apache/solr's model). This is a settled
  maintainer position: PRs #122 and #127, which added server-side query
  escaping/validation, were both closed on the rationale that such hardening
  belongs in Solr/SolrJ. *(maintainer — Q-queryinj.)*
- **It does not restrict which collections/schemas a caller may create or
  mutate.** `create-collection`, `add-fields`, `add-field-types` are exposed to
  any authorized caller with no per-collection gate; the server cannot be
  configured (as of v0) to present a read-only tool subset. This is the
  **intended v0 posture**, not an oversight — but fine-grained per-tool
  authorization is on the roadmap as
  [#66](https://github.com/apache/solr-mcp/issues/66) ("Have specific security
  roles"). A report proposing a read-only tool subset or per-tool roles is
  `VALID-HARDENING` and should be routed to #66. *(maintainer —
  Q-adminexposure.)*
- **It makes no resource guarantee on caller-sized inputs.** `rows`, facet
  cardinality, and indexing-body size are forwarded to Solr; the server does not
  document a cap. A pathological argument is bounded only by Solr. *(maintainer —
  Q-resource.)*
- **It does not secure the backend, the transport TLS, or the OTLP export
  channel.** TLS termination in front of the HTTP listener, TLS to Solr, and the
  telemetry sink are operator infrastructure. *(maintainer — Q-otel.)*
- **Well-known attack classes left to the caller/operator:** prompt injection and
  tool poisoning via returned data (MCP-specific); confused-deputy /
  token-passthrough across the AI-client → server → Solr chain; SSRF *if* the
  backend-target invariant (§8.5) were ever relaxed; Solr query-logic injection
  (CWE-943) inherent to any NL-to-Solr surface; DoS via unbounded `rows`/large
  index payloads.

## §10 Downstream responsibilities (operator)

- **Choose the transport deliberately.** Use STDIO for a single local client;
  use HTTP only behind a private network or authenticated ingress, with a real
  `OAUTH2_ISSUER_URI` and the IdP configured to populate `aud`. *(documented.)*
- **Never set `HTTP_SECURITY_ENABLED=false` on anything network-reachable**, and
  never combine a `*` CORS origin with credentials. *(documented —
  docs/security/http.md "Forbidden".)*
- **Point `SOLR_URL` only at a Solr the connected client is entitled to use**,
  and scope that Solr's own auth/authz to the least privilege the tools need —
  because the server passes one shared credential and adds no authorization.
  *(documented — docs/security/stdio.md.)*
- **Treat the connected AI client (and its model) as part of your trust base.**
  The server executes tool calls the client emits; approval/guardrail UX is the
  client's job.
- **Assume Solr result content can carry injected instructions.** If the backend
  Solr is writable by untrusted parties, the returned documents are an injection
  vector into your model. *(maintainer — Q-promptinj.)*
- **Run STDIO as an unprivileged user; do not redirect stdout.** *(documented —
  docs/security/stdio.md.)*
- **Enable TLS** for the HTTP transport and for the SolrJ connection; secure the
  OTLP collector endpoint. *(maintainer — Q-otel.)*

## §11 Known misuse patterns

- **Exposing the HTTP transport unauthenticated** (`HTTP_SECURITY_ENABLED=false`,
  or no ingress auth) on a reachable network — turns the whole tool surface,
  including collection creation and schema mutation, into an anonymous API.
- **Pointing the server at a production Solr while connecting an
  untrusted/experimental client** — the client inherits the server's full Solr
  authorization.
- **Wiring `SOLR_URL` (or credentials) from user/tool input** instead of
  deployer environment — would convert the server into an SSRF/credential-relay
  primitive. Explicitly forbidden. *(documented.)*
- **Indexing untrusted documents into a Solr that the same MCP server reads
  back to the model** — creates a stored-prompt-injection loop.
- **Sharing one MCP server (and its one backend credential) across mutually
  distrusting users** in HTTP mode — there is no per-user authorization.

## §11a Known non-findings (recurring false positives)

- **"An authorized tool call created a collection / changed a schema / wrote
  documents."** That is the tool doing its job for an authorized caller against
  the operator-chosen Solr — `BY-DESIGN` unless it crosses an auth boundary the
  server should have enforced (§8). *(documented — README tool table.)*
- **"STDIO mode has no authentication."** Intended and MCP-spec-aligned; trust is
  the OS process owner. `KNOWN-NON-FINDING`. *(documented — docs/security/stdio.md.)*
- **"CSRF is disabled on the HTTP endpoint."** Deliberate: the API is stateless
  bearer-token with no cookies/session, so CSRF does not apply. `KNOWN-NON-FINDING`.
  *(documented — docs/security/http.md §1.)*
- **"`/actuator/health` is anonymous."** Intended for load-balancer/orchestrator
  liveness probes; every other actuator endpoint requires auth.
  `KNOWN-NON-FINDING`. *(documented — `HttpSecurityConfiguration`.)*
- **"XXE in XML indexing."** The `DocumentBuilderFactory` is hardened (DOCTYPE
  disallowed, external entities off). `KNOWN-NON-FINDING`. *(documented —
  `XmlDocumentCreator`.)*
- **"`SOLR_URL` allows SSRF."** It is deployer-only startup config, never taken
  from a tool argument; an SSRF report requires the operator to have violated the
  documented contract. `OUT-OF-MODEL` (operator config) / `BY-DESIGN`.
  *(documented.)*
- **"Solr query injection via the `search` tool."** Expressing arbitrary Solr
  queries is the feature; the blast radius is the backend Solr's, governed by
  apache/solr's model. Route Solr-side query-parser exposure there. PRs #122
  and #127 proposed server-side escaping/validation and were closed for this
  reason. `KNOWN-NON-FINDING`. *(maintainer — Q-queryinj.)*
- **"`/mcp` is `permitAll()` — the MCP endpoint is unauthenticated."** Auth is
  enforced at the method level by `@PreAuthorize("isAuthenticated()")` on every
  tool, resource, prompt and completion handler, not at the filter chain.
  `KNOWN-NON-FINDING` unless an entry point is found *without* the annotation —
  that would be `VALID`. *(maintainer — Q-transport.)*
- **Findings in the `docker compose` sample stack (unauthenticated Solr/ZooKeeper/
  Grafana).** `OUT-OF-MODEL: unsupported-component` — dev fixture, §3.
  *(documented — compose.yaml.)*

## §12 Conditions that would change this model

- Adding a **destructive** tool (delete-collection, delete-by-query, schema
  field deletion, config API) — today the tool set is read/additive-only, which
  materially bounds the blast radius.
- Allowing any **backend-target or credential** value to originate from a tool
  argument or per-request input (would open SSRF/credential-relay).
- Adding **per-caller identity passthrough** or an authorization layer over Solr
  collections/actions (would add new §8 properties).
- Introducing **tool output sanitisation** or a configurable **read-only tool
  subset** (would change §9/§11a).
- A change in the **default** of `PROFILES` or `HTTP_SECURITY_ENABLED`.
- A **vulnerability report that cannot be routed** to a §13 disposition — that
  signals a model gap; revise §8/§9 rather than making an ad-hoc call.

## §13 Triage dispositions

| Disposition | Meaning | Licensed by |
| --- | --- | --- |
| `VALID` | A §8 property breaks via an in-scope adversary (auth bypass, wrong-audience token accepted, CORS wildcard+credentials, network listener in STDIO, tool-arg repoints backend, XXE in XML indexing, dishonest tool hint). | §8, §6, §7 |
| `VALID-HARDENING` | No §8 break, but a §11 misuse is made too easy (e.g. admin tools exposed with no opt-out); fixed at maintainer discretion. Per-tool / read-only-subset proposals route to [#66](https://github.com/apache/solr-mcp/issues/66). | §11 |
| `OUT-OF-MODEL: trusted-input` | Requires control of deployer config (`SOLR_URL`, credentials, issuer, CORS list). | §5/§6/§10 |
| `OUT-OF-MODEL: adversary-not-in-scope` | Requires owning the client's stdin (STDIO), a maliciously-connected client, or direct backend access. | §7 |
| `OUT-OF-MODEL: non-default-build` | Only manifests with `HTTP_SECURITY_ENABLED=false` or an otherwise discouraged toggle. | §5a |
| `OUT-OF-MODEL: unsupported-component` | Lands in the `docker compose`/sample dev stack. | §3 |
| `BY-DESIGN: property-disclaimed` | Concerns a §9 property (prompt injection via Solr content, no per-user authz, Solr query expressiveness, backend-hardening, resource bounds). | §9 |
| `KNOWN-NON-FINDING` | Matches a §11a pattern. | §11a |
| `MODEL-GAP` | Cannot be routed to any of the above. | triggers §12 |

## §14 Maintainer answers (and the one question still open)

**Answered by Aditya Parikh (Apache Solr) on 2026-07-16**, verified against the
source, and endorsed by Eric Pugh on 2026-07-22. Every answer below is folded
into the body above; the corresponding claims now carry *(maintainer)* tags.

**Wave 1 — scope and trust posture — ANSWERED**

- **Q-clienttrust.** Is the client's *identity* trusted but its *tool arguments*
  untrusted? → **Confirmed.** The client's identity is operator-authorized (the
  operator chose to connect it), but every argument it emits is untrusted data;
  the server validates nothing about the natural language upstream of it.
  Model-side defenses (approval UX, content filtering) belong to the client and
  operator. (§2/§7.)
- **Q-transport / Q-httpsec.** Are STDIO-unauthenticated and
  HTTP-secured-by-default both supported postures? → **Confirmed**, with both
  dispositions as proposed: `HTTP_SECURITY_ENABLED=false` on a network
  deployment is `OUT-OF-MODEL: non-default-build`; unauthenticated STDIO is
  `KNOWN-NON-FINDING`. Two precision points, now in §5a: the HTTP transport runs
  **stateless** (`spring.ai.mcp.server.protocol=stateless`), and `/mcp` is
  `permitAll()` at the filter chain with auth enforced by
  `@PreAuthorize("isAuthenticated()")` on every entry point. (§5a/§8/§11a.)
- **Q-adminexposure.** Is exposing `create-collection`/`add-fields`/
  `add-field-types` to any authorized caller the intended posture? →
  **Confirmed as the intended v0 posture, and yes — a `VALID-HARDENING`
  target.** No per-tool or per-collection gate today, deliberately; per-tool
  authorization is roadmapped as
  [#66](https://github.com/apache/solr-mcp/issues/66). Route such reports there.
  (§9/§11/§12/§13.)

**Wave 2 — data-flow and injection — ANSWERED**

- **Q-promptinj.** Is prompt injection via returned Solr content a disclaimed
  property? → **Confirmed: disclaimed, `BY-DESIGN`.** The server returns Solr
  content verbatim and cannot meaningfully sanitise it; the defense belongs to
  the client (approval UX, which the server supports by advertising honest
  `readOnlyHint`/`destructiveHint` annotations) and to the operator (don't back
  the server with a Solr writable by untrusted parties). (§9/§10.)
- **Q-queryinj.** Does `search` forward `q`/`fq` with full Solr query-parser
  semantics? → **Confirmed, with no escaping performed.** Full expressiveness is
  the feature; blast radius is the backend Solr's. A documented position, not an
  omission: PRs #122 and #127 added server-side escaping/validation and were
  closed on the rationale that such hardening belongs in Solr/SolrJ. (§6/§9/§11a.)
- **Q-collection.** Can `collection` traverse paths or escape to another host? →
  **Confirmed: no host escape.** The base URL is fixed at startup from
  `SOLR_URL`; `collection` is used only as a path segment against it. The server
  does not validate the string further — what a given path reaches *within* that
  Solr is the backend's authorization call, consistent with the #122/#127
  position. (§6.)

**Wave 3 — backend, resources, telemetry — ANSWERED**

- **Q-backendcreds.** One static shared backend credential, no identity
  passthrough? → **Confirmed.** Read once from `SOLR_USERNAME`/`SOLR_PASSWORD`
  at startup, applied only when both are set; no per-caller credential. (If #66
  lands, authorization becomes finer-grained *server-side*; the backend
  credential stays shared.) (§8.5/§9.)
- **Q-resource.** Any server-side cap on `rows`, facet cardinality, or indexing
  body size? → **Confirmed: none.** Bounds are the backend Solr's. Also
  documented — PR #127 (pagination/validation bounds) was closed for the same
  reason. (§6/§9.)
- **Q-sideeffects / Q-otel.** Is the outbound side-effect inventory complete? →
  **Confirmed.** SolrJ connection (always), servlet listener (HTTP mode only),
  OTLP export (when configured). No child processes and no file reads from tool
  input. Securing OTLP and TLS is operator infrastructure. The `docker compose`
  autostart exists only in the http-profile `bootRun` local-dev path. (§5/§9.)

**Wave 4 — meta / ownership — ANSWERED**

- **Q-disclosure.** Which channel do §8 findings go to? →
  **`security@solr.apache.org`**, the Solr PMC's channel, same as apache/solr.
  `SECURITY.md` was aligned to it in this PR (commit `440d5d4`). (§1.)
- **Q-ownership.** Does this document coexist with `docs/security/*.md`? →
  **Confirmed.** `THREAT_MODEL.md` is the canonical scanner-facing model;
  `docs/security/*.md` remain the operator-facing source of truth, with the
  cross-references as set out in §15. (§1/§15.)

**STILL OPEN — Q-tenancy (raised by Jan Høydahl, 2026-07-29).**

Should the project declare that the **only supported deployment mode is one
`solr-mcp` instance per user**, each configured with that user's own
`SOLR_USER`/`SOLR_PASSWORD` mapping to their roles on the Solr backend?

The proposal, in Jan's words, is that this makes "a 1:1 connection to the user
writing the prompt and the permission that user is given in solr backend" —
so a report that user *Y* can perform destructive action *Z* becomes, by
construction, a misconfigured Solr server rather than an MCP-server flaw. A
single person needing both normal and elevated access would run two instances.
Multi-tenancy or credential forwarding would be a later version.

This is **not yet a maintainer position** and is deliberately not folded into
§2/§8/§10 above. Jan asked whether to settle it on the PR or take it to the dev
list first, and that call sits with the PMC. Two notes for whoever picks it up:

- It would strengthen §10 (downstream responsibilities) and add a §11a entry,
  rather than change any §8 claim — the server gains no new property, it makes
  an existing operator assumption explicit and testable.
- It interacts with [#66](https://github.com/apache/solr-mcp/issues/66): if
  per-tool roles land server-side, the one-instance-per-user constraint becomes
  a recommendation rather than the only supported mode, so §12 would trip.

The ASF Security team is happy to draft the §2/§10/§11a wording once the PMC
decides — or to leave it entirely to the project. It does not block the scan
either way; an undecided question is legitimately an undecided question.


## §15 Appendix — document roles and existing-policy back-map

**Document roles (proposed; PMC to ratify).** This `THREAT_MODEL.md` is intended
as the project's **scanner-facing** security model — in/out-of-scope, the §8/§9
property lists, the §11a non-findings, and the §13 triage dispositions. The
existing repo docs remain the **operator-facing** and **spec-citation** source of
truth and cross-reference this model:

- **STDIO transport security model** — `docs/security/stdio.md` (why STDIO has no
  auth; MCP-spec citations).
- **HTTP transport security model** — `docs/security/http.md` (OAuth2 filter
  chain, JWT audience binding, CORS, actuator gating; RFC/CWE citations).
- **OAuth2 provider setup** — `docs/security/auth0.md`, `docs/security/keycloak.md`.
- **Backend search-engine threats** — [apache/solr `THREAT_MODEL.md`](https://github.com/apache/solr/blob/main/THREAT_MODEL.md)
  (Solr query-parser abuse, Solr SSRF via `shards`/streaming, Solr auth/authz,
  ZooKeeper — all §3 here).

**Existing-policy back-map** (source-of-truth statement → this model's §):

| Existing statement (docs/security) | Lands in |
| --- | --- |
| STDIO: no in-process auth, trust = OS user, spec-aligned | §4, §8.4, §11a |
| STDIO: treat `SOLR_URL` as deployer config, never from a tool arg | §5, §8.5, §11 |
| STDIO: delegate Solr-side authorization to Solr; scope the instance | §9 (no per-user authz), §10 |
| HTTP: secured by default; `@PreAuthorize` on every tool | §8.1 |
| HTTP: JWT audience binding (RFC 8707 / CWE-345) | §8.2 |
| HTTP: CORS allowlist, no wildcard+credentials (CWE-942) | §8.3 |
| HTTP: CSRF disabled (stateless bearer) | §11a |
| HTTP: `/actuator/health` anon, rest authenticated | §8.1, §11a |
| HTTP: `HTTP_SECURITY_ENABLED=false` is dev-only, unsafe on network | §5a, §13 (`OUT-OF-MODEL: non-default-build`) |
| Code: XML indexing XXE-hardened | §8.6, §11a |
| Code: tool behaviour hints (`readOnly`/`idempotent`/`destructive`) | §8.7 |
| README: tool set is read + additive only (no delete tool) | §12 (would change model) |

The §8/§9/§11a lists were reviewed against the source by the Apache Solr
project and carry maintainer confirmation as of 2026-07-16 (see §14). One
question — Q-tenancy — remains open by the project's choice. This document
binds the project once the PMC merges it.
