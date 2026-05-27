# Schema Modification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two MCP tools — `add-fields` and `add-field-types` — to the Solr MCP server so AI assistants can extend a collection's schema through the MCP protocol, partially closing [apache/solr-mcp#30](https://github.com/apache/solr-mcp/issues/30).

**Architecture:** Two new methods on the existing `SchemaService` use SolrJ's `SchemaRequest.MultiUpdate` to batch additive schema changes. Inputs are `List<Map<String, Object>>` matching Solr's Schema API JSON shape (no transformation layer). `addFieldTypes` includes a small manual conversion from flat input maps to SolrJ's typed `FieldTypeDefinition` (since SolrJ splits `name`/`class` into an attributes map and pulls analyzers into typed sub-objects).

**Tech Stack:** Java 25, Spring Boot 3.5, Spring AI MCP 1.1.4, SolrJ 10.0, JUnit 5.12, Mockito, Testcontainers, Gradle.

**Reference spec:** [`docs/superpowers/specs/2026-05-17-schema-modification-design.md`](../specs/2026-05-17-schema-modification-design.md)

---

## File Structure

**Create:**
- `src/main/java/org/apache/solr/mcp/server/schema/SchemaUpdateResult.java` — MCP tool response record

**Modify:**
- `src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java` — add 2 `@McpTool` methods + 2 private helpers
- `src/main/java/org/apache/solr/mcp/server/config/SolrNativeHints.java` — register `SchemaUpdateResult` for reflection
- `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceTest.java` — extend with unit tests for new methods
- `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceIntegrationTest.java` — extend with integration tests for new methods
- `src/test/java/org/apache/solr/mcp/server/McpClientIntegrationTestBase.java` — append ordered tests 16–18 exercising new MCP tools
- `README.md` — document the two new MCP tools
- `CLAUDE.md` — update SchemaService description

**Carries over from `docs-restructure` branch:**
- `docs/superpowers/specs/2026-05-17-schema-modification-design.md` — design spec
- `docs/superpowers/plans/2026-05-17-schema-modification.md` — this plan

---

## Task 1: Branch setup and initial spec/plan commit

**Files:**
- Sync: local `main` with `upstream/main`
- Create branch: `schema-modification` off updated `main`
- Carry over (unstaged): `docs/superpowers/specs/2026-05-17-schema-modification-design.md`, `docs/superpowers/plans/2026-05-17-schema-modification.md`

- [ ] **Step 1: Confirm with user before touching origin**

The next step does `git push origin main` after a `git reset --hard upstream/main` on local `main`. That's a remote-affecting operation. Ask the user to confirm before proceeding.

- [ ] **Step 2: Verify spec and plan files are present and unstaged on current branch**

```bash
git status -- docs/superpowers/specs/2026-05-17-schema-modification-design.md docs/superpowers/plans/2026-05-17-schema-modification.md
```

Expected: both files appear as `??` (untracked). They must NOT be committed on `docs-restructure`. If they are committed there, stop and resolve before continuing.

- [ ] **Step 3: Sync local main with upstream main**

```bash
git fetch upstream
git checkout main
git reset --hard upstream/main
git push origin main
```

Expected: `origin/main` is now identical to `upstream/main`. Working tree still contains the untracked spec + plan files (they survive branch switches because they're untracked).

- [ ] **Step 4: Create the feature branch**

```bash
git checkout -b schema-modification
```

Expected: on branch `schema-modification`, untracked files still present.

- [ ] **Step 5: Commit spec and plan**

```bash
git add docs/superpowers/specs/2026-05-17-schema-modification-design.md \
        docs/superpowers/plans/2026-05-17-schema-modification.md
git commit -s -m "$(cat <<'EOF'
docs: add design spec and implementation plan for schema modification

Spec and plan for adding add-fields and add-field-types MCP tools per
issue #30. See docs/superpowers/specs/ and docs/superpowers/plans/.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds. `git log -1 --stat` shows both files added.

---

## Task 2: Create `SchemaUpdateResult` record

**Files:**
- Create: `src/main/java/org/apache/solr/mcp/server/schema/SchemaUpdateResult.java`

No tests for the record itself (Java records are trivial); it'll be exercised by every test in subsequent tasks.

- [ ] **Step 1: Create the record file**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.schema;

import java.util.Date;
import java.util.List;

/**
 * Result of an additive schema update (add-fields or add-field-types).
 *
 * <p>{@code success} is always {@code true} on return — failures throw and never produce a
 * result. {@code addedNames} echoes the {@code name} field from each input definition in
 * input order, useful for confirming what was sent.
 */
public record SchemaUpdateResult(String collection, boolean success, List<String> addedNames, Date timestamp) {
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/apache/solr/mcp/server/schema/SchemaUpdateResult.java
git commit -s -m "$(cat <<'EOF'
feat(schema): add SchemaUpdateResult record for schema modification tools

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Implement `addFields` (TDD)

**Files:**
- Modify: `src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java`
- Modify: `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceTest.java`

Existing imports in `SchemaServiceTest` already include `SchemaRequest` and the mock infrastructure. Use the same `MockitoExtension` setup.

- [ ] **Step 1: Add the failing tests to `SchemaServiceTest`**

Append after the existing tests (before the closing brace). Add the necessary imports at the top: `org.apache.solr.client.solrj.SolrRequest`, `org.apache.solr.client.solrj.request.schema.SchemaRequest.AddField`, `org.apache.solr.client.solrj.request.schema.SchemaRequest.MultiUpdate`, `org.apache.solr.client.solrj.request.schema.SchemaRequest.Update`, `org.apache.solr.common.util.NamedList`, `org.mockito.ArgumentCaptor`, `java.util.List`, `java.util.Map`.

```java
@Test
void addFields_blankCollection_throws() {
    assertThrows(IllegalArgumentException.class,
            () -> schemaService.addFields(null, List.of(Map.of("name", "x", "type", "string"))));
    assertThrows(IllegalArgumentException.class,
            () -> schemaService.addFields("", List.of(Map.of("name", "x", "type", "string"))));
    assertThrows(IllegalArgumentException.class,
            () -> schemaService.addFields("   ", List.of(Map.of("name", "x", "type", "string"))));
}

@Test
void addFields_emptyList_throws() {
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("col", null));
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFields("col", List.of()));
}

