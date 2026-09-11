# Tutorial: Your First Collection

A hands-on walkthrough that goes from an empty Solr to a properly designed,
queryable collection — entirely through natural-language conversation with an
AI assistant.

The point of this tutorial is not just *how* to index data. It's **why field
types matter**. Define the schema first, index a saved file, and ask useful
questions immediately. Schemaless pitfalls are explained below, not required
as a detour through a broken collection.

**Time:** about 15 minutes.

```
  1. Start Solr           empty SolrCloud, one container
  2. Save the dataset     reuse the file without repeating its contents
  3. Design a schema      types chosen for the questions you ask
  4. Index and verify     use the tool's actual document count
  5. Search               filters, facets, ranges, sorting
  6. Introspect           stats, health, schema
```

---

## Before you start

You need two things.

**A running Solr.** This tutorial starts one in a single container — no clone
and no build required for Solr itself:

```bash
docker run -d --name solr-tutorial -p 8983:8983 solr:9-slim solr start -c -f
```

The `-c` matters: it starts SolrCloud with embedded ZooKeeper, which is what the
collection management tools need. Give it a few seconds, then confirm it answers:

```bash
curl "http://localhost:8983/solr/admin/collections?action=LIST"
```

An empty `"collections":[]` is the correct starting point.

**An MCP client connected to the Solr MCP server.** Any of the supported clients
work — see the [client setup guides](clients/) — and the rest of this tutorial is
client-agnostic. Everything in a blockquote below is something you say to your
assistant; everything in a code block is either a shell command or data you paste.

Confirm the connection before continuing:

> *"What Solr collections are available?"*

An empty list means the server is wired up correctly.

---

## The dataset

61 streaming television shows, with a mix of field shapes that make schema design
matter: single-valued text, repeated categories, integers, and a decimal rating.

```bash
curl -O https://raw.githubusercontent.com/apache/solr-mcp/main/src/test/resources/shows.json
```

```json
{
  "id": "netflix-001",
  "title": "Stranger Things",
  "platform": "Netflix",
  "genres": ["Sci-Fi", "Horror", "Drama"],
  "release_year": 2016,
  "seasons": 5,
  "imdb_rating": 8.7,
  "cast": ["Millie Bobby Brown", "Finn Wolfhard"],
  "tags": ["80s", "supernatural"],
  "description": "A group of kids in 1980s Indiana uncover supernatural mysteries..."
}
```

For file ingestion, set `SOLR_MCP_INGEST_ROOT` in the **MCP server's environment**
to the absolute directory containing `shows.json`, then restart that server.
Use a dedicated data directory, not your home directory. The `index-json-file`
tool accepts paths relative to this root (such as `shows.json`) or absolute
paths inside it. It reads UTF-8 JSON up to 10 MiB and returns counts, not content.

The path is on the **server**, not necessarily on your client. For Docker, mount
the data directory read-only, set the root to its container path, and pass that
path to the tool. For a remote server without a shared directory, use
`index-json-documents` with inline JSON instead. The server does not fetch URLs;
download once on the client into the shared directory. Do not ask the model to
reconstruct the entire dataset from memory.

---

## Step 1 — Design the schema first

Now create the collection and define its fields **before** any documents arrive.

> *"Create a Solr collection called shows."*

Then describe the schema you want:

> *"Add these fields to the shows collection schema: title and description as
> single-valued text_general; platform, status, country, language and rating as
> single-valued strings with docValues; genres, tags, cast and creators as
> multi-valued strings with docValues; release_year, end_year, seasons and
> episodes as single-valued pint with docValues; imdb_rating as single-valued
> pdouble with docValues."*

The assistant will translate that into `add-fields` calls. The reasoning behind
each choice:

| Choice | What it buys you |
|--------|------------------|
| `string` rather than `text_general` | Exact values. "Amazon Prime Video" stays one facet bucket instead of disappearing into tokens. |
| `docValues: true` | The column-oriented structure that makes faceting and sorting efficient. |
| `pint` / `pdouble` | Real numbers, so range filters like `[2020 TO *]` and numeric sorting work. |
| Single-valued where the data is single-valued | A rating is a scalar, not a list; sorting needs no implicit minimum/maximum selection. |
| `text_general` kept for prose | Analysis and tokenizing is exactly right for `title` and `description`. |

Note that the difference is not "strings are better than text". Both types appear
in this schema. The difference is matching the type to how the field will be
*queried* — categories get exact matching, prose gets analysis.

## Step 2 — Index and verify

> *"Use index-json-file with collection shows and path shows.json. Report the
> tool's actual successful and total counts."*

Expect `Successfully indexed 61 of 61`. Confirm with `search`, `query=*:*`,
`rows=0`: `numFound` should be 61. You can reuse the same file path to index
another prepared collection without resending the JSON. Reusing IDs in the same
collection updates documents, so a second call still leaves 61 documents.

