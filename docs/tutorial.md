# Tutorial: Your First Collection

A hands-on walkthrough that goes from an empty Solr to a properly designed,
queryable collection — entirely through natural-language conversation with an
AI assistant.

The point of this tutorial is not just *how* to index data. It's **why field
types matter**. You will index a dataset twice: once letting Solr guess, once
choosing types deliberately, and see exactly what the difference buys you.

**Time:** about 15 minutes.

```
  1. Start Solr           empty SolrCloud, one container
  2. Index blind          61 documents, zero schema work        <- it just works
  3. Inspect the guess    what Solr decided on your behalf      <- and here's the catch
  4. Design a schema      types chosen for the questions you ask
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

If your client cannot read local files, paste the JSON contents directly into the
conversation instead of referencing the path.

---

## Step 1 — Index without a schema

Solr's `_default` configset runs in **schemaless** (data-driven) mode: send it
documents containing fields it has never seen, and it will invent types for them.

> *"Create a Solr collection called shows-auto."*

> *"Index the contents of ./shows.json into the shows-auto collection."*

You should get `61 of 61 documents`. No schema, no field definitions, no
configuration — and it worked.

This is genuinely useful. Schemaless mode exists so you can get data in and start
exploring before you know what questions you'll ask. The trouble starts when you
ask them.

---

## Step 2 — Ask a real question

> *"Show me the breakdown of shows-auto by platform."*

This is the most ordinary business question imaginable, and the answer comes back
empty:

```json
{ "numFound": 61, "documents": [], "facets": { "platform": {} } }
```

Read that carefully, because it is worse than an error. Sixty-one documents
matched. The query succeeded. Solr simply has no breakdown to give you, and it
says so without complaining. Nothing here tells you that the *data* is fine and
the *field type* is the problem — which is exactly the failure mode that makes
schemaless deceptive.

To see the cause, look at what Solr decided on your behalf:

> *"Show me the schema for shows-auto."*

| Field | Solr guessed | What that costs you |
|-------|-------------|---------------------|
| `platform` | `text_general` | Tokenized and analyzed, so it is no longer one value. Searching `platform:prime` matches all 20 Amazon Prime Video shows — nonsense for a category — and faceting it yields **no buckets at all**. |
| `title` | `text_general` | Searchable, but not sortable or exact-matchable. |
| `imdb_rating` | `pdoubles` | Note the trailing `s` — that plural means **multi-valued**. Every rating is a list, so "highest rated" is not a well-defined question. |
| `release_year` | `plongs` | Multi-valued too, which makes range filtering awkward. |

Look at any document that comes back and the giveaway is visible — every field is
wrapped in an array:

```json
"title": ["Stranger Things"], "imdb_rating": [8.7]
```

Solr saw one sample of each field and had no reason to assume it would not repeat,
so it hedged on all of them.

**The lesson:** schemaless is an on-ramp, not a destination. Solr guessed from a
single document with no knowledge of what you would later want to ask. Faceting,
range filtering and sorting all depend on types chosen with those questions in
mind.

---

## Step 3 — Reset

Field types **cannot be changed once created**. Worse, at present every collection
created through `create-collection` shares the same `_default` configset, so the
guesses from Step 1 are already baked in and a new collection would inherit them
(see [Known issues](#known-issues)).

So start from a clean slate:

```bash
docker rm -f solr-tutorial
docker run -d --name solr-tutorial -p 8983:8983 solr:9-slim solr start -c -f
```

This takes a few seconds. Wait for the collections endpoint to answer before
continuing.

---

## Step 4 — Design the schema first

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
| Single-valued where the data is single-valued | You can sort on it. Sorting by a multi-valued field is not meaningful. |
| `text_general` kept for prose | Analysis and tokenizing is exactly right for `title` and `description`. |

Note that the difference is not "strings are better than text". Both types appear
in this schema. The difference is matching the type to how the field will be
*queried* — categories get exact matching, prose gets analysis.

Now index the same data into the new collection:

> *"Index the contents of ./shows.json into the shows collection."*

And ask the question that failed in Step 2:

> *"Show me the breakdown of shows by platform."*

```
Netflix 20, Amazon Prime Video 20, HBO Max 7, Apple TV+ 4,
Disney+ 4, Hulu 3, Paramount+ 2, Peacock 1
```

Same data, same question, same tool. The only thing that changed is that someone
decided what the fields meant.

---

## Step 5 — Search

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

## Step 6 — Ask about the index itself

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

## Known issues

Two rough edges worth knowing about. The first is a tracked defect you will meet
while following this tutorial; neither is a mistake on your part.

**Collections share the `_default` configset**
([#183](https://github.com/apache/solr-mcp/issues/183)). `create-collection` binds
each collection to the shared `_default` configset rather than copying it, so
schemaless field guesses leak into every collection created afterwards. Symptoms
are `add-fields` failing with `Field 'x' already exists` on a brand-new collection,
or a "schemaless" collection silently inheriting another collection's explicit
types. Restarting Solr resets it, which is why Step 3 exists.

**Unknown search parameters are dropped silently.** The `search` tool takes sorting
as `sortClauses`, a list of objects shaped `{"item": "imdb_rating", "order": "desc"}`.
A differently-named parameter is ignored rather than rejected, so a wrong key looks
like a query that simply did not sort.

---

## Where to go next

- **[Client setup guides](clients/)** — Claude Desktop, Claude Code, VS Code /
  Copilot, Cursor, JetBrains, MCP Inspector
- **[Security](security/)** — the deployment model, the HTTP transport, and OAuth2
  setup with Auth0 or Keycloak
- **[Observability](observability.md)** — traces, metrics and logs via OpenTelemetry
- **[FAQ](FAQ.md)** — including why an MCP server rather than a prompt-level skill

Things worth trying with what you have running:

- Index your own JSON, CSV or XML and see what the schema guesser makes of it.
- Add a `DenseVectorField` with `add-field-types` and try vector search.
- Describe a dataset in words and ask the assistant to design a schema for it.
- Point the server at a Solr you already run — `SOLR_URL` is the only setting.

## Clean up

```bash
docker rm -f solr-tutorial
```