@Test
void addFields_happyPath_buildsMultiUpdateAndEchoesNames() throws Exception {
    List<Map<String, Object>> fields = List.of(
            Map.of("name", "title", "type", "text_general", "stored", true, "indexed", true),
            Map.of("name", "platform", "type", "string", "stored", true, "indexed", true, "docValues", true));

    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = schemaService.addFields("col", fields);

    assertTrue(result.success());
    assertEquals(List.of("title", "platform"), result.addedNames());
    assertEquals("col", result.collection());
    assertNotNull(result.timestamp());

    ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
    verify(solrClient).request(captor.capture(), eq("col"));
    assertInstanceOf(MultiUpdate.class, captor.getValue());
}

@Test
void addFields_solrThrows_propagates() throws Exception {
    when(solrClient.request(any(SolrRequest.class), eq("col")))
            .thenThrow(new SolrServerException("simulated"));

    assertThrows(SolrServerException.class,
            () -> schemaService.addFields("col", List.of(Map.of("name", "x", "type", "string"))));
}
```

Also add `import static org.mockito.Mockito.verify;` to the existing static imports.

- [ ] **Step 2: Run tests, expect failure**

```bash
./gradlew test --tests SchemaServiceTest -i
```

Expected: 4 new tests FAIL with compilation errors (no `addFields` method on `SchemaService`).

- [ ] **Step 3: Implement `addFields` in `SchemaService.java`**

Add the imports (top of file): `java.util.ArrayList`, `java.util.Date`, `java.util.List`, `java.util.Map`, `org.apache.solr.client.solrj.request.schema.SchemaRequest.Update`.

Add the method at the end of the class (before the closing brace). Also add a private validation helper.

```java
@PreAuthorize("isAuthenticated()")
@McpTool(name = "add-fields", description = "Add one or more fields to a Solr collection schema. "
        + "Call get-schema first to inspect existing field configuration before adding. "
        + "Each field map follows the Solr Schema API add-field shape: required keys "
        + "'name' and 'type', plus optional 'stored', 'indexed', 'docValues', "
        + "'multiValued', 'required', 'omitNorms', etc. "
        + "Example: {\"name\":\"platform\",\"type\":\"string\",\"stored\":true,\"indexed\":true,\"docValues\":true}. "
        + "Use 'strings' (not 'string') for multi-valued string fields. "
        + "Note: this only adds new fields; existing fields cannot be modified. "
        + "Commands run in input order; if one fails mid-batch, prior commands remain applied "
        + "(use get-schema to inspect on failure).")
public SchemaUpdateResult addFields(
        @McpToolParam(description = "Solr collection name") String collection,
        @McpToolParam(description = "List of field definitions (Solr add-field JSON shape)")
                List<Map<String, Object>> fields)
        throws SolrServerException, IOException {
    requireCollection(collection);
    requireNonEmpty(fields, "fields");

    List<String> names = new ArrayList<>(fields.size());
    List<SchemaRequest.Update> updates = new ArrayList<>(fields.size());
    for (Map<String, Object> field : fields) {
        names.add(String.valueOf(field.get("name")));
        updates.add(new SchemaRequest.AddField(field));
    }

    new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
    return new SchemaUpdateResult(collection, true, names, new Date());
}

private static void requireCollection(String collection) {
    if (collection == null || collection.isBlank()) {
        throw new IllegalArgumentException("Collection name must not be blank");
    }
}

