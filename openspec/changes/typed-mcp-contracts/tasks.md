# Tasks

Each numbered group is one PR off `main` (design D8). Merge order is 2, 4, 1, then 3, 5, 6: group 1 turns on `generateOutputSchema` for every tool that returns a non-`String`, and the SDK fails any call whose result does not match its schema, so the alias, collection and schema records are reshaped first. Every group ends green on `./gradlew spotlessApply build` and `./gradlew nativeTest -Pnative`, with 0 failures and 0 skipped tests beyond the `@DisabledInNativeImage` Mockito ones.

## 1. Contract test scaffolding and MCP result types

- [ ] 1.1 Add `McpContractConformanceIntegrationTest`, run for the legacy and the modern protocol. For every tool flagged with `generateOutputSchema = true`, it:
  - asserts an object `outputSchema` is published;
  - calls the tool against Testcontainers Solr;
  - validates `structuredContent` against the schema with `additionalProperties` forced to `false`, except the `fields` and `parameters` paths;
  - asserts the first text block parses to the same JSON.

  Verify: the test runs on both protocols (it starts with zero flagged tools).
- [x] 1.2 Flag `check-health` alone with `generateOutputSchema = true` as a probe (`list-collections` still returns a bare array until group 2, and an `outputSchema` must be an object). Record in the PR description whether the modern path adds the text copy and validates like the legacy handler. Verify: the 1.1 test passes on legacy. Its result on modern decides whether step 6 is held for modern (design Risks).
- [x] 1.3 Return `GetPromptResult` from all six prompts: the annotation's description, plus one `USER` message with today's `PromptText` text. Verify: the prompt unit tests and the `prompts/get` cases in `McpClientIntegrationTestBase` assert the description, role and text.
- [x] 1.4 Return `ReadResourceResult` with one `application/json` `TextResourceContents` from `solr://collections`, `solr://{collection}/schema` and `solr://{collection}/fields`, keeping the fixed error messages. Verify: the resource tests assert the `uri`, the `mimeType`, and text equal to the matching tool's JSON.
- [x] 1.5 Return `CompleteResult` with `total` and `hasMore` from all six completion handlers, counting every match before the 100 cap. Verify: unit tests cover 0, 99, 100 and 150 matching collections.
- [x] 1.6 Document the contract rules from the `mcp-contracts` spec in `AGENTS.md`, under an "MCP contracts" section beside "Architecture". Verify: the section names each rule the spec states.
- [ ] 1.7 Set `generateOutputSchema = true` on every tool whose method returns a non-`String`, enforced by `McpToolRegistrationTest`, with sample arguments for each in the conformance test. Verify: the conformance tests pass for every flagged tool on both protocols once groups 2 and 4 are merged.

## 2. Alias and collection contracts (reworks #294)

- [x] 2.1 Move `AliasService` to `alias/`. Add `Alias`, `AliasList` and `AliasDeletion`, built from `getAliasesAsLists()` and `getAliasProperties()` and sorted by name. Delete `AliasResult`. Verify: `AliasServiceTest` covers two targets plus a property, and the no-aliases case.
- [x] 2.2 Change the `create-alias` input `collections` to `List<String>` (non-empty), and return the alias read back from `LISTALIASES`. Verify: the `alias-management` "Re-pointing an alias that has properties" scenario passes in `AliasServiceIntegrationTest`.
- [x] 2.3 Check whether Solr's `DELETEALIAS` fails for an unknown alias on 9.4 and 10. If it is a no-op, look the alias up first and throw. Verify: an integration test deleting `nope` gets `isError: true` on both versions (`-Dsolr.test.image`).
- [x] 2.4 Add `CollectionList`, and return it from `list-collections` and the collections resource. Verify: `CollectionServiceTest` and `McpClientIntegrationTestBase.listCollectionsContainsCreatedCollection` parse `{"collections": [...]}`.
- [x] 2.5 Replace `Dtos.java` with `CollectionMetrics` (nested `IndexStats`, `QueryStats`, `CacheStats`, `CacheInfo` with `hitRatio`, `HandlerStats`, `HandlerInfo`; no timestamp) and `HealthStatus`, whose `reason` is the server enum from design D5. Verify: tests cover the Solr 9 and Solr 10 (no `caches`/`handlers`) stats, and an unhealthy check whose `reason` holds no exception text.
- [x] 2.6 Reduce `CollectionCreationResult` to `name`, `configSet`, `numShards` and `replicationFactor`, reporting the applied defaults. Verify: the "Defaults applied" scenario passes in `CollectionServiceIntegrationTest`.
- [x] 2.7 Confirm every optional component is `@Nullable` (design D2), and update `SolrNativeHints` and `SolrNativeHintsTest`. Verify: `nativeTest -Pnative` is green, and the group-1 conformance test passes for the seven alias and collection tools when group 1 is rebased onto this group.
- [x] 2.8 Update the README tool table and the docs-site collection and alias tool pages with the new outputs and the `create-alias` input. Verify: `./gradlew build` (RAT and site checks) is green.

