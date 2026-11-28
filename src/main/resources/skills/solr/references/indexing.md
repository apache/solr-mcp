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
- An empty `documents` array fails with `JSON input cannot be empty`.

## CSV and XML

Forwarded to Solr's own update handlers; the server does not parse the content.

- `index-xml-documents` takes Solr `<add>` blocks of `<doc>` elements only: send
  `<add><doc><field name="...">` content and nothing else.
- Solr accepts or rejects the payload as a whole. A rejected payload fails the call with a message
  that starts `Indexing failed: Solr returned an error` and quotes Solr's reason when Solr gave one.
  Fix the payload and re-send all of it.

## Markdown

- A single string that begins with `---` and contains several front-matter blocks, each preceded by a
  blank line, is split into several documents. `documentsSubmitted` counts the documents after
  splitting, so it can be larger than the number of array elements.
- An empty or blank element fails the whole call with `Markdown input cannot be empty`, and front
  matter that is not valid YAML fails it with `Failed to parse YAML front matter`.
- Front matter values are indexed as text, whatever YAML would infer: `2024` and `true` arrive as
  strings. Sequences become multi-valued fields and nested mappings are flattened with underscores.
- Extracted fields: `title` (front matter `title`, else the first level-1 heading), `headings`
  (every heading), `content` (body without front matter).

## Reading the result

Every result names the `collection` and the `format` (`json`, `csv`, `xml` or `markdown`).

- JSON and Markdown add `documentsSubmitted`, `documentsIndexed`, `fieldNames` and `fieldCount`.
  `fieldNames` holds up to 50 field names, sorted, taken from the documents Solr accepted, so it
  shows names after flattening. `fieldCount` is the total number of those names.
- CSV and XML add nothing more, because Solr's update response carries no document count. Confirm
  the count with `search` (`query=*:*`, `rows=0`, read `numFound`) or with `check-health`
  (`numDocs`).

## Partial failures (JSON and Markdown)

If Solr rejects the batch with a 400 (usually an unknown field or a value of the wrong type), the
server retries the documents one at a time so the valid ones are indexed, then commits. A
`documentsIndexed` lower than `documentsSubmitted` means Solr rejected the difference. The result
does not say which documents failed or why. When `fieldCount` is 50 or less, `fieldNames` is the
full list, so a field you sent that is missing from it had no value or was only in rejected
documents. Run `get-schema`, compare the failing fields with the schema, fix the data or add the
missing field, and re-send. The retry sends every document of the batch again, including any that
Solr took before the batch failed, so documents without an `id` can be indexed twice.

If Solr rejects every document, the call fails with
`Solr rejected every document; check the field names and types against get-schema`. Failures other
than a 400 (unknown collection, authorisation, Solr unreachable) are not retried and fail the call
with an `Indexing failed: ` message.

## Fields you did not create

On the `_default` configset, Solr creates a field the first time it sees it and picks the type from
that first value. Fields created this way hold arrays when read back. A wrong guess cannot be
changed with this server, which cannot alter existing fields. A collection created without a
`configSet` has its own copy of `_default`, so a guess stays in that collection; one created with a
named `configSet` shares it (see `SKILL.md`), so a guess there affects every collection on that
configset. When a field's type matters, call `add-fields` first, then index.