private static void requireNonEmpty(List<?> list, String name) {
    if (list == null || list.isEmpty()) {
        throw new IllegalArgumentException(name + " must not be empty");
    }
}
```

Add to existing imports: `org.springaicommunity.mcp.annotation.McpToolParam`, `java.io.IOException`, `org.apache.solr.client.solrj.SolrServerException`, `org.apache.solr.client.solrj.request.schema.SchemaRequest`.

- [ ] **Step 4: Run tests, expect pass**

```bash
./gradlew test --tests SchemaServiceTest -i
```

Expected: all SchemaServiceTest tests PASS, including the 4 new ones.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./gradlew spotlessApply
git add src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java \
        src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceTest.java
git commit -s -m "$(cat <<'EOF'
feat(schema): add add-fields MCP tool for additive schema modification

Closes part of #30. Adds one or more fields atomically per call via
SolrJ's SchemaRequest.MultiUpdate. Input is List<Map<String, Object>>
matching the Solr Schema API add-field JSON shape; validation is
limited to collection name and non-empty list (Solr returns clear
errors for malformed field defs).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Implement `addFieldTypes` + `toFieldTypeDefinition` (TDD)

**Files:**
- Modify: `src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java`
- Modify: `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceTest.java`

- [ ] **Step 1: Add the failing tests to `SchemaServiceTest`**

Add the import: `org.apache.solr.client.solrj.request.schema.AnalyzerDefinition`, `org.apache.solr.client.solrj.request.schema.FieldTypeDefinition`, `org.apache.solr.client.solrj.request.schema.SchemaRequest.AddFieldType`.

Append after the addFields tests:

```java
@Test
void addFieldTypes_blankCollection_throws() {
    assertThrows(IllegalArgumentException.class,
            () -> schemaService.addFieldTypes(null,
                    List.of(Map.of("name", "x", "class", "solr.StrField"))));
    assertThrows(IllegalArgumentException.class,
            () -> schemaService.addFieldTypes("",
                    List.of(Map.of("name", "x", "class", "solr.StrField"))));
}

@Test
void addFieldTypes_emptyList_throws() {
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFieldTypes("col", null));
    assertThrows(IllegalArgumentException.class, () -> schemaService.addFieldTypes("col", List.of()));
}

@Test
void addFieldTypes_happyPathWithAnalyzer_buildsCorrectFieldTypeDefinition() throws Exception {
    // Use a real ObjectMapper so convertValue actually works; rebuild service with it.
    ObjectMapper realMapper = new ObjectMapper();
    SchemaService service = new SchemaService(solrClient, realMapper);

    List<Map<String, Object>> types = List.of(Map.of(
            "name", "text_lowercase",
            "class", "solr.TextField",
            "analyzer", Map.of(
                    "tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"),
                    "filters", List.of(Map.of("class", "solr.LowerCaseFilterFactory")))));

    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = service.addFieldTypes("col", types);

    assertTrue(result.success());
    assertEquals(List.of("text_lowercase"), result.addedNames());

    ArgumentCaptor<SolrRequest> captor = ArgumentCaptor.forClass(SolrRequest.class);
    verify(solrClient).request(captor.capture(), eq("col"));
    assertInstanceOf(MultiUpdate.class, captor.getValue());
}

@Test
void addFieldTypes_separateAnalyzers_buildsCorrectFieldTypeDefinition() throws Exception {
    ObjectMapper realMapper = new ObjectMapper();
    SchemaService service = new SchemaService(solrClient, realMapper);

    List<Map<String, Object>> types = List.of(Map.of(
            "name", "text_autocomplete",
            "class", "solr.TextField",
            "indexAnalyzer", Map.of(
                    "tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"),
                    "filters", List.of(
                            Map.of("class", "solr.LowerCaseFilterFactory"),
                            Map.of("class", "solr.EdgeNGramFilterFactory", "minGramSize", 2, "maxGramSize", 20))),
            "queryAnalyzer", Map.of(
                    "tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"),
                    "filters", List.of(Map.of("class", "solr.LowerCaseFilterFactory")))));

    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = service.addFieldTypes("col", types);

    assertTrue(result.success());
    assertEquals(List.of("text_autocomplete"), result.addedNames());
}

@Test
void addFieldTypes_denseVectorField_noAnalyzer() throws Exception {
    ObjectMapper realMapper = new ObjectMapper();
    SchemaService service = new SchemaService(solrClient, realMapper);

    List<Map<String, Object>> types = List.of(Map.of(
            "name", "openai_embedding",
            "class", "solr.DenseVectorField",
            "vectorDimension", 1536,
            "similarityFunction", "cosine",
            "knnAlgorithm", "hnsw"));

    when(solrClient.request(any(SolrRequest.class), eq("col"))).thenReturn(new NamedList<>());

    SchemaUpdateResult result = service.addFieldTypes("col", types);

    assertTrue(result.success());
    assertEquals(List.of("openai_embedding"), result.addedNames());
}

