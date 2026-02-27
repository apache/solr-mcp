# Semantic & Hybrid Search Specification

## Problem Statement

The Solr MCP server currently supports only keyword (BM25) search via its `search` tool. Modern search applications benefit from semantic understanding — finding documents by meaning, not just keyword overlap. Apache Solr 9.x introduced native Dense Vector Search (KNN/HNSW) which enables this capability. This spec defines a `SolrVectorStore` (implementing Spring AI's `VectorStore` interface) as the foundation, plus three new MCP tools that add semantic search, hybrid search, and embedding-aware indexing to the MCP server.

## Reference Implementation

The patterns in this spec are ported from [ai-powered-search](https://github.com/adityamparikh/ai-powered-search), which contains a working `SolrVectorStore` implementation, `RrfMerger`, `EmbeddingService`, and supporting utilities.

## Design Principles

- **Spring AI `VectorStore` as foundation** — Implement `SolrVectorStore` extending `AbstractObservationVectorStore`. MCP tools delegate to it for semantic search and document indexing with embeddings.
- **Optional embedding dependency** — The server must start and all existing tools must work without an embedding model configured. Semantic/hybrid tools fail gracefully with a clear error when invoked without one.
- **Provider-agnostic** — Use Spring AI's `EmbeddingModel` interface. Users choose their provider (OpenAI, Ollama, etc.) by adding the corresponding Spring AI starter to the classpath.
- **Schema-agnostic** — Vector field name, content field, metadata prefix, and dimensions are all configurable via `SolrVectorStoreOptions`.
- **Backward compatible** — The existing `search` tool remains unchanged.
- **Consistent patterns** — Follow existing codebase conventions: `@McpTool` annotations, `@Service` classes, `SearchResponse` record, `@PreAuthorize("isAuthenticated()")`, constructor injection.

---

## SolrVectorStore (Spring AI VectorStore Implementation)

The core of this feature is a `SolrVectorStore` class that implements Spring AI's `VectorStore` interface. This does not exist in Spring AI today — it is ported from the reference implementation.

### Class: `SolrVectorStore`

Extends `AbstractObservationVectorStore` (Spring AI base class providing observability/Micrometer support).

**Builder pattern:**
```java
SolrVectorStore vectorStore = SolrVectorStore.builder(solrClient, "my-collection", embeddingModel)
    .options(SolrVectorStoreOptions.builder()
        .vectorFieldName("vector")
        .vectorDimension(1536)
        .contentFieldName("content")
        .metadataPrefix("metadata_")
        .build())
    .observationRegistry(observationRegistry)
    .build();
```

**Core methods (overriding `AbstractObservationVectorStore`):**

| Method | Description |
|--------|-------------|
| `doAdd(List<Document>)` | Generates embeddings for documents missing them, converts to `SolrInputDocument`, adds to Solr with commit |
| `doDelete(List<String>)` | Deletes documents by ID from Solr |
| `doSimilaritySearch(SearchRequest)` | Embeds query text, builds KNN query, applies filter expressions, returns `Document` list filtered by similarity threshold |
| `createObservationContextBuilder(String)` | Creates Micrometer observation context for metrics |
| `getNativeClient()` | Returns the underlying `SolrClient` |

**Key behaviors:**
- Embeddings are generated in batch via `embeddingModel.embedForResponse(texts)` for efficiency
- Documents without pre-existing embeddings get them auto-generated from `Document.getText()`
- KNN queries use POST method (`SolrRequest.METHOD.POST`) to avoid URI-too-long errors with large vectors
- Spring AI `Filter.Expression` is converted to Solr query syntax (EQ→`field:value`, GT→`field:{value TO *]`, AND/OR, etc.)
- Metadata fields are stored with a configurable prefix (default: `metadata_`)
- Similarity threshold filtering is done post-query since `score` is a Solr pseudo-field

### Record: `SolrVectorStoreOptions`

Configurable options for the vector store:

| Field | Default | Description |
|-------|---------|-------------|
| `idFieldName` | `"id"` | Solr document ID field |
| `contentFieldName` | `"content"` | Solr content/text field |
| `vectorFieldName` | `"vector"` | DenseVectorField name |
| `metadataPrefix` | `"metadata_"` | Prefix for metadata fields in Solr |
| `vectorDimension` | `1536` | Embedding vector dimensionality |

### Class: `VectorStoreFactory`

Creates and caches `SolrVectorStore` instances per collection using `ConcurrentHashMap`. Since the MCP tools accept `collection` as a per-call parameter, the factory provides the right `VectorStore` for each collection.

```java
@Component
public class VectorStoreFactory {
    public VectorStore forCollection(String collection) { ... }
    public int getCacheSize() { ... }
    public void clearCache() { ... }
    public VectorStore evict(String collection) { ... }
}
```

The factory takes `Optional<EmbeddingModel>` — when no embedding model is configured, it throws `EmbeddingNotConfiguredException` on `forCollection()` calls.

### Supporting Utilities

**`VectorFormatUtils`** — Formats `float[]` and `List<Float>` to Solr KNN query string format (`[0.1, 0.2, 0.3]`).

**`SolrQueryUtils`** — Builds KNN query strings: `{!knn f=<field> topK=<k>}<vectorString>`.

---

## New MCP Tools

### 1. `semantic-search`

Pure vector/KNN search using natural language queries. Delegates to `SolrVectorStore.similaritySearch()`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to query |
| `queryText` | `String` | Yes | — | Natural language query text |
| `vectorField` | `String` | No | `"vector"` | Name of the DenseVectorField in the Solr schema |
| `topK` | `Integer` | No | `10` | Number of nearest neighbors to return |
| `filterQueries` | `List<String>` | No | — | Solr fq parameters to narrow results |

**Behavior:**
1. Get `VectorStore` for the collection via `VectorStoreFactory`
2. Build `SearchRequest` with query, topK, and filter expression
3. Call `vectorStore.similaritySearch(request)`
4. Convert `List<Document>` results to `SearchResponse` format

**Returns:** `SearchResponse` (same record as existing `search` tool)

**Error:** Throws `EmbeddingNotConfiguredException` if no embedding model is configured.

### 2. `hybrid-search`

Combined keyword + vector search merged via client-side Reciprocal Rank Fusion (RRF).

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to query |
| `queryText` | `String` | Yes | — | Natural language query text (used for embedding and optionally for keyword search) |
| `vectorField` | `String` | No | `"vector"` | DenseVectorField name |
| `topK` | `Integer` | No | `10` | Number of nearest neighbors for KNN leg |
| `keywordQuery` | `String` | No | `queryText` | Explicit Solr query for keyword/BM25 leg (defaults to `queryText` if not provided) |
| `filterQueries` | `List<String>` | No | — | Solr fq parameters (applied to both legs) |
| `facetFields` | `List<String>` | No | — | Facet fields (applied to keyword leg only) |
| `rows` | `Integer` | No | `10` | Number of final merged results |

**Behavior:**
1. Execute keyword search via `SolrClient` (edismax on `_text_` catch-all field) with filters and facets
2. Execute vector search via `SolrClient` (KNN query built using `EmbeddingService` + `SolrQueryUtils`) with same filters
3. Merge results using `RrfMerger` (k=60)
4. Return `SearchResponse` with merged documents, facets from keyword leg

**Returns:** `SearchResponse` with `numFound` = merged document count, `maxScore` = null (RRF scores replace Solr scores), facets from keyword leg.

### 3. `index-with-embeddings`

Index JSON documents with auto-generated embedding vectors. Delegates to `SolrVectorStore.add()`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to index into |
| `json` | `String` | Yes | — | JSON string containing array of documents |
| `textFields` | `List<String>` | Yes | — | Field names whose values are concatenated for embedding generation (e.g., `["title", "content"]`) |
| `vectorField` | `String` | No | `"vector"` | Target vector field name in documents |

**Behavior:**
1. Parse JSON into `SolrInputDocument` list using existing `IndexingDocumentCreator`
2. For each document, concatenate values of `textFields` (space-separated, skip nulls/missing fields)
3. Batch embed all concatenated texts via `EmbeddingService`
4. Add embedding as `List<Float>` to each document's `vectorField`
5. Delegate to existing `IndexingService.indexDocuments(collection, documents)` for batch indexing
6. Return count of successfully indexed documents

**Returns:** `int` (count of successfully indexed documents)

---

## Architecture

### New Classes

```
src/main/java/org/apache/solr/mcp/server/
├── embedding/
│   ├── EmbeddingService.java                  # Wraps Optional<EmbeddingModel>, retry logic
│   ├── EmbeddingNotConfiguredException.java   # Runtime exception
│   └── VectorFormatUtils.java                 # float[] ↔ Solr vector string conversion
├── solr/
│   ├── SolrQueryUtils.java                    # KNN query builder
│   └── vectorstore/
│       ├── SolrVectorStore.java               # Spring AI VectorStore implementation
│       ├── SolrVectorStoreOptions.java        # Configurable options record
│       ├── VectorStoreFactory.java            # Per-collection VectorStore cache
│       └── VectorStoreConfig.java             # Spring @Configuration for VectorStore bean
├── search/
│   ├── SearchResponseBuilder.java             # Extracted shared helpers (getDocs, getFacets)
│   ├── VectorSearchService.java               # semantic-search + hybrid-search MCP tools
│   └── RrfMerger.java                         # Reciprocal Rank Fusion implementation
└── indexing/
    └── EmbeddingIndexingService.java           # index-with-embeddings MCP tool
```

### `EmbeddingService`

Wraps the optional `EmbeddingModel` Spring AI bean using `Optional<EmbeddingModel>` constructor injection. Spring injects `Optional.empty()` when no embedding provider is on the classpath.

```java
@Service
public class EmbeddingService {
    private final Optional<EmbeddingModel> embeddingModel;

    public EmbeddingService(Optional<EmbeddingModel> embeddingModel) { ... }

    public boolean isAvailable() { ... }
    public float[] embed(String text) { ... }                   // throws EmbeddingNotConfiguredException
    public List<Float> embedAsList(String text) { ... }
    public String embedAndFormatForSolr(String text) { ... }    // embed + format for KNN query
    public EmbeddingModel getEmbeddingModel() { ... }           // for VectorStoreFactory
}
```

### `RrfMerger`

Standalone class implementing Reciprocal Rank Fusion. Ported from the reference implementation.

```java
public class RrfMerger {
    static final int DEFAULT_K = 60;

    public RrfMerger() { this(DEFAULT_K); }
    public RrfMerger(int k) { ... }

    public List<Map<String, Object>> merge(
        List<Map<String, Object>> keywordResults,
        List<Map<String, Object>> vectorResults) { ... }
}
```

**RRF Algorithm:**
- Documents identified by `id` field
- `rrfScore = SUM(1 / (k + rank))` across all lists where document appears (1-indexed ranks)
- Results sorted by descending RRF score
- Output includes metadata: `rrf_score`, `keyword_score`, `vector_score`, `keyword_rank`, `vector_rank`
- Null-safe: handles null input lists as empty

### `SearchResponseBuilder`

Extracted from `SearchService`'s private `getDocs()` and `getFacets()` methods into public static utilities. `SearchService.search()` is refactored to call these (no behavioral change).

```java
public final class SearchResponseBuilder {
    public static List<Map<String, Object>> getDocs(SolrDocumentList documents) { ... }
    public static Map<String, Map<String, Long>> getFacets(QueryResponse response) { ... }
    public static SearchResponse fromQueryResponse(QueryResponse response) { ... }
}
```

### Dependency Flow

```
VectorSearchService ──> VectorStoreFactory ──> SolrVectorStore ──> EmbeddingModel
                    ──> EmbeddingService ──> Optional<EmbeddingModel>
                    ──> SolrClient (direct, for keyword leg of hybrid)
                    ──> RrfMerger
                    ──> SearchResponseBuilder

EmbeddingIndexingService ──> EmbeddingService ──> Optional<EmbeddingModel>
                         ──> IndexingService (reuse indexDocuments())
                         ──> IndexingDocumentCreator

VectorStoreFactory ──> SolrClient
                   ──> Optional<EmbeddingModel>
                   ──> ConcurrentHashMap<String, VectorStore> (cache)
```

---

## Solr Prerequisites

Collections used with semantic/hybrid search must have a `DenseVectorField` configured in their schema. Example schema additions (via Solr Schema API):

```json
{
  "add-field-type": {
    "name": "knn_vector_1536",
    "class": "solr.DenseVectorField",
    "vectorDimension": 1536,
    "similarityFunction": "cosine",
    "knnAlgorithm": "hnsw"
  }
}
```

```json
{
  "add-field": {
    "name": "vector",
    "type": "knn_vector_1536",
    "stored": true,
    "indexed": true
  }
}
```

The `vectorDimension` must match the embedding model's output dimensions (e.g., 1536 for OpenAI `text-embedding-3-small`).

---

## Embedding Model Configuration

Users configure their embedding provider via standard Spring AI properties. No new application properties are introduced by this feature. Examples:

**OpenAI:**
```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small
```

**Ollama:**
```properties
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.options.model=nomic-embed-text
```

Users must add the corresponding Spring AI starter dependency to their classpath (e.g., `spring-ai-openai-spring-boot-starter`).

---

## Dependencies

The `EmbeddingModel` interface is provided by `spring-ai-core`, which should be transitively available via the existing `spring-ai-starter-mcp-server-webmvc` dependency. If not, add it explicitly:

```toml
# gradle/libs.versions.toml [libraries]
spring-ai-core = { module = "org.springframework.ai:spring-ai-core" }
```

The `AbstractObservationVectorStore` base class and `VectorStore` interface come from `spring-ai-core` as well.

No embedding provider dependencies (OpenAI, Ollama, etc.) are added to the project. Users add their chosen provider.

---

## Implementation Notes

### KNN Query Format

Solr 9.x KNN query parser syntax:
```
{!knn f=vector topK=10}[0.1, 0.2, 0.3, ..., 0.1536]
```

The embedding `float[]` is serialized as comma-separated values inside square brackets via `VectorFormatUtils.formatVectorForSolr()`. Uses `String.valueOf(float)` (not locale-dependent `String.format()`) to ensure `.` as decimal separator.

### POST for KNN Queries

KNN queries with large vectors (~10KB for 1536 dimensions) should use `SolrRequest.METHOD.POST` to avoid URI-too-long errors.

### Filter Expression Conversion

Spring AI `Filter.Expression` is converted to Solr query syntax:

| Spring AI | Solr |
|-----------|------|
| `EQ` | `field:value` |
| `NE` | `-field:value` |
| `GT` | `field:{value TO *]` |
| `GTE` | `field:[value TO *]` |
| `LT` | `field:[* TO value}` |
| `LTE` | `field:[* TO value]` |
| `AND` | `left AND right` |
| `OR` | `left OR right` |

Metadata fields are automatically prefixed with `metadataPrefix` (default: `metadata_`).

### RRF Constant

The RRF constant `k=60` is the standard value from the original RRF paper. It controls how much weight is given to higher-ranked documents. Default is 60, but `RrfMerger` accepts a custom value via constructor.

### Hybrid Search Over-Fetching

The hybrid search fetches `topK * 2` from each leg (keyword and vector) to give RRF more candidates for better fusion quality, then limits final output to `rows`.

### Vector Field Value Format

When indexing via SolrJ, `DenseVectorField` accepts `List<Float>` via `SolrInputDocument.addField()`.

---

## Files to Create

| File | Description |
|------|-------------|
| `src/main/java/.../embedding/EmbeddingService.java` | Optional embedding model wrapper with retry |
| `src/main/java/.../embedding/EmbeddingNotConfiguredException.java` | Runtime exception for missing model |
| `src/main/java/.../embedding/VectorFormatUtils.java` | Vector format conversion utilities |
| `src/main/java/.../solr/SolrQueryUtils.java` | KNN query builder utility |
| `src/main/java/.../solr/vectorstore/SolrVectorStore.java` | Spring AI VectorStore for Solr |
| `src/main/java/.../solr/vectorstore/SolrVectorStoreOptions.java` | Configurable options record |
| `src/main/java/.../solr/vectorstore/VectorStoreFactory.java` | Per-collection VectorStore cache |
| `src/main/java/.../solr/vectorstore/VectorStoreConfig.java` | Spring configuration for VectorStore bean |
| `src/main/java/.../search/VectorSearchService.java` | `semantic-search` + `hybrid-search` MCP tools |
| `src/main/java/.../search/SearchResponseBuilder.java` | Shared Solr response conversion helpers |
| `src/main/java/.../search/RrfMerger.java` | Reciprocal Rank Fusion implementation |
| `src/main/java/.../indexing/EmbeddingIndexingService.java` | `index-with-embeddings` MCP tool |
| `src/test/java/.../embedding/EmbeddingServiceTest.java` | Unit tests |
| `src/test/java/.../solr/vectorstore/SolrVectorStoreTest.java` | Unit tests for VectorStore |
| `src/test/java/.../solr/vectorstore/VectorStoreFactoryTest.java` | Unit tests for factory |
| `src/test/java/.../search/VectorSearchServiceTest.java` | Unit tests (mocked deps) |
| `src/test/java/.../search/RrfMergerTest.java` | Unit tests for RRF algorithm |
| `src/test/java/.../indexing/EmbeddingIndexingServiceTest.java` | Unit tests |
| `src/test/java/.../solr/vectorstore/SolrVectorStoreIntegrationTest.java` | Testcontainers integration test |
| `src/test/java/.../search/VectorSearchIntegrationTest.java` | End-to-end MCP tool integration test |

## Files to Modify

| File | Change |
|------|--------|
| `src/main/java/.../search/SearchService.java` | Delegate `getDocs`/`getFacets` to `SearchResponseBuilder` |
| `src/test/java/.../McpToolRegistrationTest.java` | Add `VectorSearchService` and `EmbeddingIndexingService` to tool checks |
| `CLAUDE.md` | Document new services, tools, and embedding configuration |

---

## Implementation Phases

### Phase 1: Foundation
1. Verify `EmbeddingModel`/`AbstractObservationVectorStore` on classpath; add `spring-ai-core` if needed
2. Create `EmbeddingNotConfiguredException`, `EmbeddingService`, `VectorFormatUtils`, `SolrQueryUtils`
3. Create `EmbeddingServiceTest`
4. Extract `SearchResponseBuilder` from `SearchService`
5. Build & verify: `./gradlew build`

### Phase 2: SolrVectorStore
6. Create `SolrVectorStoreOptions`, `SolrVectorStore`, `VectorStoreFactory`, `VectorStoreConfig`
7. Create `SolrVectorStoreTest`, `VectorStoreFactoryTest`
8. Build & verify: `./gradlew build`

### Phase 3: Semantic Search + RRF
9. Create `RrfMerger`, `RrfMergerTest`
10. Create `VectorSearchService` with `semantic-search` and `hybrid-search` tools
11. Create `VectorSearchServiceTest`
12. Update `McpToolRegistrationTest`
13. Build & verify: `./gradlew build`

### Phase 4: Index with Embeddings
14. Create `EmbeddingIndexingService`, `EmbeddingIndexingServiceTest`
15. Update `McpToolRegistrationTest`
16. Build & verify: `./gradlew build`

### Phase 5: Integration Tests & Documentation
17. Create `SolrVectorStoreIntegrationTest` (Testcontainers with DenseVectorField schema)
18. Create `VectorSearchIntegrationTest` (end-to-end MCP tool test)
19. Update `CLAUDE.md`
20. Final: `./gradlew spotlessApply && ./gradlew build`

---

## Testing Strategy

### Unit Tests
- Mock `SolrClient` and `EmbeddingModel` (follows `SearchServiceDirectTest` pattern)
- Verify KNN query format, RRF correctness (overlapping/non-overlapping results, empty lists), parameter defaults, error handling
- Test `EmbeddingService` with/without `EmbeddingModel` present
- Test `SolrVectorStore` document conversion (Spring AI `Document` ↔ `SolrInputDocument`)
- Test `VectorStoreFactory` caching behavior
- Test filter expression conversion (EQ, NE, GT, AND, OR, etc.)

### Integration Tests
- Testcontainers Solr with Schema API setup (low-dimensional vectors, e.g. 3D, for speed)
- `@TestConfiguration` provides a deterministic mock `EmbeddingModel` returning fixed vectors
- End-to-end: index with embeddings via VectorStore → semantic search finds nearest → hybrid search merges correctly

### Regression
- `./gradlew build` must pass at every phase — existing tests remain green
- Server must start without an embedding model (`./gradlew bootRun` with existing config)

---

## Compatibility

- **Solr 9.0+** required for DenseVectorField/KNN support
- **Solr 8.x** — semantic/hybrid tools will fail at query time (Solr error); keyword search unaffected
- **Spring AI 1.1.2** — `EmbeddingModel` and `AbstractObservationVectorStore` interfaces stable
- **Java 25+** — no additional requirements
