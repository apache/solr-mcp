# Frequently Asked Questions

## LLMs already know Solr. Could a Solr skill replace this MCP server?

**No — they're complementary.** Anthropic frames it directly:
[Skills "complement Model Context Protocol (MCP) servers by teaching agents
more complex workflows that involve external tools and software."][skills-blog]
A skill teaches an agent *how to use Solr well*; this server makes the
operations *safe, deterministic, and auditable*.

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

## Sources

- Anthropic — [Equipping agents for the real world with Agent Skills][skills-blog]
- Anthropic — [Agent Skills overview](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)
- Anthropic — [Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)

[skills-blog]: https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills
