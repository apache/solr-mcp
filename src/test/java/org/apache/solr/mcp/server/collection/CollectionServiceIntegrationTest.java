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
package org.apache.solr.mcp.server.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.schema.SchemaService;
import org.apache.solr.mcp.server.search.SearchResponse;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectionServiceIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(CollectionServiceIntegrationTest.class);

  private static final String TEST_COLLECTION = "test_collection";

  private static final int DOC_COUNT = 50;

  // Solr 10 removed /admin/mbeans, the source of cache and handler stats; the
  // service then degrades to null for both rather than failing the call.
  private static final boolean MBEANS_AVAILABLE =
      TestcontainersConfiguration.solrMajorVersion() < 10;

  @Autowired private CollectionService collectionService;

  @Autowired private IndexingService indexingService;

  @Autowired private SearchService searchService;

  @Autowired private SchemaService schemaService;

  @Autowired private SolrClient solrClient;

  @BeforeAll
  void setupCollectionWithData() throws Exception {
    // 1. Create collection via CollectionService MCP tool
    CollectionCreationResult created =
        collectionService.createCollection(TEST_COLLECTION, null, null, null);
    assertTrue(created.success(), "Collection creation should succeed: " + created.message());
    log.debug("Test collection created: {}", TEST_COLLECTION);

    // 2. Index documents via IndexingService MCP tool
    List<Map<String, Object>> docs = new ArrayList<>();
    for (int i = 0; i < DOC_COUNT; i++) {
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("id", "doc-" + i);
      doc.put("title_s", "Document " + i);
      doc.put("category_s", (i % 2 == 0) ? "even" : "odd");
      doc.put("count_i", i);
      docs.add(doc);
    }
    indexingService.indexJsonDocuments(TEST_COLLECTION, docs);
    log.debug("Indexed {} documents via IndexingService", DOC_COUNT);

    // 3. Run searches via SearchService MCP tool to populate caches and handler
    // stats
    for (int i = 0; i < 5; i++) {
      searchService.search(TEST_COLLECTION, "*:*", null, null, null, 0, 10);
      searchService.search(TEST_COLLECTION, "title_s:Document", null, null, null, 0, 5);
      searchService.search(TEST_COLLECTION, "*:*", List.of("category_s:even"), null, null, 0, 10);
    }
    log.debug("Ran warm-up queries via SearchService");
  }

  @Test
  void testListCollections() throws Exception {
    List<String> collections = collectionService.listCollections();

    log.debug("Collections: {}", collections);

    assertNotNull(collections);
    assertFalse(collections.isEmpty());

    boolean testCollectionExists =
        collections.contains(TEST_COLLECTION)
            || collections.stream().anyMatch(col -> col.startsWith(TEST_COLLECTION + "_shard"));
    assertTrue(
        testCollectionExists,
        "Collections should contain " + TEST_COLLECTION + " (found: " + collections + ")");

    assertEquals(
        collections.size(),
        collections.stream().distinct().count(),
        "Collection names should be unique");
  }

  @Test
  void testGetCollectionStats_reflectsIndexedData() throws Exception {
    SolrMetrics metrics = collectionService.getCollectionStats(TEST_COLLECTION);

    assertNotNull(metrics);
    assertNotNull(metrics.timestamp());

    // Index stats should reflect the documents we indexed
    IndexStats indexStats = metrics.indexStats();
    assertNotNull(indexStats);
    assertEquals(DOC_COUNT, indexStats.numDocs(), "numDocs should match indexed document count");
    assertNotNull(indexStats.segmentCount());
    assertTrue(indexStats.segmentCount() >= 1, "Should have at least one segment after indexing");

    // Query stats come from the *:* probe query inside getCollectionStats
    QueryStats queryStats = metrics.queryStats();
    assertNotNull(queryStats);
    assertNotNull(queryStats.queryTime());
    assertEquals(
        DOC_COUNT, queryStats.totalResults(), "totalResults should match indexed document count");
    assertEquals(0L, queryStats.start());

    if (!MBEANS_AVAILABLE) {
      assertNull(metrics.cacheStats(), "Cache stats should be null without /admin/mbeans");
      assertNull(metrics.handlerStats(), "Handler stats should be null without /admin/mbeans");
      return;
    }

    // Cache stats should be present after warm-up queries
    assertNotNull(metrics.cacheStats(), "Cache stats should not be null after queries ran");
    CacheStats cacheStats = metrics.cacheStats();
    assertNotNull(cacheStats.queryResultCache());
    assertThat(cacheStats.queryResultCache().lookups())
        .as("Query result cache should have lookups after queries")
        .isPositive();
    assertNotNull(cacheStats.documentCache());
    assertThat(cacheStats.documentCache().lookups())
        .as("Document cache should have lookups after queries")
        .isPositive();
    assertNotNull(cacheStats.filterCache());
    assertThat(cacheStats.filterCache().lookups())
        .as("Filter cache should have lookups after filter queries")
        .isPositive();

    // Handler stats should reflect actual request counts
    assertNotNull(metrics.handlerStats(), "Handler stats should not be null after queries ran");
    HandlerStats handlerStats = metrics.handlerStats();
    assertNotNull(handlerStats.selectHandler());
    assertThat(handlerStats.selectHandler().requests())
        .as("Select handler should have processed requests")
        .isPositive();
    assertNotNull(handlerStats.updateHandler());
    assertThat(handlerStats.updateHandler().requests())
        .as("Update handler should have processed requests from indexing")
        .isPositive();
  }

  @Test
  void testGetCollectionStats_nonExistentCollection_throwsIllegalArgument() {
    String missing = "non_existent_collection";

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> collectionService.getCollectionStats(missing));

    assertTrue(
        exception.getMessage().contains("Collection not found: " + missing),
        "Message should name the missing collection: " + exception.getMessage());
  }

  @Test
  void testCheckHealth_healthy() {
    SolrHealthStatus status = collectionService.checkHealth(TEST_COLLECTION);

    log.debug("Health status: {}", status);

    assertNotNull(status);
    assertTrue(status.isHealthy());
    assertNull(status.errorMessage());
    assertEquals(TEST_COLLECTION, status.collection());

    assertNotNull(status.responseTime());
    assertTrue(status.responseTime() >= 0);

    assertEquals(
        DOC_COUNT, status.totalDocuments(), "Health check should report indexed document count");

    assertNotNull(status.lastChecked());
    assertTrue(
        java.time.Duration.between(status.lastChecked(), java.time.Instant.now()).toMillis()
            < 5000);
  }

  @Test
  void testCheckHealth_nonExistentCollection() {
    String missing = "non_existent_collection";
    SolrHealthStatus status = collectionService.checkHealth(missing);

    assertNotNull(status);
    assertFalse(status.isHealthy());
    assertNotNull(status.errorMessage());
    assertFalse(status.errorMessage().isBlank());
    assertEquals(missing, status.collection());
    assertNull(status.responseTime());
    assertNull(status.totalDocuments());
  }

  @Test
  void testCollectionNameExtraction() {
    assertEquals(TEST_COLLECTION, collectionService.extractCollectionName(TEST_COLLECTION));
    assertEquals("films", collectionService.extractCollectionName("films_shard1_replica_n1"));
    assertEquals("products", collectionService.extractCollectionName("products_shard2_replica_n3"));
    assertEquals("", collectionService.extractCollectionName(""));
  }

  @Test
  void testGetCacheMetrics_afterQueries() {
    CacheStats cacheStats = collectionService.getCacheMetrics(TEST_COLLECTION);

    if (!MBEANS_AVAILABLE) {
      assertNull(cacheStats, "Cache stats should be null without /admin/mbeans");
      return;
    }

    assertNotNull(cacheStats, "Cache stats should not be null after warm-up queries");

    // Query result cache: warm-up queries should have generated lookups
    CacheInfo qrc = cacheStats.queryResultCache();
    assertNotNull(qrc);
    assertThat(qrc.lookups())
        .as("queryResultCache lookups should be positive after queries")
        .isPositive();
    assertNotNull(qrc.hitratio());
    assertNotNull(qrc.size());

    // Document cache: reading documents populates this cache
    CacheInfo dc = cacheStats.documentCache();
    assertNotNull(dc);
    assertThat(dc.lookups())
        .as("documentCache lookups should be positive after queries")
        .isPositive();

    // Filter cache: the filter queries we ran should generate lookups
    CacheInfo fc = cacheStats.filterCache();
    assertNotNull(fc);
    assertThat(fc.lookups())
        .as("filterCache lookups should be positive after filter queries")
        .isPositive();
  }

  @Test
  void testGetHandlerMetrics_afterQueriesAndIndexing() {
    HandlerStats handlerStats = collectionService.getHandlerMetrics(TEST_COLLECTION);

    if (!MBEANS_AVAILABLE) {
      assertNull(handlerStats, "Handler stats should be null without /admin/mbeans");
      return;
    }

    assertNotNull(handlerStats, "Handler stats should not be null after activity");

    // Select handler: warm-up queries should have driven request counts > 0
    HandlerInfo select = handlerStats.selectHandler();
    assertNotNull(select);
    assertThat(select.requests())
        .as("Select handler requests should be positive after queries")
        .isPositive();
    // Solr may omit these counters entirely or report an explicit 0 - both mean
    // "nothing went wrong". Requiring null made the test depend on which.
    assertTrue(
        select.errors() == null || select.errors() == 0L, "Select handler should report no errors");
    assertTrue(
        select.timeouts() == null || select.timeouts() == 0L,
        "Select handler should report no timeouts");

    // Update handler: indexing 50 docs should have driven request counts > 0
    HandlerInfo update = handlerStats.updateHandler();
    assertNotNull(update);
    assertThat(update.requests())
        .as("Update handler requests should be positive after indexing")
        .isPositive();
  }

  @Test
  void testGetCacheMetrics_nonExistentCollection() {
    assertNull(collectionService.getCacheMetrics("non_existent_collection"));
  }

  @Test
  void testGetHandlerMetrics_nonExistentCollection() {
    assertNull(collectionService.getHandlerMetrics("non_existent_collection"));
  }

  @Test
  void createCollection_createsAndListable() throws Exception {
    String name = "mcp_test_create_" + System.currentTimeMillis();

    CollectionCreationResult result = collectionService.createCollection(name, null, null, null);

    assertTrue(result.success());
    assertEquals(name, result.name());
    assertNotNull(result.createdAt());

    List<String> collections = collectionService.listCollections();
    boolean exists =
        collections.contains(name)
            || collections.stream().anyMatch(col -> col.startsWith(name + "_shard"));
    assertTrue(
        exists, "Newly created collection should appear in list (found: " + collections + ")");
  }

  /**
   * The repro from #183: schemaless guesses made while indexing one collection must not appear in a
   * collection created after it.
   *
   * <p>The field names are prefixed because every integration test class shares one Solr container,
   * and tests that bind to {@code _default} directly write their guessed fields into {@code
   * _default} itself, which a later private copy inherits.
   */
  @Test
  void createCollection_withoutConfigSet_getsPrivateSchemaDeletedWithIt() throws Exception {
    String first = "leak183_a";
    String second = "leak183_b";

    CollectionCreationResult firstResult =
        collectionService.createCollection(first, null, null, null);
    assertEquals(first + ".AUTOCREATED", firstResult.configSet());
    indexingService.indexJsonDocuments(
        first, List.of(Map.of("id", "1", "leak183_platform", "Netflix", "leak183_rating", 7.5)));

    CollectionCreationResult secondResult =
        collectionService.createCollection(second, null, null, null);
    assertEquals(second + ".AUTOCREATED", secondResult.configSet());

    List<String> secondFields = new ArrayList<>();
    for (Map<String, Object> field : schemaService.getSchema(second).getFields()) {
      secondFields.add(String.valueOf(field.get("name")));
    }
    assertFalse(secondFields.contains("leak183_platform"), "Guessed field leaked: " + secondFields);
    assertFalse(secondFields.contains("leak183_rating"), "Guessed field leaked: " + secondFields);
    assertEquals(
        List.of("leak183_platform"),
        schemaService
            .addFields(second, List.of(Map.of("name", "leak183_platform", "type", "string")))
            .addedNames());

    assertEquals(first + ".AUTOCREATED", configNameOf(first));
    assertEquals(second + ".AUTOCREATED", configNameOf(second));

    // Solr deletes the private copy with the collection, so the server needs
    // no cleanup of its own.
    CollectionAdminRequest.deleteCollection(first).process(solrClient);
    assertFalse(
        new ConfigSetAdminRequest.List()
            .process(solrClient)
            .getConfigSets()
            .contains(first + ".AUTOCREATED"));
  }

  private String configNameOf(String collection) throws Exception {
    Object node =
        CollectionAdminRequest.getClusterStatus()
            .setCollectionName(collection)
            .process(solrClient)
            .getResponse();
    for (String key : List.of("cluster", "collections", collection, "configName")) {
      node = ((NamedList<?>) node).get(key);
    }
    return String.valueOf(node);
  }

  @Test
  void testSearchVerifiesIndexedDocuments() throws Exception {
    // Verify the documents we indexed are actually searchable via SearchService
    SearchResponse all =
        searchService.search(TEST_COLLECTION, "*:*", null, null, null, 0, DOC_COUNT);
    assertEquals(DOC_COUNT, all.numFound(), "Should find all indexed documents");
    assertEquals(DOC_COUNT, all.documents().size(), "Should return all documents in single page");

    // Filter search should return only even-category docs
    SearchResponse evens =
        searchService.search(
            TEST_COLLECTION, "*:*", List.of("category_s:even"), null, null, 0, DOC_COUNT);
    assertEquals(DOC_COUNT / 2, evens.numFound(), "Should find 25 even-category documents");
  }
}
