# Design

## Context

See proposal.md (Why) for the motivation and specs/ for the required behaviour. This design covers how the server meets them on the current stack: Spring Boot 4.2.0, Spring AI 2.1.0, MCP Java SDK 2.x, and both the legacy and modern (`MCP_PROTOCOL=modern`) protocols.

How the stack treats tool results today (verified in the 2.1.0 jars):

- `@McpTool.generateOutputSchema` defaults to `false`, and no tool sets it. In `ReturnMode.TEXT`, `AbstractMcpToolMethodCallback.convertValueToCallToolResult` serializes the return value into one text block. No `outputSchema` and no `structuredContent` reach the client.
- In `ReturnMode.STRUCTURED` it sets only `structuredContent`. The SDK's legacy `McpAsyncServer`/`McpStatelessAsyncServer` `StructuredOutputCallToolHandler` then:
  1. adds the JSON as a `TextContent` block when `content` is empty;
  2. **validates `structuredContent` against `outputSchema`**, failing the call on a mismatch.
  The modern server path (`io.modelcontextprotocol.modern`) has not been checked.
- `McpJsonSchemaGenerator.generateFromType` calls Spring AI's `JsonSchemaGenerator` with `ALLOW_ADDITIONAL_PROPERTIES_BY_DEFAULT`. `AbstractSpringAiSchemaModule.checkRequired` decides `required` from `@ToolParam`, `@JsonProperty` and swagger `@Schema`, then from Spring's `Nullness.forField`, which reads JSpecify `@Nullable`. A `@Nullable` component is not required, and every other property is.
- Prompt, resource and completion callbacks already accept `GetPromptResult`, `ReadResourceResult` and `CompleteResult`. String returns are wrapped by the framework.

The code already has server-owned records that this change reshapes or replaces:
- `CollectionSchema`, `FieldListing` and `FieldFlags` (schema).
- `SearchResponse` and `SortClause` (search).
- `AliasResult` (alias).
- Nine package-private records in `collection/Dtos.java`.

`CollectionSchema` and `FieldListing` carry `@JsonAnySetter`/`@JsonAnyGetter` `other` maps and serialize SolrJ's `FieldFlag` enum by name. `FieldFlags.fromSchemaProperties` keeps only `true` properties, so a declared `false` is lost.

## Goals / Non-Goals

**Goals:**
- Every MCP surface's Java signature is its contract. The generated `outputSchema` matches what the server actually sends, and a test proves that for every tool.
- Solr-to-contract mapping lives in one place per domain, reading SolrJ's typed accessors where they exist.

