---
name: add-mcp-tool
description: Add or change an MCP tool, prompt or resource in solr-mcp, with everything such a change has to touch - behaviour hints, @PreAuthorize, native-image hints for response records, the registration and client tests, the README tables, THREAT_MODEL.md, the client docs' tool counts and CHANGELOG.md. Use when asked to add a tool, expose a new Solr API, add a parameter to an existing tool, or add a prompt, resource or completion.
---

# Add or change an MCP tool

A tool is more than one annotated method. The list below is what `list-fields` (#288) and the
alias tools (#214) had to touch. Work through it in order. Skip a step only when it cannot
apply, and say so in the PR.

## 1. Put the method in the module that owns the Solr API

Tools live in five service classes: `search/SearchService`, `indexing/IndexingService`,
`collection/CollectionService`, `collection/AliasService` and `schema/SchemaService`. Add to the
one that owns the Solr API you call. A new top-level package is a new Spring Modulith module,
and `ModularityTests` will check its dependencies.

## 2. Annotate it the way the others are

```java
@PreAuthorize("isAuthenticated()")
@McpTool(
    name = "kebab-case-name",
    annotations = @McpTool.McpAnnotations(readOnlyHint = true),
    description = "What it does, and when to use it instead of the nearest other tool")
public SomeResult doIt(@McpToolParam(description = "...") String collection) { ... }
```

- **Name** is kebab-case and unique across all five classes.
- **Hints** say what the tool does to Solr. `readOnlyHint = true` for reads,
  `destructiveHint = true` for anything that removes something, `idempotentHint = true` for a
  create-or-update. ChatGPT treats a tool with no `readOnlyHint` as a write and asks the user
  every time.
- **`@PreAuthorize("isAuthenticated()")`** is required. HTTP mode is secured by default, and a
  tool without it is reachable without a token.
- **Description**: models choose between tools by it. When a new tool overlaps an old one (as
  `list-fields` overlaps `get-schema`), update both descriptions to say which one to use, and
  update `spring.ai.mcp.server.instructions` in `src/main/resources/application.properties`
  if the "discover before acting" advice changes.
- **Never add a tool that deletes documents, collections or fields** without raising it on the
  dev list first. The server instructions promise there is none, and so do `README.md`,
  `CHANGELOG.md` and `THREAT_MODEL.md`.
- **Validate arguments** before calling Solr (see `AliasService`'s blank-name check), and throw
  `IllegalArgumentException` with a message the model can act on.

## 3. Return a record, and register it for native image

Return a record, not a `Map` or a SolrJ response type. Then add every new record, including
nested ones (`Outer$Inner`) and any Jackson serializer named in an annotation, to the list in
`config/SolrNativeHints.java`. The MCP framework dispatches on `Object`, so Spring AOT cannot see
these types. A missing entry passes every JVM test and returns empty JSON in the native image.
Add the record to `SolrNativeHintsTest` as well, and to the "MCP tool response records" list in
`AGENTS.md`.

## 4. Tests

- **Unit test** next to the service (`*ServiceTest`, Mockito). Annotate it
  `@DisabledInNativeImage` like the others.
- **Integration test** against real Solr (`*IntegrationTest`, Testcontainers).
- **`McpToolRegistrationTest`**: the tool's annotation, hints and parameter annotations, and for
  a resource or completion the URI or prompt binding list.
- **`McpClientIntegrationTestBase`**: add the name to `listToolsReturnsExpectedTools`, and call
  the tool once end to end if it takes new argument types.
- If behaviour differs by Solr version, run `./gradlew test -Dsolr.test.image=solr:<v>-slim`
  for each version in the CI matrix (`.github/workflows/ci.yml`).

## 5. Documentation, in the same PR

- `README.md`: the Tools table (or the Resources/Prompts table, and the completions list).
- `THREAT_MODEL.md`: the tool count and list in the system description, and the read, write or
  admin row in the attack-surface table.
- `docs/clients/chatgpt.md`: "There should be N" tools.
- `docs/clients/mcp-inspector.md` and `docs/site/content/pages/mcp/quick-start.md`, if they name
  the resource templates or completions you changed.
- `CHANGELOG.md`: one line under `[Unreleased]` (`dev-docs/CHANGELOG_GUIDE.md`).
- `AGENTS.md`: the service-class list, if you added a class or package.

## 6. Prompts, resources and completions

Prompt names live in `util/PromptNames`, shared text in `util/PromptText`. A prompt or resource
that takes a `collection` argument needs an `@McpComplete` binding, and
`McpToolRegistrationTest` lists every binding, so update it.

## 7. Check

```bash
./gradlew spotlessApply build              # format, Error Prone/NullAway, RAT, all JVM tests
./gradlew nativeTest -Pnative              # only with GraalVM 25; proves the hints in step 3
```

If you cannot run the native test, say so in the PR. `native.yml` runs it when native-related
paths change, and `config/SolrNativeHints.java` is one of them.