@Test
void addFieldTypes_solrThrows_propagates() throws Exception {
    ObjectMapper realMapper = new ObjectMapper();
    SchemaService service = new SchemaService(solrClient, realMapper);

    when(solrClient.request(any(SolrRequest.class), eq("col")))
            .thenThrow(new SolrServerException("simulated"));

    assertThrows(SolrServerException.class,
            () -> service.addFieldTypes("col",
                    List.of(Map.of("name", "x", "class", "solr.StrField"))));
}
```

Note: tests use a real `ObjectMapper` rather than the `@Mock` one because `toFieldTypeDefinition` calls `convertValue(...)` which must actually work. The class-level `@Mock ObjectMapper` stays — only the field-type tests construct a real one.

- [ ] **Step 2: Run tests, expect failure**

```bash
./gradlew test --tests SchemaServiceTest -i
```

Expected: 5 new tests FAIL with compilation errors (no `addFieldTypes` method).

- [ ] **Step 3: Implement `addFieldTypes` + `toFieldTypeDefinition` in `SchemaService.java`**

Add imports: `java.util.LinkedHashMap`, `org.apache.solr.client.solrj.request.schema.AnalyzerDefinition`, `org.apache.solr.client.solrj.request.schema.FieldTypeDefinition`.

Append after `addFields`:

```java
@PreAuthorize("isAuthenticated()")
@McpTool(name = "add-field-types", description = "Add one or more field types to a Solr collection schema. "
        + "Call get-schema first to inspect existing field types before adding. "
        + "Each map follows the Solr Schema API add-field-type shape: required keys "
        + "'name' and 'class', optional 'analyzer' (or 'indexAnalyzer'+'queryAnalyzer'), "
        + "and class-specific attributes. "
        + "Common recipes: "
        + "(1) case-insensitive exact match: class=solr.TextField with analyzer "
        + "{tokenizer:{class:solr.KeywordTokenizerFactory}, filters:[{class:solr.LowerCaseFilterFactory}]}; "
        + "(2) dense vector for semantic search: class=solr.DenseVectorField with "
        + "vectorDimension, similarityFunction (cosine/dot_product/euclidean), and knnAlgorithm=hnsw; "
        + "(3) autocomplete: class=solr.TextField with separate indexAnalyzer using EdgeNGramFilterFactory "
        + "and queryAnalyzer without it. "
        + "After adding a type, use add-fields to create fields of that type. "
        + "Commands run in input order; partial application possible on failure.")
public SchemaUpdateResult addFieldTypes(
        @McpToolParam(description = "Solr collection name") String collection,
        @McpToolParam(description = "List of field type definitions (Solr add-field-type JSON shape)")
                List<Map<String, Object>> fieldTypes)
        throws SolrServerException, IOException {
    requireCollection(collection);
    requireNonEmpty(fieldTypes, "fieldTypes");

    List<String> names = new ArrayList<>(fieldTypes.size());
    List<SchemaRequest.Update> updates = new ArrayList<>(fieldTypes.size());
    for (Map<String, Object> fieldType : fieldTypes) {
        names.add(String.valueOf(fieldType.get("name")));
        updates.add(new SchemaRequest.AddFieldType(toFieldTypeDefinition(fieldType)));
    }

    new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
    return new SchemaUpdateResult(collection, true, names, new Date());
}

/**
 * Builds a {@link FieldTypeDefinition} from a flat input map matching the Solr Schema API
 * add-field-type JSON shape. SolrJ's {@code FieldTypeDefinition} stores name/class and
 * other scalar attributes inside an attributes {@link Map}, with analyzers pulled into
 * typed sub-objects — so we can't deserialize the flat input directly via Jackson.
 */
private FieldTypeDefinition toFieldTypeDefinition(Map<String, Object> input) {
    FieldTypeDefinition def = new FieldTypeDefinition();
    Map<String, Object> attributes = new LinkedHashMap<>(input);
    Object analyzer = attributes.remove("analyzer");
    Object indexAnalyzer = attributes.remove("indexAnalyzer");
    Object queryAnalyzer = attributes.remove("queryAnalyzer");
    def.setAttributes(attributes);
    if (analyzer != null) {
        def.setAnalyzer(toAnalyzerDefinition(analyzer));
    }
    if (indexAnalyzer != null) {
        def.setIndexAnalyzer(toAnalyzerDefinition(indexAnalyzer));
    }
    if (queryAnalyzer != null) {
        def.setQueryAnalyzer(toAnalyzerDefinition(queryAnalyzer));
    }
    return def;
}

private AnalyzerDefinition toAnalyzerDefinition(Object raw) {
    return objectMapper.convertValue(raw, AnalyzerDefinition.class);
}
```

- [ ] **Step 4: Run tests, expect pass**

```bash
./gradlew test --tests SchemaServiceTest -i
```

Expected: all 9+ new tests PASS plus the pre-existing tests still pass.

- [ ] **Step 5: Apply Spotless and commit**

```bash
./gradlew spotlessApply
git add src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java \
        src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceTest.java
