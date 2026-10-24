# What the index tools do to your data

The four `index-*` tools send the payload to Solr in one update request that also commits, so the
documents are searchable when the tool returns. For what fields, types and analyzers mean in Solr,
see the Solr Reference Guide.

## Ids and re-sending

- JSON, CSV and XML: whatever `id` you give is used. Without one, Solr's default update chain
  assigns a random id, so re-sending creates new documents instead of replacing old ones.
- Markdown: a document without an `id` gets one derived from its text. The same text replaces
  itself; an edited document is a new one. Put a stable `id` in the front matter to update in place.

## JSON

- Each object becomes one document. Field names are used as sent.
- Nested objects are flattened with underscores: `{"author": {"name": "X"}}` is indexed as
  `author_name`.
- Inside an array, only scalar items are kept. Objects and arrays nested in an array are dropped
  without a warning, and an array left with no scalars produces no field.
- The response ends with the indexed field names (at most 50, then a count of the rest). The list is
  built from every submitted document, including any that Solr then rejected, so a listed name may
  not exist in the schema.

## CSV and XML

Forwarded to Solr's own update handlers; the server does not parse the content.

- The only check the server makes is that XML parses, has no DOCTYPE, and has `<add>` as its root
  element. The tool is for documents: send `<add><doc><field name="...">` content only.
- Solr's response carries no document count, so none is reported. Confirm with `search` or
  `get-collection-stats`.
- When Solr rejects a payload, fix it and re-send. The server does not retry these individually.

## Markdown

- A single string that begins with `---` and contains several front-matter blocks, each preceded by a
  blank line, is split into several documents. The `M` in the response counts documents after
  splitting, not array elements.
- An empty or blank element fails the whole call with `Markdown input cannot be null or empty`.
- Front matter values are indexed as text, whatever YAML would infer: `2024` and `true` arrive as
  strings. Sequences become multi-valued fields and nested mappings are flattened with underscores.
- Extracted fields: `title` (front matter `title`, else the first level-1 heading), `headings`
  (every heading), `content` (body without front matter).
- Unlike the JSON tool, the response does not list the indexed field names.

## Reading the response

- JSON and Markdown: `Successfully indexed N of M documents into collection '...'`.
- CSV and XML: `Solr accepted the ... and committed it (status S, T ms)`.

## Partial failures (JSON and Markdown)

If Solr rejects the batch with a 400 (usually an unknown field or a value of the wrong type), the
server retries the documents one at a time so the valid ones are indexed, then commits. The response
gives only the count; it does not say which documents failed or why. If `N` is less than `M`, run
`get-schema`, compare the failing fields with the schema, fix the data or add the missing field, and
re-send. Documents without an `id` that were already accepted before the failure may be indexed
again on the retry, so unique ids matter most here. Failures other than a 400 (unknown collection,
authorisation, Solr unreachable) are not retried and are returned as errors.

## Fields you did not create

On the `_default` configset, Solr creates a field the first time it sees it and picks the type from
that first value. Fields created this way hold arrays when read back. A wrong guess cannot be
changed with this server, which cannot alter existing fields, and because collections created with
defaults share one schema (see `SKILL.md`), it affects every collection on that configset. When a
field's type matters, call `add-fields` first, then index.
