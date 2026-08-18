# Frequently Asked Questions

## LLMs already know Solr. Could a Solr skill replace this MCP server?

**No — they're complementary.** Anthropic teaches the agent stack as five
distinct layers:[skilljar]

- **CLAUDE.md** — always-on project standards
- **Skills** — task-specific expertise that loads on demand
- **Hooks** — automated operations triggered by events
- **Subagents** — isolated execution contexts for delegated work
- **MCP servers** — external tools and integrations

A Solr skill and this server sit on different layers and do different jobs:
the skill teaches an agent *how to use Solr well*; this server is the live
integration that makes the operations *safe, deterministic, and auditable*.
Anthropic's engineering blog reinforces the split — Skills "complement
Model Context Protocol (MCP) servers by teaching agents more complex
workflows that involve external tools and software."[skills-blog]

### What a skill could do

Query craft and read-mostly knowledge: building `q`/`fq`/`facet`, choosing
analyzers, interpreting `get-schema`, deciding when to `list-collections` vs
`create-collection`. For one developer against a Solr they control, a skill
plus `curl` covers a lot.

### What this server does that a skill can't

Behavior that is **code, not knowledge** — an agent emitting raw HTTP would
re-derive it imperfectly every call:

- **Indexing resilience** — 1000-doc batches, single commit, per-doc retry to
  salvage valid docs from a failed batch.
- **Format hardening** — nested-object flattening, field sanitization, 10 MB
  guards, **XXE-hardened XML parsing**.
- **Metric aggregation** — `get-collection-stats` folds Luke + Metrics APIs,
  normalizes shard names, and degrades gracefully on Solr 10.
- **Typed contracts** — every tool returns the same typed record; the model
  doesn't reparse raw JSON each call.
- **Auth & observability** — HTTP mode authenticates server-side (no raw
  secrets in agent context), calls are logged and auditable, and config lives
  in one place instead of every developer's skill file.

### Decision framework

| Criterion     | Reach for a **Skill**     | Reach for **this MCP server**          |
|---------------|---------------------------|----------------------------------------|
| Providing     | A pattern / process       | Access to a live service               |
| Content       | Static, team-curated      | Real-time data and side effects        |
| Auth          | None                      | OAuth2 (HTTP); OS-user trust (STDIO)   |
| Audit         | Not centrally observable  | Logged, rate-limitable, auditable      |
| Determinism   | Sampled each call         | Same input → same output               |
| Reuse         | Skill-aware agents only   | Any MCP client                         |

### Bottom line

Keep the server for deterministic, security-sensitive, multi-step operations
and for non-Claude clients (it's an Apache incubating project for *any* MCP
client). Add a thin Solr skill for query and faceting know-how. The skill
makes the agent better at *using* Solr; the server makes the dangerous parts
*safe and repeatable*.

## Doesn't an MCP server cost more context than a skill?

**At rest, yes; at runtime, often no.** A skill places only its metadata
(~100 tokens of `name` + `description`) in the system prompt; its
`SKILL.md` body and bundled files load via progressive disclosure only
when triggered.[skills-overview] An MCP server's tool definitions sit in
context for the whole session whether the agent uses them or not. As
Anthropic puts it, *"tool descriptions occupy more context window
space"*, and at scale agents *"need to process hundreds of thousands of
tokens before reading a request."*[code-execution]

For this server (11 tools across search, indexing, schema, and
collections), the upfront overhead is a few thousand tokens — real but
bounded.

Two factors close the gap at runtime:

- **Typed, compact returns.** Every tool returns its own purpose-built
  typed record (`SearchResponse`, `SolrHealthStatus`, `IndexStats`, …),
  not raw Solr JSON the model must reparse. Over a multi-turn agent run, leaner tool output
  offsets the upfront schema cost.
- **Code execution with MCP.** Anthropic's pattern of discovering tool
  definitions on demand inside a code-execution loop cut a reference
  workload from **150,000 to 2,000 tokens — a 98.7%
  reduction.**[code-execution]

The strong move is **both**: a thin skill for query/faceting know-how
(progressive disclosure keeps upfront cost near-zero), plus this server
for the live operations (typed records keep per-call cost low). For
indexing or schema-introspection turns that fan out across many calls,
delegate to a subagent — Anthropic's stack lists subagents as their own
layer specifically for isolating heavy work from the main
thread.[skilljar]

## Sources

- Anthropic — [Introduction to Agent Skills][skilljar] (course)
- Anthropic — [Equipping agents for the real world with Agent Skills][skills-blog]
- Anthropic — [Agent Skills overview][skills-overview]
- Anthropic — [Code execution with MCP][code-execution]

[skilljar]: https://anthropic.skilljar.com/introduction-to-agent-skills/434528
[skills-blog]: https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
[skills-overview]: https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview
[code-execution]: https://www.anthropic.com/engineering/code-execution-with-mcp
