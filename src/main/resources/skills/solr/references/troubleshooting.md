# Troubleshooting tool errors

A failed call returns a result with `isError: true`. Its text starts with the server's message,
`<what failed>: <what happened>`, for example `Getting the schema failed: Solr could not be
reached. ...`. The text names no Solr host or URL. Match the part after the colon and make the call
it names.

## Any tool that calls Solr

| After the colon | Meaning | Next call |
|---|---|---|
| `collection '<name>' was not found. Hint: call list-collections ...` | Solr answered 404 for the named collection | `list-collections`, then retry with a listed name |
| `Solr answered that its admin API was not found. ...` | `list-collections`, `create-collection` or an alias tool got a 404 | Not fixable from the tool; the operator must point the Solr URL at the root of a SolrCloud cluster |
| `Solr denied access. ...` | Solr answered 401 or 403 | Not fixable from the tool; the operator must check Solr credentials and permissions |
| `Solr returned an error: <reason>` | Solr refused the request, or failed, and gave its reason | Fix the argument the reason names. If the text ends `If it persists, ask the operator to check Solr's health and logs.`, Solr itself failed: retry later |
| `Solr returned an error (HTTP <code>)` | Solr answered with an error but no reason to pass on | For a 4xx code re-check the arguments; otherwise retry later, then tell the user |
| `Solr did not answer in time. ...` | The request timed out | Retry later; if it persists, the operator must check Solr's load |
| `Solr could not be reached. ...` | Connection refused or another I/O failure | Retry once; if it persists, the operator must check that Solr is running and the Solr URL |

## `search`

After `Search failed: Solr returned an error: <reason>` for a 400, the server adds a hint:

| Hint | Meaning | Next call |
|---|---|---|
| `Hint: call list-fields on collection '...'` | `query`, `queryFields`, `filterQueries`, `facetFields` or `sortClauses` names a field the collection lacks | `list-fields`; fields Solr created while indexing may carry suffixes such as `name_s` |
| `Hint: the q parameter uses Lucene query syntax; ...` | Lucene syntax error, or a bad `{!...}` local param | Quote or escape special characters; check local params |
| `Check query syntax, query fields, filter queries, ...` | Another 400 | Re-check the parameters the hint lists; `list-fields` to verify fields |

The server also writes these itself:

| Message starts with | Meaning | Next call |
|---|---|---|
| `Search failed: queryFields cannot be combined with {!...} local params` | `queryFields` was set and `query` starts with `{!...}` | Put `qf` inside the local params, or drop the prefix |
| `Search failed: fields cannot rename another field to the unique key` | A `fields` entry such as `id:name_s` targets the unique key, which every hit reports as `id` | Choose another name for the renamed field |
| `Search failed: the collection's schema changed since its unique key was read` | The collection was recreated with another schema | Retry the search once |
| `Search failed: the collection's schema has no unique key` | The collection cannot be searched with this server | Tell the user |
| `Search failed: unable to process the Solr response` | The server could not read Solr's answer | If `facetFields` were set, `list-fields` to check them or retry without; else tell the user the operator must check server logs |

`search` reads the collection's unique key through the Schema API, so `Search failed: Solr denied
access` can appear for an account that may read documents but lacks Solr's `schema-read`
permission. The operator must grant it.

## `check-health`, `get-collection-stats`

- `check-health` never returns an error for a Solr failure. Its result has `healthy: false` and a
  `reason`, and no `pingMillis` or `numDocs`:
  - `COLLECTION_NOT_FOUND`: use a name from `list-collections`.
  - `SOLR_UNREACHABLE`: the server could not reach Solr; the operator must check Solr and its URL.
  - `PING_FAILED`: Solr answered the ping with an error.
  - `QUERY_FAILED`: the ping succeeded but a match-all query failed.
- Both accept a shard-style name such as `films_shard1_replica_n1` and resolve it to the collection.
- `get-collection-stats` can leave out `caches`, `handlers` and `metricsCore`. This is expected
  (see `references/solr-versions.md`).

## Indexing

- `documentsIndexed` lower than `documentsSubmitted`: Solr rejected some documents individually.
  See "Partial failures" in `references/indexing.md`.
- `Solr rejected every document; check the field names and types against get-schema`: every JSON or
  Markdown document failed with a 400. Run `get-schema`, fix the data or add the fields, re-send.
- `Indexing failed: Solr returned an error ...` from the CSV or XML tool: Solr rejected the payload.
  Fix it and re-send all of it.
- `XML input must be a Solr <add> block containing <doc> elements ...`: the payload is not a Solr
  `<add>` block. Send documents only.
- `Failed to parse XML document`: the XML is malformed, or carries a DOCTYPE, which is refused.
- `JSON input cannot be empty`, `XML input cannot be empty`, `Markdown input cannot be empty`:
  the payload, or one Markdown element, is empty or blank.
- `Failed to parse YAML front matter`: a Markdown document's front matter is not valid YAML.

## Schema, collections and aliases

- `Adding fields failed: Solr returned an error: ...` with a reason that says a field already
  exists: the field is already in the schema, including when it was added or guessed through another
  collection that shares a named configset. Collections created without a `configSet` have their own
  copy and do not affect each other.
- `Each field definition requires a non-empty 'name'` (or `Each field type definition ...`): a
  definition has a blank `name`. `fields must not be empty`, `fieldTypes must not be empty`: the
  list was empty.
- `Creating the collection failed: Solr returned an error: ...` naming the configset: that
  `configSet` does not exist. Omit it to get a private copy of `_default`.
- `Creating the alias failed: Solr returned an error: ...` naming a collection: that collection
  does not exist. Check with `list-collections`.
- `Alias '<name>' does not exist`: `delete-alias` was given an unknown alias. Check `list-aliases`.
- `Collection name must not be blank`, `Alias name must not be blank`, `Collections must name at
  least one collection, and no name may be blank`: an argument was empty.

## `get-cluster-status`, `list-configsets`

- Solr answers an unknown collection on `get-cluster-status` with a 400, not a 404, so the text is
  `Getting the cluster status failed: Solr returned an error: ...` with Solr's reason, followed by
  the `list-collections` hint. Use a name from `list-collections`.

## Resources

Reading `solr://collections`, `solr://{collection}/schema` or `solr://{collection}/fields` fails
with fixed text that does not include Solr's reason, such as `Failed to retrieve the schema. Hint:
...`. Call the matching tool (`list-collections`, `get-schema`, `list-fields`) to see the reason.