Then ask:

> *"Show me the breakdown of shows by platform."*

```
Netflix 20, Amazon Prime Video 20, HBO Max 7, Apple TV+ 4,
Disney+ 4, Hulu 3, Paramount+ 2, Peacock 1
```

The category field preserves exact platform names, so the breakdown answers the
question rather than counting analyzed word tokens.

---

## Step 3 — Search

Each of these exercises a different Solr capability. The parameter each one drives
is noted so you can connect the natural-language request to what actually runs.

| Ask your assistant | Exercises |
|--------------------|-----------|
| *"Which shows have 'dragon' in the title?"* | `q` — full-text search on an analyzed field |
| *"Find shows whose description contains the exact phrase 'a group of'."* | `q` — phrase query |
| *"Show me everything on Netflix."* | `fq` — exact filter on a string field |
| *"Which shows were released in 2020 or later?"* | `fq` — numeric range, `release_year:[2020 TO *]` |
| *"Find all the comedies."* | `fq` — membership in a multi-valued field |
| *"Break the collection down by genre and by status."* | `facet` — multiple fields at once |
| *"What are the five highest rated shows?"* | `sortClauses` + `rows` |
| *"Show me shows from 2015 onward rated 8 or above, sorted by rating, with a platform breakdown."* | everything combined |

The last one is the interesting one. You did not write:

```
q=*:*&fq=release_year:[2015 TO *]&fq=imdb_rating:[8 TO *]&sort=imdb_rating desc&facet=true&facet.field=platform&rows=5
```

The assistant did, because the tool descriptions told it how. That translation is
what the server exists to provide.

---

## Step 4 — Ask about the index itself

Search is the headline, but the operational tools are what make this useful in a
real workflow.

> *"Is the shows collection healthy?"*

> *"Give me the stats for the shows collection."*

> *"What's my query result cache hit ratio, and what does that tell me?"*

> *"Explain the shows schema — which fields can I facet on, and which can I sort by?"*

That last question is worth asking. The assistant has both the schema and an
understanding of Solr's type semantics, so it can answer a question that would
otherwise mean reading `managed-schema` and knowing what `docValues` implies.

---

## Why not index schemaless first?

The `_default` configset can guess types for unknown fields, but it does not know
your intended queries. It may infer `text_general` without docValues for
`platform`, and multi-valued `pdoubles`/`plongs` for scalar numbers. Faceting an
analyzed category can return token buckets, no buckets, or an error depending on
the configuration and Solr version — not reliable exact-category counts.

If you already indexed this way, inspect the relevant fields and copy-field
rules. An existing string copy such as `platform_str` can provide exact facets;
do not assume the sibling exists without checking. For a durable fix, use a
clean configset, define the schema, and reindex from the saved file. These MCP
tools only **add** fields and cannot change an existing field's type. There is
no need to deliberately break and reset Solr to complete this tutorial.

## Known issues

Two rough edges worth knowing about, especially when reusing an existing Solr.

**Collections share the `_default` configset**
([#183](https://github.com/apache/solr-mcp/issues/183)). `create-collection` binds
each collection to the shared `_default` configset rather than copying it, so
schemaless field guesses leak into every collection created afterwards. Symptoms
are `add-fields` failing with `Field 'x' already exists` on a brand-new collection,
or a "schemaless" collection silently inheriting another collection's explicit
types. Use a fresh, isolated configset for a different schema; simply restarting
a persistent Solr instance does not reset its managed schema.

**Unknown search parameters are dropped silently.** Arguments the `search` tool does
not declare are ignored rather than rejected, so a misnamed one looks like a query
that simply did not do what you asked — results come back, just unsorted or
unfiltered. If a result set ignores part of your request, check the tool's parameter
names via your client's tool inspector before assuming the data is wrong.

---

## Where to go next

- **[Client setup guides](clients/)** — Claude Desktop, Claude Code, VS Code /
  Copilot, Cursor, JetBrains, MCP Inspector
- **[Security](security/)** — the deployment model, the HTTP transport, and OAuth2
  setup with Auth0 or Keycloak
- **[Observability](observability.md)** — traces, metrics and logs via OpenTelemetry
- **[FAQ](FAQ.md)** — including why an MCP server rather than a prompt-level skill

Things worth trying with what you have running:

- Design a schema for your own JSON, CSV or XML, then index and verify it.
- Add a `DenseVectorField` with `add-field-types` and try vector search.
- Describe a dataset in words and ask the assistant to design a schema for it.
- Point the server at a Solr you already run — `SOLR_URL` is the only setting.

## Clean up

```bash
docker rm -f solr-tutorial
```
