# Schema modification MCP tools — design

**Status:** draft, pending user approval
**Issue:** [apache/solr-mcp#30](https://github.com/apache/solr-mcp/issues/30) (partial — see Scope)
**Branch:** `schema-modification`

## Problem

`SchemaService` exposes one MCP tool today: `get-schema`, which is read-only. AI assistants
can inspect a Solr collection's schema but cannot extend it. The only way to add fields or
field types is to drop out of the AI workflow and POST JSON to Solr's Schema API by hand:

```bash
curl -X POST -H 'Content-Type:application/json' \
    http://localhost:8983/solr/shows/schema \
    -d '{"add-field": [{"name":"title","type":"text_general", ...}, ...]}'
```

This breaks the end-to-end "set up a collection, define its shape, index data" workflow
through an AI assistant.

The motivating use case: ask an AI to create a `shows` collection and define a schema with
fields like `title text_general`, `platform string` (for exact-match faceting), `release_year
pint`, `genres strings` (multi-valued), and so on — properties that Solr's schemaless mode
won't infer correctly.

### Why not just rely on schemaless mode / dynamic fields?

The `_default` configset has schemaless mode enabled and ships with dynamic-field patterns
(`*_s`, `*_i`, `*_txt`, ...). For casual exploration these cover a lot. They fail for the
motivating use case because:

- Schemaless guesses `text_general` for strings, but several shows fields need `string` for
  exact-match faceting (`platform`, `country`, `language`, `rating`).
- Schemaless never sets `docValues=true`. The shows spec wants `docValues=true` on 11 of 16
  fields for sorting/faceting/function-query efficiency.
- Schemaless infers multi-valued from the first doc's value shape — fragile under data drift.
- Schemaless cannot define `DenseVectorField` (vector search needs explicit `vectorDimension`,
  `similarityFunction`, `knnAlgorithm` — the secondary issue-#30 motivation).

Explicit `add-fields` / `add-field-types` is therefore necessary for any non-casual workflow.

## Scope

In scope:

- `add-fields` MCP tool — add one or more fields to an existing collection's schema.
- `add-field-types` MCP tool — add one or more field types (including custom analyzer chains
  and `DenseVectorField` for vector search) to an existing collection's schema.

Out of scope (issue #30 still partially open after merge):

- `replace-field` / `replace-field-type` — silently breaks existing indexed data without
  reindex. AI-driven workflows are the wrong place to expose that footgun without a
  guardrail design.
- `delete-field` / `delete-field-type` — same risk; orphan data; cascading effects on
  field types used by multiple fields.
- `add-copy-field` / `add-dynamic-field` — useful but not motivating; defer to follow-up.
- `add-codec-factory` (issue #30 third bullet) — uses Config API, not Schema API; different
  code path; different risk profile.

## Decisions (from brainstorming)

| Decision | Choice | Rationale |
|---|---|---|
| Operations | Add-only; no replace/delete | Replace/delete silently corrupt indexed data without explicit reindex. Add-only is safe; orphan fields/types are harmless. |
| Batching | Batch per call (list of definitions) | Matches Solr Schema API wire format; one round-trip. SolrJ's `SchemaRequest.MultiUpdate` is built for this. |
| Parameter shape | `List<Map<String, Object>>` | Maps 1:1 to Solr's JSON. Records can't cleanly express analyzer nesting + arbitrary per-factory params. Matches SolrJ's `AddField(Map)` constructor — zero transformation. |
| One tool vs two | Two separate tools | LLM tool-use guidance favors single-purpose tools. Two single-list-parameter tools eliminate cross-wire risk vs a combined tool with two optional lists. |
| Failure mode | Throw on any Solr error | Matches `createCollection`. Partial-failure case is rare in practice; when it does happen the LLM can call `get-schema` to inspect. Avoids inventing a `failures` shape for a rare case. |

## Architecture

Add the two tools as methods on the existing `SchemaService`
(`src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java`). Same package,
same constructor dependencies (`SolrClient`, `ObjectMapper`), same annotations as
`getSchema`. No new service class.

```
SchemaService
├── getSchema(String collection)               — existing
├── addFields(collection, fields)              — NEW
└── addFieldTypes(collection, fieldTypes)      — NEW
```

### Method signatures

Tool descriptions are deliberately long and include inline recipes (case-insensitive exact
match, dense vector, autocomplete). LLMs use these recipes as the diagnostic-to-fix bridge:
the user describes a symptom ("my filter doesn't match Netflix"), the LLM matches the
symptom to a recipe in the description, and the recipe gives the LLM the exact analyzer
chain to construct. Generic shape-only descriptions are not sufficient — a strong model
might still produce a working chain from training data, but inline recipes improve
reliability and reduce variance across model capabilities.

```java
@PreAuthorize("isAuthenticated()")
@McpTool(
    name = "add-fields",
    description = "Add one or more fields to a Solr collection schema. " +
        "Call get-schema first to inspect existing field configuration before adding. " +
        "Each field map follows the Solr Schema API add-field shape: required keys " +
        "'name' and 'type', plus optional 'stored', 'indexed', 'docValues', " +
        "'multiValued', 'required', 'omitNorms', etc. " +
        "Example: {\"name\":\"platform\",\"type\":\"string\",\"stored\":true,\"indexed\":true,\"docValues\":true}. " +
        "Use 'strings' (not 'string') for multi-valued string fields. " +
        "Note: this only adds new fields; existing fields cannot be modified. " +
        "Solr's Schema API is transactional — if any command in the batch fails, " +
        "none are applied. On failure, fix the invalid field(s) and retry the whole batch."
)
public SchemaUpdateResult addFields(
    @McpToolParam(description = "Solr collection name") String collection,
    @McpToolParam(description = "List of field definitions (Solr add-field JSON shape)")
        List<Map<String, Object>> fields
) throws SolrServerException, IOException;

@PreAuthorize("isAuthenticated()")
@McpTool(
    name = "add-field-types",
    description = "Add one or more field types to a Solr collection schema. " +
        "Call get-schema first to inspect existing field types before adding. " +
        "Each map follows the Solr Schema API add-field-type shape: required keys " +
        "'name' and 'class', optional 'analyzer' (or 'indexAnalyzer'+'queryAnalyzer'), " +
        "and class-specific attributes. " +
        "Common recipes: " +
        "(1) case-insensitive exact match: class=solr.TextField with analyzer " +
        "{tokenizer:{class:solr.KeywordTokenizerFactory}, filters:[{class:solr.LowerCaseFilterFactory}]}; " +
        "(2) dense vector for semantic search: class=solr.DenseVectorField with " +
        "vectorDimension, similarityFunction (cosine/dot_product/euclidean), and knnAlgorithm=hnsw; " +
        "(3) autocomplete: class=solr.TextField with separate indexAnalyzer using EdgeNGramFilterFactory " +
        "and queryAnalyzer without it. " +
        "After adding a type, use add-fields to create fields of that type. " +
        "Solr's Schema API is transactional — if any command in the batch fails, none are applied."
)
public SchemaUpdateResult addFieldTypes(
    @McpToolParam(description = "Solr collection name") String collection,
    @McpToolParam(description = "List of field type definitions (Solr add-field-type JSON shape)")
        List<Map<String, Object>> fieldTypes
) throws SolrServerException, IOException;
```

### Result type

New record `SchemaUpdateResult` in a new file
`src/main/java/org/apache/solr/mcp/server/schema/SchemaUpdateResult.java`:

```java
public record SchemaUpdateResult(String collection, List<String> addedNames) {}
```

Failures throw and never produce this result, so no `success` flag is needed.
`addedNames` echoes the `name` from each input definition in input order so the
caller can confirm what landed. No `timestamp` — sub-second operation; the MCP
host records call timing already.

### Implementation skeleton

```java
public SchemaUpdateResult addFields(String collection, List<Map<String, Object>> fields)
        throws SolrServerException, IOException {
    if (collection == null || collection.isBlank()) {
        throw new IllegalArgumentException("Collection name must not be blank");
    }
    if (fields == null || fields.isEmpty()) {
        throw new IllegalArgumentException("fields must not be empty");
    }
    List<String> names = new ArrayList<>(fields.size());
    List<SchemaRequest.Update> updates = new ArrayList<>(fields.size());
    for (Map<String, Object> field : fields) {
        names.add(String.valueOf(field.get("name")));
        updates.add(new SchemaRequest.AddField(field));
    }
    new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
    return new SchemaUpdateResult(collection, true, names, new Date());
}
```

`addFieldTypes` is the same shape but each map needs conversion to `FieldTypeDefinition`
(see below).

### `FieldTypeDefinition` conversion helper

`FieldTypeDefinition` (SolrJ) doesn't accept `name`/`class` as top-level setters — those go
into the attributes map. So a flat input map doesn't deserialize directly via Jackson. A
small private helper builds it manually:

```java
private FieldTypeDefinition toFieldTypeDefinition(Map<String, Object> input) {
    FieldTypeDefinition def = new FieldTypeDefinition();
    Map<String, Object> attributes = new LinkedHashMap<>(input);
    Object analyzer = attributes.remove("analyzer");
    Object indexAnalyzer = attributes.remove("indexAnalyzer");
    Object queryAnalyzer = attributes.remove("queryAnalyzer");
    def.setAttributes(attributes);
    if (analyzer != null)      def.setAnalyzer(toAnalyzerDefinition(analyzer));
    if (indexAnalyzer != null) def.setIndexAnalyzer(toAnalyzerDefinition(indexAnalyzer));
    if (queryAnalyzer != null) def.setQueryAnalyzer(toAnalyzerDefinition(queryAnalyzer));
    return def;
}

private AnalyzerDefinition toAnalyzerDefinition(Object raw) {
    return objectMapper.convertValue(raw, AnalyzerDefinition.class);
}
```

`AnalyzerDefinition` (with its nested `charFilters`, `tokenizer`, `filters` lists of
`{class, params...}` maps) is structurally amenable to Jackson `convertValue`. Verify by
integration test.

### Validation

Minimal — match `createCollection` style:

- `collection` not null, not blank → `IllegalArgumentException`
- `fields` / `fieldTypes` not null, not empty → `IllegalArgumentException`

No per-map key validation. Solr returns clear errors for missing/invalid keys; pre-validating
duplicates that work and adds maintenance.

### Failure mode

- Bad input (blank collection, empty list) → `IllegalArgumentException` (Spring AI MCP
  converts to tool error)
- Solr transport failure → `SolrServerException` / `IOException` propagate
- Solr-side command failure → `SolrServerException` propagates from
  `MultiUpdate.process()` (verify exact behavior in integration test — Solr returns errors
  in the response body; SolrJ may or may not throw automatically. If it doesn't, inspect
  `response.getResponse().get("errors")` and throw explicitly. **This API shape needs
  integration-test verification before relying on it.**)

`MultiUpdate` is **transactional** — per Solr's Schema API reference guide and SolrJ's
`SchemaRequest.MultiUpdate` Javadoc, all commands in a single call either succeed or
fail together. Solr returns HTTP 400 with an `errors` array on failure and rolls back
any partially-applied state. SolrJ then throws (verified by the
`addFields_duplicateField_throws` integration test, which passes without needing manual
response-body inspection).

### Native image hints

Add to `src/main/java/org/apache/solr/mcp/server/config/SolrNativeHints.java`:

- `SchemaUpdateResult` — invisible to AOT (MCP dispatches via `Object`), same pattern as
  `CollectionCreationResult`

For SolrJ types (`SchemaRequest.AddField`, `AddFieldType`, `MultiUpdate`,
`FieldTypeDefinition`, `AnalyzerDefinition`): verify by running
`./gradlew nativeTest -Pnative` after the implementation pass. Add reflection registrations
only if tests fail.

Resource hints: none new.

## Testing

### `SchemaServiceTest` (unit, Mockito, `@DisabledInNativeImage`)

New file at `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceTest.java`
(or extend if exists). Cases:

- `addFields_blankCollection_throws()` — null and blank collection
- `addFields_emptyList_throws()` — null and empty list
- `addFields_happyPath_buildsMultiUpdate()` — capture `SolrRequest` argument to
  `solrClient.request(...)`, assert it is a `MultiUpdate` carrying the expected `AddField`s
  in input order
- `addFields_solrThrows_propagates()` — mock SolrClient to throw `SolrServerException`,
  assert it surfaces unchanged
- Same four cases for `addFieldTypes`, plus:
  - `addFieldTypes_withAnalyzer_buildsFieldTypeDefinition()` — input has nested analyzer,
    assert the `FieldTypeDefinition` passed to `AddFieldType` has its analyzer set with the
    expected tokenizer/filters

### `SchemaServiceIntegrationTest` (Testcontainers, real Solr)

New file `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceIntegrationTest.java`.
Pattern follows existing `*IntegrationTest` classes (real `SolrContainer`, real `SolrClient`).

- `addFields_endToEnd_persistsToSchema()` — create collection via `CollectionService`, call
  `addFields` with 3 fields covering `string`/`text_general`/`pint`, then call `getSchema`
  and assert all 3 appear with the right types and properties (including `docValues=true`
  where set)
- `addFieldTypes_endToEnd_persistsToSchema()` — add a custom field type with an analyzer
  (e.g., `text_lowercase` with `KeywordTokenizerFactory` + `LowerCaseFilterFactory`), then
  add a field using that type, then index a doc and assert the lowercase analyzer was
  applied (query for the field with mixed-case input matches)
- `addFields_duplicateField_throws()` — add a field, then call again with the same name;
  assert exception (Solr returns "Field 'X' already exists")
- `addFields_unknownType_throws()` — try to add a field with `type: "nonexistent_type"`;
  assert exception

The duplicate-field and unknown-type tests also serve to **verify the response error
shape** assumption noted under Failure mode.

### `McpClientIntegrationTestBase`

Append ordered tests to the existing `mcp-client-test` collection (reuse — by test 16 the
prior assertions are done, and adding fields doesn't disturb them):

```java
@Test @Order(16)
void addFieldsToTestCollection() {
    // call add-fields with a subset of the shows-style schema:
    //   {name: "platform", type: "string", stored: true, indexed: true, docValues: true}
    //   {name: "release_year", type: "pint", stored: true, indexed: true, docValues: true}
    //   {name: "genres", type: "strings", stored: true, indexed: true, docValues: true}
    // assert result has success=true and addedNames in expected order
}

@Test @Order(17)
void indexDocumentWithNewFields() {
    // index-json-documents with one doc using the new fields:
    //   {id: "show-1", title: "Breaking Bad", platform: "Netflix",
    //    release_year: 2008, genres: ["drama","crime"]}
    // assert no error
}

@Test @Order(18)
void searchWithNewFieldFilter() {
    // search with filterQueries=["platform:Netflix"]
    // assert numFound=1 and the returned doc has title="Breaking Bad"
}
```

Skip `add-field-types` in `McpClientIntegrationTestBase` — covered in
`SchemaServiceIntegrationTest`. Keeps the MCP-protocol-level test focused on the user's
motivating workflow.

### Docker / native test coverage

No changes to `dockerIntegrationTest` or `nativeTest` configuration. The new methods are
exercised by the existing test runs:

- JVM unit + integration: `./gradlew build`
- Native: `./gradlew nativeTest -Pnative` (unit Mockito tests stay `@DisabledInNativeImage`)
- Docker MCP protocol: `./gradlew dockerIntegrationTest` (runs `McpClientIntegrationTestBase`
  subclasses against the Jib image, including the new ordered tests)

## Docs

- **`README.md`** — append rows for `add-fields` and `add-field-types` to the existing MCP
  tools list, one line each, matching the style of `get-schema`.
- **`CLAUDE.md`** — under "MCP Tools" → SchemaService entry, change from "Schema
  introspection" to "Schema introspection and additive modification". One sentence.
- **No new doc files.**

## Git workflow

```bash
# Sync local main with upstream main (will confirm with user before push)
git checkout main
git fetch upstream
git reset --hard upstream/main
git push origin main

# Branch off updated main
git checkout -b schema-modification
```

**Spec file handling.** This spec is written on the `docs-restructure` branch but the
implementation work happens on `schema-modification` (off main). To avoid cherry-picking:

1. Do not commit the spec on `docs-restructure`.
2. After `git checkout -b schema-modification`, the unstaged spec file in
   `docs/superpowers/specs/` carries over to the new branch automatically.
3. First commit on `schema-modification` includes the spec.

Untracked `.DS_Store` and the rest of `docs/superpowers/` stay alone.

## Commit conventions

Per project + user CLAUDE.md:

- Conventional Commits: `feat(schema): add add-fields and add-field-types MCP tools`
- `Signed-off-by:` in every commit (user's global instruction; `git commit -s`)
- `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`

## Open questions resolved during brainstorming

- **Why not include modification ops (replace/delete)?** Replace/delete silently break
  indexed data without reindex. AI-driven workflows are exactly the wrong place to expose
  that footgun. Defer until we design a guardrail (e.g. mandatory
  `acknowledgeReindexRequired: true`).
- **Why not include codec factory?** Different API (Config API vs Schema API), different
  risk profile (wrong codec choice can break an index), and the motivating use case
  doesn't need it.
- **Why two tools instead of one combined?** LLM tool-use guidance favors single-purpose
  tools. Combined tool's two-optional-list shape risks LLMs cross-wiring field defs and
  type defs. Orphan-field-type cost of separation is harmless.
- **Why `List<Map<String, Object>>` instead of strongly-typed records?** Solr field-type
  shape includes analyzers/tokenizers/filters with class-specific param bags; records
  collapse to `Map<String, Object>` at the leaves anyway. Map shape matches SolrJ's
  `AddField(Map)` constructor — zero transformation.
- **Why not skip this and rely on schemaless mode?** Schemaless gets `string` vs
  `text_general` wrong for the motivating use case, never sets `docValues`, infers
  multi-valued fragilely from doc 1, and cannot define vector fields at all.
- **Why throw on failure instead of returning a partial-result type?** Most failures fail
  fast at command #1 (already-exists, unknown-type). Mid-batch partial failure is rare;
  modeling it with a `List<SchemaUpdateError>` adds a type for an edge case. Caller can
  call `get-schema` after a failure to see what landed.
- **Why no per-key map validation?** Solr returns clear errors for missing/invalid keys.
  Pre-validating duplicates Solr's work and adds maintenance burden. Matches
  `createCollection` style (only validates collection name).
