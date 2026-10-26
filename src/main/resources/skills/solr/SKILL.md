---
name: solr
description: Use when searching, indexing, or managing Apache Solr collections through the Solr MCP server (tools such as search, index-json-documents, get-schema, add-fields, create-collection, list-collections), or when the user asks about their Solr data, collections, or schema. Explains how the server's tools behave, what to know before writing data, how their results and errors read, and where results depend on the Solr version. It does not teach Solr itself; for query syntax, analysis, and schema design use the Solr Reference Guide.
license: Apache-2.0
metadata:
  version: "1.0"
---

# Solr MCP server

This skill covers what is specific to this server's tools. It is not a Solr tutorial. For anything
about Solr itself (query parsers, analyzers, field types, faceting, relevance), consult the
[Solr Reference Guide](https://solr.apache.org/guide/solr/latest/) through your own knowledge,
web search, or a documentation-lookup tool, and match the guide version to the Solr in use.

## Which tool

| Task | Tool |
|---|---|
| See what collections exist | `list-collections` (also the `solr://collections` resource) |
| See fields and types | `get-schema` (also the `solr://{collection}/schema` resource) |
| Search, filter, facet, sort, page | `search` |
| Index JSON / CSV / Solr update XML / Markdown | `index-json-documents`, `index-csv-documents`, `index-xml-documents`, `index-markdown-documents` |
| Add fields or field types | `add-fields`, `add-field-types` |
| Create a collection | `create-collection` |
| Point a name at collections | `list-aliases`, `create-alias`, `delete-alias` |
| Size, query timings, cache and handler metrics | `get-collection-stats` |
| Is the collection reachable | `check-health` |

Pick the index tool by input format; do not convert JSON or CSV to Markdown to index it.

## Before you write data

1. **Collections share a schema by default.** `create-collection` uses the `_default` configset
   unless told otherwise, and collections created that way share one schema. A field added with
   `add-fields`, or created by Solr while indexing, on one collection shows up on the others, and a
   later `add-fields` for the same name fails because the field already exists. Run `get-schema`
   on a collection before assuming it is empty, and choose field names and types with the whole
   cluster in mind.
2. **Give every document a stable `id`.** Without one, Solr's default update chain generates a random
   id for JSON, CSV and XML documents, so sending the same payload twice creates duplicates.
   Markdown documents without an `id` get one derived from their text: identical text replaces
   itself, edited text becomes a new document.
3. **Field names and values are not always stored as sent.** Nested JSON objects are flattened with
   underscores, and objects or arrays nested inside a JSON array are dropped. Fields that Solr
   creates while indexing hold arrays when read back. Run `get-schema` before querying fields you
   did not create. Details are in `references/indexing.md`.
4. **Each index call commits.** The documents are searchable when the call returns; there is no
   commit tool and none is needed.

## Reading results

- `check-health` reports an unhealthy or unknown collection in its result, not as a tool error.
- `get-collection-stats` can return `null` for `cacheStats` and `handlerStats`. That is expected.
- A tool call that fails with a protocol-level error and no message usually means Solr could not be
  reached or is misconfigured, not that the request was wrong.

## References

Open a reference only when the task needs it.

- `references/indexing.md` - what the `index-*` tools do to your data, how responses read, partial
  failures, and field-type surprises.
- `references/troubleshooting.md` - the server's error messages, what each means, and the next call.
- `references/solr-versions.md` - what differs across the supported Solr versions (9.4 to 10) as
  seen through the tools.