git commit -s -m "$(cat <<'EOF'
feat(schema): add add-field-types MCP tool with FieldTypeDefinition helper

Supports single analyzer, separate index/query analyzers, and non-analyzer
field types like DenseVectorField. Manual conversion from flat input map
to SolrJ FieldTypeDefinition because name/class go into attributes map.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Register `SchemaUpdateResult` for native image reflection

**Files:**
- Modify: `src/main/java/org/apache/solr/mcp/server/config/SolrNativeHints.java`

- [ ] **Step 1: Add `SchemaUpdateResult` to the `MCP_RESPONSE_RECORDS` list**

Open `SolrNativeHints.java`. Find the `MCP_RESPONSE_RECORDS` list (around line 63). Append the new entry. The list becomes:

```java
private static final List<String> MCP_RESPONSE_RECORDS = List.of(
        "org.apache.solr.mcp.server.collection.CollectionCreationResult",
        "org.apache.solr.mcp.server.collection.SolrHealthStatus",
        "org.apache.solr.mcp.server.collection.SolrMetrics", "org.apache.solr.mcp.server.collection.IndexStats",
        "org.apache.solr.mcp.server.collection.FieldStats", "org.apache.solr.mcp.server.collection.QueryStats",
        "org.apache.solr.mcp.server.collection.CacheStats", "org.apache.solr.mcp.server.collection.CacheInfo",
        "org.apache.solr.mcp.server.collection.HandlerStats", "org.apache.solr.mcp.server.collection.HandlerInfo",
        "org.apache.solr.mcp.server.search.SearchResponse",
        "org.apache.solr.mcp.server.schema.SchemaUpdateResult");
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Apply Spotless and commit**

```bash
./gradlew spotlessApply
git add src/main/java/org/apache/solr/mcp/server/config/SolrNativeHints.java
git commit -s -m "$(cat <<'EOF'
feat(config): register SchemaUpdateResult for GraalVM native image reflection

Same pattern as the other @McpTool response records — invisible to AOT
because MCP dispatches via Object.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Extend `SchemaServiceIntegrationTest` with real-Solr integration tests

**Files:**
- Modify: `src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceIntegrationTest.java`

Reuse the existing `TEST_COLLECTION = "schema_test_collection"` setup. Use unique field/type names per test method to avoid collisions across test ordering.

- [ ] **Step 1: Add the integration tests**

Append after the existing tests. Add imports: `java.util.List`, `java.util.Map`, `org.apache.solr.client.solrj.SolrServerException`, `org.apache.solr.client.solrj.response.QueryResponse`, `org.apache.solr.client.solrj.request.SolrQuery`, `org.apache.solr.common.SolrInputDocument`.

