# Proposal

## Why

The server's MCP surface mixes contracts the server defines with Solr's own shapes:
- Indexing results are prose `String`s.
- `list-aliases` returns Solr's comma-joined map.
- Facets and documents are untyped maps.
- `CollectionSchema` and `FieldListing` forward unknown Solr properties through `@JsonAnySetter` catch-alls.
- Prompts, resources and completions return bare `String`s and lists.
- No tool publishes an `outputSchema`, so even the typed records reach clients only as a JSON string.

Solr's response formats are implementation details that change between versions. The MCP server should absorb those changes and present stable, strongly typed contracts. 1.0.0 is unreleased (`1.0.0-SNAPSHOT`), so the shapes can change now without breaking anyone. After the release, each change would be a breaking one.

## What Changes

- **Server-owned types everywhere.**
  - Every tool, resource, prompt and completion accepts and returns records the server defines.
  - No SolrJ, solr-api or `NamedList` types; no `Map<String, Object>` results; no `@JsonAnySetter`/`@JsonAnyGetter` catch-all components.
  - The one exception is the user's own document fields.
- **Structured output.**
  - Every tool sets `generateOutputSchema = true`, so `tools/list` publishes an `outputSchema` and each call returns `structuredContent`.
  - The MCP SDK keeps adding the serialized-JSON text copy.
- **One failure channel.** Failures are exceptions, which reach the client as `isError` results. Results no longer carry `success`/`message` flags or timestamps taken from the server's clock.
- **BREAKING `search`:**
  - `documents` becomes `List<SearchHit(id, score, fields)>`, with `id` read from the collection's uniqueKey field.
  - `facets` becomes `List<FacetField(field, values: List<FacetValue(value, count)>)>`, which keeps Solr's count order.
- **BREAKING indexing tools:** all four return `IndexingResult` instead of a prose `String`.
- **BREAKING schema tools:**
  - `CollectionSchema` and `FieldListing` lose their `other` catch-alls and stop exposing SolrJ's `FieldFlag` enum.
  - `FieldListing.fields` becomes a list.
  - `add-fields` takes `List<FieldDefinition>` instead of `List<Map<String, Object>>`.
  - `add-field-types` takes `List<FieldTypeDefinition>`.
- **BREAKING collection tools:**
  - `list-collections` returns `CollectionList` instead of a bare array.
  - `create-collection` reports the configset, shard count and replication factor it used, without `success`/`message`/`createdAt`.
  - `check-health` returns `HealthStatus`, whose `reason` is a fixed server-owned string rather than Solr's exception text.
  - `get-collection-stats` returns `CollectionMetrics` with no server timestamp.
- **BREAKING alias tools:**
  - `list-aliases` returns `AliasList(List<Alias(name, collections, properties)>)`, with targets as a list.
  - `create-alias` takes its target `collections` as a list instead of a comma-joined string, and returns the `Alias` as Solr holds it after the call.
  - `delete-alias` returns `AliasDeletion`.
- **Prompts, resources, completions:**
  - Prompts return `GetPromptResult`.
  - Resources return `ReadResourceResult` with `application/json` `TextResourceContents`.
  - Completions return `CompleteResult` with `total` and `hasMore`, so the 100-name cap is visible to the client.
- **Package layout:**
  - Records live in per-domain packages (`alias/`, `collection/`, `indexing/`, `schema/`, `search/`), one top-level public record per file.
  - Part-records are nested inside their parent.
  - `collection/Dtos.java` is deleted, and `AliasService` moves to `alias/`.

## Capabilities

### New Capabilities
- `mcp-contracts`: Rules every MCP surface follows — server-owned typed inputs and outputs, a published output schema, failures as `isError`, no catch-all or Solr-typed components, and MCP's typed results for prompts, resources and completions.
- `search`: The `search` tool's result contract — hits, scores, the uniqueKey id, and facets.
- `indexing`: The result contract of the four indexing tools.
- `schema-management`: The contracts of `get-schema`, `list-fields`, `add-fields` and `add-field-types`, and the schema and fields resources.
- `collection-management`: The contracts of `list-collections`, `get-collection-stats`, `check-health` and `create-collection`, and the collections resource.
- `alias-management`: The contracts of `list-aliases`, `create-alias` and `delete-alias`.

### Modified Capabilities
None. `openspec/specs/` has no capabilities yet.

## Impact

- **Code:** every `*Service` under `src/main/java/org/apache/solr/mcp/server/`.
  - New records in each domain package; `collection/Dtos.java` and `collection/AliasResult.java` removed.
  - `SolrNativeHints` updates its MCP response-record list.
  - `PromptText`/`PromptNames` are unchanged apart from the prompt return type.
- **Clients:** every tool's output JSON changes shape, and `add-fields`/`add-field-types` inputs change. The six prompts and three resources keep their names and URIs.
- **Tests:**
  - Unit tests and `McpClientIntegrationTestBase` assert the new shapes.
  - A new test checks every tool's `structuredContent` against its published `outputSchema` on both the legacy and modern protocols.
- **Open PRs:**
  - #294 (Solr-shaped `AliasList`/`CollectionList` with `other`) is reworked as the collection and alias step.
  - #270 (`get-cluster-status` as `Map<String, Object>`) needs typed records before it merges.
- **Docs:** README tool tables and the docs site's tool pages show the new outputs.