## 3. Search contract

- [ ] 3.1 Add the uniqueKey lookup with a per-collection cache (design D4). Add the uniqueKey to `fl` when the client's `fields` leaves it out. Verify: a unit test shows one Schema API call across repeated searches on the same collection.
- [ ] 3.2 Replace `SearchResponse.documents` with `List<SearchHit(id, score?, fields)>` and `facets` with `List<FacetField(field, List<FacetValue(value, count)>)>`. Verify: `SearchServiceTest` and `SearchServiceIntegrationTest` cover a non-`id` uniqueKey, a request with `score`, a `fields` list without the uniqueKey, facet order, and no facets.
- [ ] 3.3 Confirm every optional component is `@Nullable` (design D2), update `SolrNativeHints`, and update the README and docs-site search pages and the `search-collection` prompt text where it describes the output. Verify: the 1.1 test passes for `search`, and `nativeTest -Pnative` is green.

## 4. Schema contract

- [ ] 4.1 Add the server-owned `FieldProperty` enum with camelCase JSON values. Make `FieldFlags` decode Luke letters into it. Verify: `FieldFlagsTest` covers every Luke letter Solr 9.4 and 10 emit.
- [ ] 4.2 Rework `CollectionSchema`:
  - `Field`/`FieldType` declare the spec's properties as nullable booleans;
  - field types get `parameters: Map<String, String>`;
  - `Analyzer` holds `AnalyzerComponent(name?, className?, parameters)`;
  - all `other` maps are removed.

  Verify: tests cover an explicit `stored: false`, a dropped unknown field attribute, `positionIncrementGap` in `parameters`, and the `standard`/`lowercase` chain.
- [ ] 4.3 Rework `FieldListing` to a name-sorted `List<ListedField>` with `flags: Set<FieldProperty>`, and no `other` map. Verify: `list-fields` tests cover a dynamic-field-derived field, and the absence of Luke letters.
- [ ] 4.4 Change `add-fields` to take `List<CollectionSchema.Field>` and `add-field-types` to take `List<CollectionSchema.FieldType>`, mapping to SolrJ's `SchemaRequest` builders. Unknown properties are rejected by input validation. Verify: integration tests cover adding `year`/`pint`/`docValues`, the unknown-property `isError`, and a cross-collection round trip of a field and of a text field type.
- [ ] 4.5 Confirm every optional component is `@Nullable` (design D2), update `SolrNativeHints`, and update the README, the docs-site schema pages and the `view-schema`/`design-schema` prompt text that shows field JSON. Verify: `nativeTest -Pnative` is green, and the group-1 conformance test passes for the four schema tools when group 1 is rebased onto this group.

## 5. Indexing contract

- [ ] 5.1 Add `IndexingResult(collection, format, documentsSubmitted?, documentsIndexed?, fieldNames?)`. Return it from all four indexing tools, with counts and field names only for JSON and Markdown. Verify: `IndexingServiceTest` covers all-accepted, partially-rejected (5 submitted, 3 indexed), Markdown sections, and CSV/XML without counts.
- [ ] 5.2 Confirm every optional component is `@Nullable` (design D2), set `generateOutputSchema = true` on the four indexing tools, update `SolrNativeHints`, and update the README, the docs-site indexing pages and the `index-data` prompt text that quotes the old sentence. Verify: the 1.1 test passes for these tools, and `nativeTest -Pnative` is green.

## 6. Whole-surface checks

- [ ] 6.1 Make the 1.1 test assert that every registered tool sets `generateOutputSchema = true`, so a new tool without a schema fails the build. Verify: removing the flag from one tool locally makes the test fail.
- [ ] 6.2 Run the full compatibility matrix (`-Dsolr.test.image` = 9.4, 9.9, 9.10, 10) and `./gradlew dockerIntegrationTest` for the Jib, native-stdio and native-http images. Verify: all green; record the results in the PR.
- [ ] 6.3 Add an "MCP contracts" entry to the 1.0.0 changelog listing every changed output and input. Verify: `./gradlew getChangelog --project-version=1.0.0` shows it.

## Workflow follow-up

- Archive the change with `openspec archive typed-mcp-contracts` after group 6 merges, so the six capabilities become `openspec/specs/`.
- #270 (`get-cluster-status`) adopts these rules before it merges.