```java
@Test
void addFields_endToEnd_persistsToSchema() throws Exception {
    List<Map<String, Object>> fields = List.of(
            Map.of("name", "addf_title", "type", "text_general", "stored", true, "indexed", true),
            Map.of("name", "addf_platform", "type", "string", "stored", true, "indexed", true,
                    "docValues", true),
            Map.of("name", "addf_year", "type", "pint", "stored", true, "indexed", true, "docValues", true));

    SchemaUpdateResult result = schemaService.addFields(TEST_COLLECTION, fields);

    assertTrue(result.success());
    assertEquals(List.of("addf_title", "addf_platform", "addf_year"), result.addedNames());

    SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);
    Map<String, Object> title = schema.getFields().stream()
            .filter(f -> "addf_title".equals(f.get("name"))).findFirst().orElseThrow();
    Map<String, Object> platform = schema.getFields().stream()
            .filter(f -> "addf_platform".equals(f.get("name"))).findFirst().orElseThrow();
    Map<String, Object> year = schema.getFields().stream()
            .filter(f -> "addf_year".equals(f.get("name"))).findFirst().orElseThrow();

    assertEquals("text_general", title.get("type"));
    assertEquals("string", platform.get("type"));
    assertEquals(Boolean.TRUE, platform.get("docValues"));
    assertEquals("pint", year.get("type"));
}

@Test
void addFieldTypes_customAnalyzer_appliesAtIndexAndQuery() throws Exception {
    schemaService.addFieldTypes(TEST_COLLECTION, List.of(Map.of(
            "name", "aft_text_ci_keyword",
            "class", "solr.TextField",
            "analyzer", Map.of(
                    "tokenizer", Map.of("class", "solr.KeywordTokenizerFactory"),
                    "filters", List.of(Map.of("class", "solr.LowerCaseFilterFactory"))))));

    schemaService.addFields(TEST_COLLECTION, List.of(Map.of(
            "name", "aft_ci_platform",
            "type", "aft_text_ci_keyword",
            "stored", true, "indexed", true)));

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", "aft-doc-1");
    doc.addField("aft_ci_platform", "NetFlix");
    solrClient.add(TEST_COLLECTION, doc);
    solrClient.commit(TEST_COLLECTION);

    QueryResponse lowercase = solrClient.query(TEST_COLLECTION, new SolrQuery("aft_ci_platform:netflix"));
    assertEquals(1L, lowercase.getResults().getNumFound());

    QueryResponse uppercase = solrClient.query(TEST_COLLECTION, new SolrQuery("aft_ci_platform:NETFLIX"));
    assertEquals(1L, uppercase.getResults().getNumFound());

    QueryResponse partial = solrClient.query(TEST_COLLECTION, new SolrQuery("aft_ci_platform:net"));
    assertEquals(0L, partial.getResults().getNumFound());
}

@Test
void addFieldTypes_denseVectorField_schemaRoundTrip() throws Exception {
    schemaService.addFieldTypes(TEST_COLLECTION, List.of(Map.of(
            "name", "aft_test_vector",
            "class", "solr.DenseVectorField",
            "vectorDimension", 4,
            "similarityFunction", "cosine")));

    SchemaRepresentation schema = schemaService.getSchema(TEST_COLLECTION);
    Map<String, Object> vt = schema.getFieldTypes().stream()
            .filter(t -> "aft_test_vector".equals(t.getAttributes().get("name")))
            .findFirst().orElseThrow().getAttributes();

    assertEquals("solr.DenseVectorField", vt.get("class"));
    assertEquals(4, ((Number) vt.get("vectorDimension")).intValue());
    assertEquals("cosine", vt.get("similarityFunction"));
}

@Test
void addFields_duplicateField_throws() throws Exception {
    List<Map<String, Object>> field = List.of(
            Map.of("name", "addf_dup_field", "type", "string", "stored", true, "indexed", true));
    schemaService.addFields(TEST_COLLECTION, field);

    // Second call with same name — Solr returns an error in the response body.
    // Whether SolrJ throws or returns silently is part of what this test verifies.
    Exception ex = assertThrows(Exception.class,
            () -> schemaService.addFields(TEST_COLLECTION, field));
    assertTrue(ex instanceof SolrServerException || ex instanceof RuntimeException,
            "Expected SolrServerException or RuntimeException, got " + ex.getClass());
}

@Test
void addFields_unknownType_throws() {
    List<Map<String, Object>> field = List.of(
            Map.of("name", "addf_broken", "type", "totally_not_a_real_type"));
    assertThrows(Exception.class, () -> schemaService.addFields(TEST_COLLECTION, field));
}
```