**Non-Goals:**
- `get-cluster-status` (#270, unmerged). It adopts these rules in its own PR.
- Changing any tool, prompt or resource name or URI, or adding new tools.
- A versioned or negotiated contract. 1.0.0 is unreleased, so the shapes change in place.

## Decisions

### D1. Turn on structured output per tool, behind a conformance test

Every `@McpTool` sets `generateOutputSchema = true`. A new integration test runs once per protocol (legacy, modern):
1. Lists the tools and asserts each has an object `outputSchema`.
2. Calls every tool against Testcontainers Solr with valid arguments.
3. Validates `structuredContent` against the published schema with all `additionalProperties` forced to `false`. Only the paths for `fields` and `parameters` are allowed open.
4. Asserts the first text block parses to the same JSON.

This test enforces the `mcp-contracts` spec: the published schema is only part of the guarantee, and the test is what covers the rest.

*Alternatives:*
- Return `CallToolResult` by hand: loses schema generation from the return type.
- A global post-processor that rewrites schemas: more code than an annotation per tool, and it hides the contract from the signature.

### D2. `@Nullable` marks the optional components

A record component that may be absent is `@Nullable`, and only those are. Spring AI's schema generator reads that through Spring's `Nullness`, so the component is left out of `required`. Records use `@JsonInclude(NON_NULL)`, so an absent value is omitted rather than sent as `null`. No extra annotation is needed. The D1 test confirms this for every tool: the SDK's validator rejects a result that omits a required property.

*Alternatives:*
- `@JsonProperty(required = false)` on each optional component: redundant with `@Nullable`, and it would also take precedence over it, so the two could drift apart.
- Send `null`s and type the properties as nullable unions: noisier JSON for LLM clients.

### D3. One record type per contract object; map from SolrJ's typed accessors

Each domain maps Solr responses into its records in the service or a static `from(...)` factory on the record. These record types are the contract objects:

- **search**: `SearchResponse(numFound, start, maxScore?, documents: List<SearchHit>, facets: List<FacetField>)` with nested `SearchHit(id, score?, fields)`, `FacetField(field, values)` and `FacetValue(value, count)`.
  - Built from `SolrDocumentList` and `FacetField.getValues()`.
  - `fields` is a `LinkedHashMap` copy in Solr's field order, with the uniqueKey and `score` removed.
- **indexing**: `IndexingResult(collection, format, documentsSubmitted?, documentsIndexed?, fieldNames?)`, with `format` an enum `JSON|CSV|XML|MARKDOWN` serialized lowercase.
  - The counts come from `indexDocuments`, which already returns the accepted count after its per-document retry.
- **schema**:
  - `CollectionSchema` with nested `Field`, `FieldType`, `Analyzer`, `AnalyzerComponent(name?, className?, parameters)` and `CopyField`.
  - `Field` and `FieldType` declare the spec's properties as `@Nullable Boolean` components, which keeps declared `false` and leaves undeclared properties absent. Remaining field-type attributes go into `parameters: Map<String, String>`. Remaining field attributes are dropped.
  - `FieldListing(List<ListedField>)` sorted by name, with `flags: Set<FieldProperty>`.
  - `FieldProperty` is a server-owned enum whose JSON values are the camelCase schema-attribute names (`indexed`, `docValues`, …). It replaces SolrJ's `FieldFlag` in the contract. `FieldFlags` keeps decoding Luke's flag letters into it.
- **collection**:
  - `CollectionList(collections)`.
  - `CollectionMetrics(index, query, caches?, handlers?)` with nested `IndexStats`, `QueryStats`, `CacheStats`, `CacheInfo` (`hitRatio`), `HandlerStats` and `HandlerInfo`.
  - `HealthStatus(collection, healthy, pingMillis?, numDocs?, reason?)`.
  - `CollectionCreationResult(name, configSet, numShards, replicationFactor)`.
- **alias**:
  - `AliasList(aliases)` and `Alias(name, collections, properties)`, built from `CollectionAdminResponse.getAliasesAsLists()` and `getAliasProperties()`, sorted by name.
  - `AliasDeletion(name)`.

Collection-typed components are copied with `List.copyOf`/`Map.copyOf` in compact constructors; `fields` and `parameters` maps use unmodifiable `LinkedHashMap` copies to keep order. A component is `@Nullable` only where the spec says it can be absent.

*Alternative:* reuse solr-api's `ListAliasesResponse`/`ListCollectionsResponse` and similar. Rejected: their fields are public and mutable, and they carry `responseHeader`/`error`. This is exactly the Solr shape the contract replaces.

### D4. `SearchHit.id` from the schema's uniqueKey, cached per collection

`SearchService` resolves the uniqueKey with `SchemaRequest.UniqueKey` and caches it per collection in a bounded cache with a short TTL. Because the uniqueKey of an existing collection cannot change, staleness only matters if a collection is deleted and recreated. When the client's `fields` leaves out the uniqueKey, the server adds it to `fl` and removes it from `fields`. Values are converted to strings with `String.valueOf`.

*Alternative:* assume `id`. Rejected because the spec covers collections whose uniqueKey is something else.

### D5. Health reasons are a fixed enum

`HealthStatus.reason` comes from a server enum (`COLLECTION_NOT_FOUND`, `PING_FAILED`, `SOLR_UNREACHABLE`, `QUERY_FAILED`), chosen from the exception type and Solr's HTTP code. The exception is logged at `warn` with its cause. This applies the rule #275 established for resources (no Solr text in responses) to a value that is caught and returned rather than thrown, so the redaction aspect never sees it.

### D6. Prompts, resources and completions return MCP's result types

- **Prompts** build `GetPromptResult(description, List.of(new PromptMessage(Role.USER, new TextContent(text))))`, keeping today's text from `PromptText` and the description from the annotation.
- **Resources** return `ReadResourceResult` with one `TextResourceContents(uri, "application/json", json)`. The JSON comes from the same `JsonMapper` and the same record as the matching tool, so the two outputs cannot drift.
- **Completions** return `CompleteResult(new CompleteCompletion(values, total, hasMore))`. The handler counts every match before applying `MAX_COMPLETION_RESULTS`.

### D7. Package layout

Each domain package holds its service and its contract records:

| Package | Holds |
|---|---|
| `alias/` | `AliasService`, `Alias`, `AliasList`, `AliasDeletion` |
| `collection/` | `CollectionService`, `CollectionList`, `CollectionMetrics`, `HealthStatus`, `CollectionCreationResult` |
| `indexing/` | `IndexingService`, `IndexingResult` |
| `schema/` | `SchemaService`, `CollectionSchema`, `FieldListing`, `FieldProperty`, `SchemaUpdateResult` |
| `search/` | `SearchService`, `SearchResponse`, `SortClause` |

- There is one top-level public record per file. Records that exist only as part of another are nested inside it.
- `collection/Dtos.java` and `collection/AliasResult.java` are deleted.
- `SolrNativeHints` lists the new top-level and nested records, because MCP's `Object`-typed dispatch hides them from AOT.

### D8. Delivery order

Each area is its own PR off `main`, ordered so the shared rules land first:
1. D1 + D2 test scaffolding, prompts/resources/completions (D6), plus `SolrNativeHints`.
2. Alias and collection (D3, D5, D7). This reworks #294.
3. Search (D3, D4).
4. Schema (D3).
5. Indexing (D3).
6. Turning on `generateOutputSchema` for every tool, once each area's records meet D2. The conformance test must be green on both protocols in this PR.

## Risks / Trade-offs

- **[Risk]** The modern protocol path may not add the text copy or validate as the legacy handler does. → The D1 test runs on both protocols in step 1, before any tool turns the flag on. If the modern path lacks the text copy, step 6 is held for modern and the gap is filed upstream with Spring AI / the java-sdk.
- **[Risk]** The SDK's output validation turns a contract slip into a failed call in production. → The D1 conformance test covers every tool in CI, including the D2 rule that optional components are `@Nullable`.
- **[Trade-off]** Dropping unknown Solr attributes hides settings a later Solr adds, for example a new field property. → The contract adds them on purpose. `parameters` keeps class-specific field-type settings, which are the most common case.
- **[Risk]** The uniqueKey lookup adds a Schema API request to the first search on each collection. → Cached per collection (D4). The request is a cheap admin call.
- **[Trade-off]** Every client-visible output changes at once before 1.0.0. → This is deliberate. Doing it after 1.0.0 would need a breaking release.

## Migration Plan

There are no deployed users before 1.0.0. Each PR updates the README tool tables and the docs-site tool pages for its area, and the 1.0.0 changelog lists the output changes under one "MCP contracts" heading. To roll back, revert the area's PR. The steps don't depend on each other except for step 6.

## Open Questions

- Does Solr's `DELETEALIAS` fail for an alias that does not exist? If it is a no-op, `delete-alias` checks `LISTALIASES` first, so the spec's "fails for an unknown alias" holds either way.
