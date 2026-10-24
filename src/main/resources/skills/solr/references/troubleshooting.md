# Troubleshooting tool errors

The server rewrites most Solr failures into short messages that say what to do next. Match the
message and make the call it names.

## No message at all

A call that fails with a protocol-level error and no text, from `search`, the `index-*` tools or
`get-schema`, is a failure to reach Solr or to talk to it (connection refused, I/O error), not a
problem with your arguments. Retry once; if it persists, tell the user the operator must check the
Solr URL, network and credentials. The `Search failed: unable to communicate with Solr` message is
written for this case but may not reach you.

## `search`

| Message starts with | Meaning | Next call |
|---|---|---|
| `Search failed: the collection was not found` | The `collection` does not exist | `list-collections`, then retry with a listed name |
| `Search failed: a referenced field is not defined` | `query`, `filterQueries`, `facetFields` or `sortClauses` names a field the schema lacks | `get-schema`; fields Solr created while indexing may carry suffixes such as `name_s` |
| `Search failed: Solr could not parse the query or filters` | Lucene syntax error, or a bad `{!...}` local param | Quote or escape special characters; check local params |
| `Search failed: Solr rejected the request` | Another 400 | Re-check syntax, filters, facets, sort, pagination; `get-schema` to verify fields |
| `Search failed: Solr denied access` | Solr returned 401 or 403 | Not fixable from the tool; the operator must check Solr credentials and permissions |
| `Search failed: unable to process the Solr response` | Any unexpected failure reading the response | If `facetFields` were set, `get-schema` to check them or retry without; else tell the user the operator must check server logs |
| `Search failed: Solr could not complete the request` | Any other Solr error | Retry later; if it persists, the operator must check Solr health and logs |

## `get-collection-stats`, `check-health`

- `Collection not found: <name>. Hint: call list-collections ...`: use a name from `list-collections`.
- Both accept a shard-style name such as `films_shard1_replica_n1` and resolve it to the collection.
- `check-health` does not raise an error for a down or unknown collection; it returns an unhealthy
  status with the failure message. Read the result.
- `get-collection-stats` returns `null` for `cacheStats` and `handlerStats` in several situations.
  This is expected, not an error (see `references/solr-versions.md`).

## Indexing

- `Successfully indexed N of M` with N < M: some documents were rejected individually. See
  "Partial failures" in `references/indexing.md`.
- `XML input must be a Solr <add> block containing <doc> elements; <x> is not accepted by this tool`:
  the XML root is not `<add>`. Send documents only.
- `Failed to parse XML document`: the XML is malformed, or carries a DOCTYPE, which is refused.
- `Markdown input cannot be null or empty`: one of the array elements is blank.
- A CSV or XML payload that Solr rejects returns Solr's error text. Fix the payload and re-send.

## Schema and collections

- `add-fields` fails with an "already exists" message when the field is already in the schema,
  including when it was added or guessed through another collection on the same configset.
- `Each field definition requires a non-empty string 'name'`: a field map has no `name`.
- `Collection name must not be blank`, `Alias name must not be blank`: an argument was empty.