If the `addFields_duplicateField_throws` test FAILS (i.e., the second call returns normally because SolrJ doesn't throw on response-body errors), then `SchemaService.addFields` and `addFieldTypes` need to be updated to inspect the response and throw explicitly. This is the verification the spec called out. Add the inspection code:

```java
// Inside addFields/addFieldTypes after .process(...):
SchemaResponse.UpdateResponse response =
    new SchemaRequest.MultiUpdate(updates).process(solrClient, collection);
@SuppressWarnings("unchecked")
List<Object> errors = (List<Object>) response.getResponse().get("errors");
if (errors != null && !errors.isEmpty()) {
    throw new SolrServerException("Schema update returned errors: " + errors);
}
```

Re-run the test and confirm it passes.

- [ ] **Step 2: Run integration tests**

```bash
./gradlew test --tests SchemaServiceIntegrationTest -i
```

Expected: all integration tests PASS (existing + 5 new). If Docker isn't running, the test class is disabled automatically.

- [ ] **Step 3: Apply Spotless and commit**

```bash
./gradlew spotlessApply
git add src/test/java/org/apache/solr/mcp/server/schema/SchemaServiceIntegrationTest.java \
        src/main/java/org/apache/solr/mcp/server/schema/SchemaService.java
git commit -s -m "$(cat <<'EOF'
test(schema): integration tests for add-fields and add-field-types

End-to-end against real Solr via Testcontainers. Verifies schema
round-trip, custom analyzer behavior, vector field type registration,
and error propagation on duplicate field / unknown type.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Note: the commit may include `SchemaService.java` if the response-body inspection was added in Step 1.

---

## Task 7: Extend `McpClientIntegrationTestBase` with MCP protocol tests

**Files:**
- Modify: `src/test/java/org/apache/solr/mcp/server/McpClientIntegrationTestBase.java`

Reuse the existing `COLLECTION = "mcp-client-test"`. The new fields are added after the existing CSV indexing tests (order 14, 15). New tests are at orders 16, 17, 18.

- [ ] **Step 1: Add the ordered tests**

Append after the `searchFindsAllDocumentsAfterCsvIndexing()` test (order 15), before the `protected static String extractText(...)` helpers.

```java
@Test
@Order(16)
void addFieldsToTestCollection() throws Exception {
    List<Map<String, Object>> fields = List.of(
            Map.of("name", "platform", "type", "string", "stored", true, "indexed", true, "docValues", true),
            Map.of("name", "release_year", "type", "pint", "stored", true, "indexed", true, "docValues", true),
            Map.of("name", "genres", "type", "strings", "stored", true, "indexed", true, "docValues", true));

    CallToolResult result = mcpClient.callTool(new CallToolRequest("add-fields",
            Map.of("collection", COLLECTION, "fields", fields)));

    assertNotNull(result);
    assertNotError(result);
    String text = extractText(result);
    assertTrue(text.contains("platform"), "Result should mention added 'platform': " + text);
    assertTrue(text.contains("release_year"), "Result should mention added 'release_year': " + text);
    assertTrue(text.contains("genres"), "Result should mention added 'genres': " + text);
}

@Test
@Order(17)
void indexDocumentWithNewFields() {
    String json = """
            [
              {"id": "show-1", "title": "Breaking Bad", "author": "Vince Gilligan",
               "category": "show", "platform": "Netflix",
               "release_year": 2008, "genres": ["drama", "crime"]}
            ]
            """;

    CallToolResult result = mcpClient.callTool(new CallToolRequest("index-json-documents",
            Map.of("collection", COLLECTION, "json", json)));

    assertNotNull(result);
    assertNotError(result);
}

@Test
@Order(18)
void searchWithNewFieldFilters() throws Exception {
    CallToolResult byPlatform = mcpClient.callTool(new CallToolRequest("search",
            Map.of("collection", COLLECTION, "query", "*:*",
                    "filterQueries", List.of("platform:Netflix"))));
    Map<String, Object> r1 = OBJECT_MAPPER.readValue(extractText(byPlatform), new TypeReference<>() {
    });
    assertEquals(1, getNumFound(r1), "Should find exactly 1 doc with platform=Netflix");

    CallToolResult byGenre = mcpClient.callTool(new CallToolRequest("search",
            Map.of("collection", COLLECTION, "query", "*:*",
                    "filterQueries", List.of("genres:crime"))));
    Map<String, Object> r2 = OBJECT_MAPPER.readValue(extractText(byGenre), new TypeReference<>() {
    });
    assertEquals(1, getNumFound(r2), "Multi-valued 'genres' should match on 'crime'");
}
```

Also update the `listToolsReturnsExpectedTools` test (order 2) to assert the new tool names appear:

```java
assertTrue(toolNames.contains("add-fields"), "Should have add-fields tool");
assertTrue(toolNames.contains("add-field-types"), "Should have add-field-types tool");
```

- [ ] **Step 2: Run both HTTP and stdio variants**

```bash
./gradlew test --tests McpClientIntegrationTest --tests McpClientStdioIntegrationTest -i
```

Expected: both subclasses PASS the full 18-test sequence. If the HTTP variant fails on `add-fields` with a 401/403, the test infrastructure for the HTTP transport isn't supplying auth correctly. In that case, inspect `McpClientIntegrationTest` to see how it authenticates other `@PreAuthorize` tool calls (`create-collection`, `list-collections`) — the same mechanism applies.

- [ ] **Step 3: Apply Spotless and commit**

```bash
./gradlew spotlessApply
git add src/test/java/org/apache/solr/mcp/server/McpClientIntegrationTestBase.java
git commit -s -m "$(cat <<'EOF'
test: extend MCP client integration tests for schema modification tools

Adds ordered tests 16-18 exercising the add-fields → index → search
workflow through the MCP protocol against both HTTP and stdio transports.
Also asserts add-fields and add-field-types appear in listTools output.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Locate the MCP tools table or list**

```bash
grep -n -A 2 -B 2 "get-schema\|list-collections\|create-collection" README.md | head -40
```

Identify the section where existing tools are listed (likely a table or bullet list under an "MCP Tools" heading).

- [ ] **Step 2: Add the two new tools**

Add entries for `add-fields` and `add-field-types` in the same style as adjacent tools. Use these descriptions:

- `add-fields` — Add one or more fields to a Solr collection schema (additive only; existing fields cannot be modified).
- `add-field-types` — Add one or more field types to a Solr collection schema (supports custom analyzers, DenseVectorField for semantic search, etc.).

If the README has a worked example section, add a small example showing the shows-collection workflow (create-collection → add-fields → index-json-documents → search). Keep it under ~15 lines.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -s -m "$(cat <<'EOF'
docs: document add-fields and add-field-types MCP tools in README

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the SchemaService entry**

Find the `- **SchemaService**` line under "MCP Tools" → "Architecture". Change:

```
- **SchemaService** (`schema/`) - Schema introspection
```

to:

```
- **SchemaService** (`schema/`) - Schema introspection and additive modification (add-fields, add-field-types)
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -s -m "$(cat <<'EOF'
docs: update CLAUDE.md SchemaService entry for new schema-modification tools

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Full build verification

- [ ] **Step 1: Spotless check**

```bash
./gradlew spotlessCheck
```

Expected: BUILD SUCCESSFUL. If it fails, run `./gradlew spotlessApply`, inspect changes with `git diff`, commit them with `style: spotless` if non-trivial, otherwise amend the most recent commit.

- [ ] **Step 2: Full build (JVM path)**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. All tests pass including the new unit + integration + MCP client tests. If any pre-existing test regresses, stop and investigate before continuing.

- [ ] **Step 3: Native test (optional but recommended)**

```bash
./gradlew nativeTest -Pnative
```

Expected: BUILD SUCCESSFUL. Mockito-based unit tests are skipped via `@DisabledInNativeImage`; integration tests run. If the native test fails with a "missing reflection metadata" error for a SolrJ type involved in schema modification (e.g., `FieldTypeDefinition`, `AnalyzerDefinition`), add reflection registrations to `SolrNativeHints.Registrar.registerHints()`:

```java
hints.reflection().registerType(
    org.apache.solr.client.solrj.request.schema.FieldTypeDefinition.class, categories);
hints.reflection().registerType(
    org.apache.solr.client.solrj.request.schema.AnalyzerDefinition.class, categories);
```

Then commit:

```bash
git add src/main/java/org/apache/solr/mcp/server/config/SolrNativeHints.java
git commit -s -m "$(cat <<'EOF'
fix(native): register SolrJ schema-modification types for reflection

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Docker integration test (optional)**

```bash
./gradlew dockerIntegrationTest
```

Expected: BUILD SUCCESSFUL. Exercises the new MCP tool tests via the Jib JVM image, end-to-end through the MCP protocol over both stdio and HTTP transports.

Only run this if Docker is available. If it fails on the new tests but passes on master, investigate before moving on.

---

## Task 11: Push branch and open PR

- [ ] **Step 1: Confirm with user before pushing**

Pushing the branch to `origin` and opening a PR are remote-visible actions. Stop and ask the user to confirm both before proceeding.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin schema-modification
```

- [ ] **Step 3: Open PR against `apache/solr-mcp:main`**

```bash
gh pr create --repo apache/solr-mcp --base main --head adityamparikh:schema-modification \
    --title "feat: add schema modification MCP tools (add-fields, add-field-types)" \
    --body "$(cat <<'EOF'
## Summary
- Adds `add-fields` and `add-field-types` MCP tools to extend a collection's schema additively from the MCP layer
- Partially closes #30 — `replace-*`, `delete-*`, `add-copy-field`, `add-dynamic-field`, and `add-codec-factory` deferred (see spec for rationale)
- Both tools take `List<Map<String, Object>>` matching Solr's Schema API JSON shape; batched via `SchemaRequest.MultiUpdate`
- Both tools are `@PreAuthorize("isAuthenticated()")` — HTTP enforces auth, stdio bypasses (same pattern as existing tools)

## Design and plan
- Spec: `docs/superpowers/specs/2026-05-17-schema-modification-design.md`
- Plan: `docs/superpowers/plans/2026-05-17-schema-modification.md`

## Test plan
- [x] Unit tests (Mockito, `@DisabledInNativeImage`) — validation, happy path, error propagation
- [x] Integration tests (Testcontainers, real Solr) — schema round-trip, custom analyzer behavior, DenseVectorField, duplicate/unknown-type errors
- [x] MCP protocol tests (`McpClientIntegrationTestBase`) — end-to-end add-fields → index → search via MCP tool calls over both HTTP and stdio transports
- [x] Full `./gradlew build` passes with no regressions
- [ ] `./gradlew nativeTest -Pnative` (verify in CI)
- [ ] `./gradlew dockerIntegrationTest` (verify in CI)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR is created and the URL is printed. Share the URL with the user.

---

## Notes for the executor

- **Conventional commits.** Every commit uses a Conventional Commits prefix (`feat`, `test`, `docs`, `fix`, `chore`, `style`) and the scope where applicable (`feat(schema):`, `test(schema):`, `docs:`).
- **Signoffs.** Every commit uses `-s` (or includes `Signed-off-by:` manually). User's global CLAUDE.md requires this.
- **Spotless.** Run `./gradlew spotlessApply` before each commit. The pre-commit hook or CI will reject unformatted code otherwise.
- **No `--no-verify` or `--no-gpg-sign`.** Hard rule per the system prompt.
- **Confirm before remote-affecting operations.** Step 3 of Task 1 (`git push origin main`), Steps 2–3 of Task 11 (push and PR creation).
- **`SchemaResponse.UpdateResponse` shape verification.** Task 6 Step 1 includes the verification — if the duplicate-field test passes without manual error inspection, SolrJ throws automatically and the code is fine as written. If it fails, add the response-body inspection shown in that step and re-run.
