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
package org.apache.solr.mcp.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.TestDocuments;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Integration tests for SearchService using a real Solr instance via Testcontainers. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SearchServiceIntegrationTest {

  private static final String COLLECTION_NAME = "search_test_" + System.currentTimeMillis();

  /** Restricts a search to the ten sample books; testSpecialCharactersInQuery adds an eleventh. */
  private static final List<String> BOOKS_ONLY = List.of("cat_ss:book");

  /** A collection whose schema's uniqueKey is {@code sku} instead of {@code id}. */
  private static final String SKU_COLLECTION = "search_sku_" + System.currentTimeMillis();

  /**
   * Builds and uploads a configset for {@link #SKU_COLLECTION}. SolrCloud cannot change a uniqueKey
   * through the Schema API, so this copies {@code _default}, makes {@code sku} the uniqueKey (a
   * required string field; {@code id} stays, no longer required) and uploads the copy to the
   * embedded ZooKeeper. {@code %1$s} is the configset name.
   */
  private static final String SKU_CONFIGSET_SCRIPT =
      """
      set -e
      rm -rf /tmp/%1$s
      cp -r /opt/solr/server/solr/configsets/_default/conf /tmp/%1$s
      sed -i \
        -e 's|<uniqueKey>id</uniqueKey>|<uniqueKey>sku</uniqueKey>|' \
        -e '/<field name="id" /s/required="true"/required="false"/' \
        -e '/<field name="id" /a <field name="sku" type="string" indexed="true" stored="true" required="true" multiValued="false" />' \
        /tmp/%1$s/managed-schema.xml
      grep -q '<uniqueKey>sku</uniqueKey>' /tmp/%1$s/managed-schema.xml
      /opt/solr/bin/solr zk upconfig -n %1$s -d /tmp/%1$s -z localhost:9983
      """;

  private static boolean initialized = false;
  private static boolean skuCollectionCreated = false;
  @Autowired private SearchService searchService;
  @Autowired private IndexingService indexingService;
  @Autowired private SolrClient solrClient;
  @Autowired private SolrContainer solrContainer;

  @BeforeEach
  void setUp() throws Exception {
    if (!initialized) {
      CollectionAdminRequest.Create createRequest =
          CollectionAdminRequest.createCollection(COLLECTION_NAME, "_default", 1, 1);
      createRequest.process(solrClient);

      String sampleData =
          """
					[
					  {
					    "id": "book001",
					    "platform_ss": ["netflix"],
					    "name": ["A Game of Thrones"],
					    "author_ss": ["George R.R. Martin"],
					    "price": [7.99],
					    "genre_s": "fantasy",
					    "series_s": "A Song of Ice and Fire",
					    "sequence_i": 1,
					     "cat_ss": ["book"]
					  },
					  {
					    "id": "book002",
					    "platform_ss": ["netflix"],
					    "name": ["A Clash of Kings"],
					    "author_ss": ["George R.R. Martin"],
					    "price": [8.99],
					    "genre_s": "fantasy",
					    "series_s": "A Song of Ice and Fire",
					    "sequence_i": 2,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book003",
					    "platform_ss": ["hulu"],
					    "name": ["A Storm of Swords"],
					    "author_ss": ["George R.R. Martin"],
					    "price": [9.99],
					    "genre_s": "fantasy",
					    "series_s": "A Song of Ice and Fire",
					    "sequence_i": 3,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book004",
					    "name": ["The Hobbit"],
					    "author_ss": ["J.R.R. Tolkien"],
					    "price": [6.99],
					    "genre_s": "fantasy",
					    "series_s": "Middle Earth",
					    "sequence_i": 1,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book005",
					    "name": ["Dune"],
					    "author_ss": ["Frank Herbert"],
					    "price": [10.99],
					    "genre_s": "scifi",
					    "series_s": "Dune",
					    "sequence_i": 1,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book006",
					    "name": ["Foundation"],
					    "author_ss": ["Isaac Asimov"],
					    "price": [5.99],
					    "genre_s": "scifi",
					    "series_s": "Foundation",
					    "sequence_i": 1,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book007",
					    "name": ["The Fellowship of the Ring"],
					    "author_ss": ["J.R.R. Tolkien"],
					    "price": [8.99],
					    "genre_s": "fantasy",
					    "series_s": "The Lord of the Rings",
					    "sequence_i": 1,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book008",
					    "name": ["The Two Towers"],
					    "author_ss": ["J.R.R. Tolkien"],
					    "price": [8.99],
					    "genre_s": "fantasy",
					    "series_s": "The Lord of the Rings",
					    "sequence_i": 2,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book009",
					    "name": ["The Return of the King"],
					    "author_ss": ["J.R.R. Tolkien"],
					    "price": [8.99],
					    "genre_s": "fantasy",
					    "series_s": "The Lord of the Rings",
					    "sequence_i": 3,
					    "cat_ss": ["book"]
					  },
					  {
					    "id": "book010",
					    "name": ["Neuromancer"],
					    "author_ss": ["William Gibson"],
					    "price": [7.99],
					    "genre_s": "scifi",
					    "series_s": "Sprawl",
					    "sequence_i": 1,
					    "cat_ss": ["book"]
					  }
					]
					""";

      indexingService.indexJsonDocuments(COLLECTION_NAME, TestDocuments.json(sampleData));
      solrClient.commit(COLLECTION_NAME);
      initialized = true;
    }
  }

  @Test
  void testBasicSearch() throws SolrServerException, IOException {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
    assertNotNull(result);
    List<SearchResponse.SearchHit> documents = result.documents();
    assertFalse(documents.isEmpty());
    assertEquals(10, documents.size());
  }

  /**
   * Zero matches is an ordinary search outcome, not an error. Solr writes an empty facet as {@code
   * []}, which must still reach SolrJ as a NamedList — {@code QueryResponse.getFacetFields()} casts
   * to one, so a plain list surfaces as {@code ClassCastException: ArrayList cannot be cast to
   * NamedList} instead of an empty result.
   *
   * <p>{@code config.JsonResponseParserTest} pins the same behaviour at the parser boundary against
   * a hand-written payload. This test is the end-to-end counterpart: it proves a real Solr actually
   * emits {@code []} for a zero-hit facet, which is the premise the unit tests assume.
   */
  @Test
  void facetingAQueryThatMatchesNothingReturnsEmptyFacets()
      throws SolrServerException, IOException {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME,
            "genre_s:no_such_genre_exists",
            null,
            List.of("genre_s"),
            null,
            null,
            0);

    assertNotNull(result);
    assertEquals(0, result.numFound(), "the filter is designed to match nothing");
    assertNotNull(result.facets(), "facets must be present even when nothing matched");
    assertTrue(
        facetValues(result, "genre_s").isEmpty(),
        () -> "expected no facet values, got: " + result.facets());
  }

  @Test
  void searchWithBlankOptionsPreservesFiltersFacetsAndSort() throws Exception {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME,
            " \t",
            List.of("", "platform_ss:netflix", " "),
            List.of(" ", "platform_ss", ""),
            List.of(new SortClause("", ""), new SortClause("id", "desc")),
            0,
            10);
    assertEquals(2, result.numFound());
    assertEquals(List.of("book002", "book001"), ids(result));
    assertEquals(
        List.of(new SearchResponse.FacetValue("netflix", 2)), facetValues(result, "platform_ss"));
  }

  /**
   * Remediation hints classify Solr's error text, which this server cannot see at compile time —
   * the strings are produced by solr-core, and only solr-solrj is on the classpath. These tests
   * therefore provoke each failure on a real Solr server and assert the hint survives: they are the
   * only thing standing between a reworded Solr message and a hint that silently stops appearing.
   *
   * <p>Each asserts on the hint constant, never on the token being matched — an assertion routed
   * through the same token the matcher uses would pass even if Solr changed its wording, which is
   * exactly the regression being guarded.
   */
  @Test
  void searchWithUndefinedFieldInQueryReturnsGetSchemaHint() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                searchService.search(
                    COLLECTION_NAME, "definitely_not_a_field:value", null, null, null, null, null));
    assertTrue(
        e.getMessage().contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted(COLLECTION_NAME)),
        () -> "expected get-schema hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  @Test
  void searchWithUndefinedFieldInFilterQueryReturnsGetSchemaHint() {
    List<String> filterQueries = List.of("definitely_not_a_field:value");
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                searchService.search(
                    COLLECTION_NAME, "*:*", filterQueries, null, null, null, null));
    assertTrue(
        e.getMessage().contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted(COLLECTION_NAME)),
        () -> "expected get-schema hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /** Faceting words it differently: {@code undefined field: "name"}. */
  @Test
  void searchWithUndefinedFacetFieldReturnsGetSchemaHint() {
    List<String> facetFields = List.of("definitely_not_a_field");
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                searchService.search(COLLECTION_NAME, "*:*", null, facetFields, null, null, null));
    assertTrue(
        e.getMessage().contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted(COLLECTION_NAME)),
        () -> "expected get-schema hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /**
   * Sorting words it differently again: {@code sort param field can't be found}, which is why the
   * matcher carries a second undefined-field token.
   */
  @Test
  void searchWithUndefinedSortFieldReturnsGetSchemaHint() {
    List<SortClause> sort = List.of(new SortClause("definitely_not_a_field", "asc"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> searchService.search(COLLECTION_NAME, "*:*", null, null, sort, null, null));
    assertTrue(
        e.getMessage().contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted(COLLECTION_NAME)),
        () -> "expected get-schema hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /**
   * eDisMax words an undefined {@code qf} field as a {@code SyntaxError}, so without its own token
   * it would get the Lucene-syntax hint and send the caller to fix the query instead of the field.
   */
  @Test
  void searchWithUndefinedQueryFieldReturnsGetSchemaHint() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                searchService.search(
                    COLLECTION_NAME,
                    "kings",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of("definitely_not_a_field")));
    assertTrue(
        e.getMessage().contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted(COLLECTION_NAME)),
        () -> "expected get-schema hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  @Test
  void searchWithQueryFieldsSearchesOnlyThoseFields() throws Exception {
    SearchResponse byName =
        searchService.search(
            COLLECTION_NAME, "kings", null, null, null, null, null, null, List.of("name"));
    assertEquals(List.of("book002"), ids(byName));

    SearchResponse byAuthor =
        searchService.search(
            COLLECTION_NAME, "kings", null, null, null, null, null, null, List.of("author_ss"));
    assertEquals(0, byAuthor.numFound(), "qf must replace the default field, not add to it");
  }

  @Test
  void searchWithFieldsReturnsOnlyThoseFieldsAndScore() throws Exception {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME, "name:kings", null, null, null, null, null, List.of("id"), null);
    assertEquals(1, result.numFound());
    SearchResponse.SearchHit hit = result.documents().getFirst();
    assertEquals("book002", hit.id());
    assertNotNull(hit.score(), "score must stay in fl");
    assertEquals(Map.of(), hit.fields(), "fl must narrow the hit to the requested fields");
    assertNotNull(result.maxScore(), "score must stay in fl so maxScore is still returned");
  }

  @Test
  void searchWithUnparseableQueryReturnsLuceneSyntaxHint() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> searchService.search(COLLECTION_NAME, "name:(", null, null, null, null, null));
    assertTrue(
        e.getMessage().contains(SearchService.LUCENE_SYNTAX_HINT),
        () -> "expected Lucene syntax hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /**
   * An unknown collection is a 404 whose body is Solr's HTML page, so the message names neither the
   * collection nor "404" — it reads {@code Expected mime type in [application/json, text/plain] but
   * got text/html}. Matched on {@link org.apache.solr.common.SolrException#code()} instead.
   */
  @Test
  void searchOnMissingCollectionReturnsListCollectionsHint() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                searchService.search(
                    "definitely_not_a_collection", "*:*", null, null, null, null, null));
    assertTrue(
        e.getMessage().contains(SearchService.LIST_COLLECTIONS_HINT),
        () -> "expected list-collections hint, got: " + e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /**
   * An unclassified Solr error keeps its status but replaces backend text with safe request
   * guidance. Negative {@code rows} is structurally identical to the undefined-field failures, so
   * it also pins that text matching is not over-broad.
   */
  @Test
  void searchWithUnrecognizedSolrErrorPropagatesWithoutHint() {
    SolrException e =
        assertThrows(
            SolrException.class,
            () -> searchService.search(COLLECTION_NAME, "*:*", null, null, null, null, -5));
    assertFalse(e.getMessage().contains("Hint:"), () -> "expected no hint, got: " + e.getMessage());
    assertEquals(SolrException.ErrorCode.BAD_REQUEST.code, e.code());
    assertTrue(e.getMessage().contains("pagination"));
    assertTrue(e.getMessage().contains("list-fields"));
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /**
   * The search tool always requests the relevance score (fl=score,*), so a non-{@code *:*} query —
   * which actually produces a meaningful ranking — must come back with a non-null {@code maxScore}
   * and a {@code score} on every document. Both were silently dropped before this fix: Solr only
   * populates them when {@code score} is in the field list, which this service never set.
   */
  @Test
  void testSearchWithQueryPopulatesMaxScoreAndDocumentScore()
      throws SolrServerException, IOException {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, "genre_s:fantasy", null, null, null, null, null);
    assertNotNull(result);
    assertNotNull(result.maxScore(), "maxScore must be populated for a non-*:* query");
    assertTrue(result.maxScore() > 0, "maxScore should be a positive relevance score");
    assertFalse(result.documents().isEmpty());
    for (SearchResponse.SearchHit hit : result.documents()) {
      assertNotNull(hit.score(), "each hit should carry its relevance score");
      assertFalse(hit.fields().containsKey("score"), "score is reported on the hit, not in fields");
    }
  }

  @Test
  void testSearchWithQuery() throws SolrServerException, IOException {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME, "name:\"Game of Thrones\"", null, null, null, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertEquals(1, documents.size());
    Map<String, Object> book = documents.getFirst();
    assertEquals("A Game of Thrones", ((List<?>) book.get("name")).getFirst());
  }

  @Test
  void testSearchReturnsAuthor() throws Exception {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME, "author_ss:\"George R.R. Martin\"", null, null, null, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertEquals(3, documents.size());
    Map<String, Object> book = documents.getFirst();
    assertEquals("George R.R. Martin", ((List<?>) book.get("author_ss")).getFirst());
  }

  @Test
  void testSearchWithFacets() throws Exception {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, null, List.of("genre_s"), null, null, null);
    assertNotNull(result);
    List<SearchResponse.FacetField> facets = result.facets();
    assertNotNull(facets);
    assertEquals(
        List.of("genre_s"), facets.stream().map(SearchResponse.FacetField::field).toList());
  }

  @Test
  void testSearchWithPrice() throws Exception {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    Map<String, Object> book = documents.getFirst();
    double currentPrice =
        ((List<?>) book.get("price")).isEmpty()
            ? 0.0
            : ((Number) ((List<?>) book.get("price")).getFirst()).doubleValue();
    assertTrue(currentPrice > 0);
  }

  @Test
  void testSortByPriceAscending() throws Exception {
    List<SortClause> sortClauses = List.of(new SortClause("price", "asc"));
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, null, null, sortClauses, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    double previousPrice = 0.0;
    for (Map<String, Object> book : documents) {
      OptionalDouble priceOpt = extractPrice(book);
      if (priceOpt.isEmpty()) continue;
      double currentPrice = priceOpt.getAsDouble();
      assertTrue(
          currentPrice >= previousPrice, "Books should be sorted by price in ascending order");
      previousPrice = currentPrice;
    }
  }

  @Test
  void testSortByPriceDescending() throws Exception {
    List<SortClause> sortClauses = List.of(new SortClause("price", "desc"));
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, null, null, sortClauses, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    double previousPrice = Double.MAX_VALUE;
    for (Map<String, Object> book : documents) {
      OptionalDouble priceOpt = extractPrice(book);
      if (priceOpt.isEmpty()) continue;
      double currentPrice = priceOpt.getAsDouble();
      assertTrue(
          currentPrice <= previousPrice, "Books should be sorted by price in descending order");
      previousPrice = currentPrice;
    }
  }

  @Test
  void testSortBySequence() throws Exception {
    List<SortClause> sortClauses = List.of(new SortClause("sequence_i", "asc"));
    List<String> filterQueries = List.of("series_s:\"A Song of Ice and Fire\"");
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, filterQueries, null, sortClauses, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    int previousSequence = 0;
    for (Map<String, Object> book : documents) {
      int currentSequence = ((Number) book.get("sequence_i")).intValue();
      assertTrue(
          currentSequence >= previousSequence,
          "Books should be sorted by sequence_i in ascending order");
      previousSequence = currentSequence;
    }
  }

  @Test
  void testFilterByGenre() throws Exception {
    List<String> filterQueries = List.of("genre_s:fantasy");
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, filterQueries, null, null, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    for (Map<String, Object> book : documents) {
      String genre = (String) book.get("genre_s");
      assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
    }
  }

  @Test
  void testFilterByPriceRange() throws Exception {
    List<String> filterQueries = List.of("price:[6.0 TO 7.0]");
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, filterQueries, null, null, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    for (Map<String, Object> book : documents) {
      if (book.get("price") == null) continue;
      OptionalDouble priceOpt = extractPrice(book);
      if (priceOpt.isEmpty()) continue;
      double price = priceOpt.getAsDouble();
      assertTrue(price >= 6.0 && price <= 7.0, "All books should have price between 6.0 and 7.0");
    }
  }

  @Test
  void testCombinedSortingAndFiltering() throws Exception {
    List<SortClause> sortClauses = List.of(new SortClause("price", "desc"));
    List<String> filterQueries = List.of("genre_s:fantasy");
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, filterQueries, null, sortClauses, null, null);
    assertNotNull(result);
    List<Map<String, Object>> documents = fieldsOf(result);
    assertFalse(documents.isEmpty());
    for (Map<String, Object> book : documents) {
      String genre = (String) book.get("genre_s");
      assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
    }
    double previousPrice = Double.MAX_VALUE;
    for (Map<String, Object> book : documents) {
      Object priceObj = book.get("price");
      double currentPrice;
      if (priceObj instanceof List) {
        List<?> priceList = (List<?>) priceObj;
        if (priceList.isEmpty()) {
          continue;
        }
        currentPrice = ((Number) priceList.getFirst()).doubleValue();
      } else if (priceObj instanceof Number) {
        currentPrice = ((Number) priceObj).doubleValue();
      } else {
        continue;
      }
      assertTrue(
          currentPrice <= previousPrice, "Books should be sorted by price in descending order");
      previousPrice = currentPrice;
    }
  }

  @Test
  void testPagination() throws Exception {
    SearchResponse allResults =
        searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
    assertNotNull(allResults);
    long totalDocuments = allResults.numFound();
    assertTrue(totalDocuments > 0, "Should have at least some documents");
    SearchResponse firstPage = searchService.search(COLLECTION_NAME, null, null, null, null, 0, 2);
    assertNotNull(firstPage);
    assertEquals(0, firstPage.start(), "Start offset should be 0");
    assertEquals(totalDocuments, firstPage.numFound(), "Total count should match");
    assertEquals(2, firstPage.documents().size(), "Should return exactly 2 documents");
    SearchResponse secondPage = searchService.search(COLLECTION_NAME, null, null, null, null, 2, 2);
    assertNotNull(secondPage);
    assertEquals(2, secondPage.start(), "Start offset should be 2");
    assertEquals(totalDocuments, secondPage.numFound(), "Total count should match");
    assertEquals(2, secondPage.documents().size(), "Should return exactly 2 documents");
    List<String> firstPageIds = ids(firstPage);
    List<String> secondPageIds = ids(secondPage);
    for (String id : firstPageIds) {
      assertFalse(
          secondPageIds.contains(id), "Second page should not contain documents from first page");
    }
  }

  @Test
  void testSpecialCharactersInQuery() throws Exception {
    String specialJson =
        """
				[
				  {
				    "id": "special001",
				    "title": "Book with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /",
				    "author_ss": ["Special Author (with parentheses)"],
				    "description": "This is a test document with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /"
				  }
				]
				""";
    indexingService.indexJsonDocuments(COLLECTION_NAME, TestDocuments.json(specialJson));
    solrClient.commit(COLLECTION_NAME);
    String query = "id:special001";
    SearchResponse result =
        searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
    assertNotNull(result);
    assertEquals(1, result.numFound(), "Should find exactly one document");
    query = "author_ss:\"Special Author \\(" + "with parentheses\\)\""; // escape parentheses
    result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
    assertNotNull(result);
    assertEquals(1, result.numFound(), "Should find exactly one document");
    query = "title:special*";
    result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
    assertNotNull(result);
    assertTrue(result.numFound() > 0, "Should find at least one document");
  }

  private OptionalDouble extractPrice(Map<String, Object> document) {
    Object priceObj = document.get("price");
    if (priceObj == null) {
      return OptionalDouble.empty();
    }
    if (priceObj instanceof List) {
      List<?> priceList = (List<?>) priceObj;
      if (priceList.isEmpty()) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(((Number) priceList.getFirst()).doubleValue());
    } else if (priceObj instanceof Number) {
      return OptionalDouble.of(((Number) priceObj).doubleValue());
    }
    return OptionalDouble.empty();
  }

  /** Creates {@link #SKU_COLLECTION} from its own configset and indexes three products, once. */
  private void createSkuCollection() throws Exception {
    if (skuCollectionCreated) {
      return;
    }
    ExecResult upload =
        solrContainer.execInContainer("sh", "-c", SKU_CONFIGSET_SCRIPT.formatted(SKU_COLLECTION));
    assertEquals(0, upload.getExitCode(), () -> upload.getStdout() + upload.getStderr());
    CollectionAdminRequest.createCollection(SKU_COLLECTION, SKU_COLLECTION, 1, 1)
        .process(solrClient);
    indexingService.indexJsonDocuments(
        SKU_COLLECTION,
        TestDocuments.json(
            """
            [
              {"sku": "A-1", "name": "Anvil"},
              {"sku": "B-2", "name": "Bucket"},
              {"sku": "C-3", "name": "Crowbar"}
            ]
            """));
    solrClient.commit(SKU_COLLECTION);
    skuCollectionCreated = true;
  }

  @Test
  void uniqueKeyNotNamedIdIsReportedAsId() throws Exception {
    createSkuCollection();
    SearchResponse result =
        searchService.search(
            SKU_COLLECTION, null, null, null, List.of(new SortClause("sku", "asc")), null, null);
    assertEquals(List.of("A-1", "B-2", "C-3"), ids(result));
    for (SearchResponse.SearchHit hit : result.documents()) {
      assertFalse(hit.fields().containsKey("sku"), () -> "sku is the hit's id: " + hit);
      assertTrue(hit.fields().containsKey("name"), () -> "the other fields stay: " + hit);
    }
  }

  @Test
  void uniqueKeyNotNamedIdIsAddedToANarrowedFieldList() throws Exception {
    createSkuCollection();
    SearchResponse result =
        searchService.search(
            SKU_COLLECTION, "name:bucket", null, null, null, null, null, List.of("name"), null);
    SearchResponse.SearchHit hit = result.documents().getFirst();
    assertEquals("B-2", hit.id());
    assertEquals(Set.of("name"), hit.fields().keySet());
  }

  @Test
  void searchThroughAnAliasReturnsTheCollectionsIds() throws Exception {
    String alias = COLLECTION_NAME + "_alias";
    CollectionAdminRequest.createAlias(alias, COLLECTION_NAME).process(solrClient);
    SearchResponse result =
        searchService.search(
            alias, null, BOOKS_ONLY, null, List.of(new SortClause("id", "asc")), 0, 3);
    assertEquals(10, result.numFound());
    assertEquals(List.of("book001", "book002", "book003"), ids(result));
  }

  private static List<String> ids(SearchResponse response) {
    return response.documents().stream().map(SearchResponse.SearchHit::id).toList();
  }

  private static List<Map<String, Object>> fieldsOf(SearchResponse response) {
    return response.documents().stream().map(SearchResponse.SearchHit::fields).toList();
  }

  /** The values of one faceted field, or an empty list when the field is not among the facets. */
  private static List<SearchResponse.FacetValue> facetValues(
      SearchResponse response, String field) {
    for (SearchResponse.FacetField facet : response.facets()) {
      if (facet.field().equals(field)) {
        return facet.values();
      }
    }
    return List.of();
  }

  // ===== typed search contract (spec: search) =====

  @Test
  void pagedSearchReportsNumFoundStartAndAtMostRowsHits() throws Exception {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, BOOKS_ONLY, null, null, 5, 3);
    assertEquals(10, result.numFound());
    assertEquals(5, result.start());
    assertEquals(3, result.documents().size());
  }

  @Test
  void scoresRequestedInFieldsAreReportedOnEachHit() throws Exception {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME,
            "name:kings",
            null,
            null,
            null,
            null,
            null,
            List.of("name", "score"),
            null);
    SearchResponse.SearchHit hit = result.documents().getFirst();
    assertNotNull(hit.score());
    assertEquals(Set.of("name"), hit.fields().keySet());
  }

  @Test
  void fieldsWithoutTheUniqueKeyStillReturnItAsId() throws Exception {
    SearchResponse result =
        searchService.search(
            COLLECTION_NAME, "name:kings", null, null, null, null, null, List.of("name"), null);
    SearchResponse.SearchHit hit = result.documents().getFirst();
    assertEquals("book002", hit.id());
    assertEquals(Set.of("name"), hit.fields().keySet());
  }

  @Test
  void fieldValuesKeepTheirTypes() throws Exception {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, "id:book001", null, null, null, null, null);
    Map<String, Object> fields = result.documents().getFirst().fields();
    assertEquals(1, ((Number) fields.get("sequence_i")).intValue());
    assertEquals(List.of("George R.R. Martin"), fields.get("author_ss"));
    assertFalse(fields.containsKey("id"), "the uniqueKey is the hit's id, not one of its fields");
  }

  @Test
  void facetValuesKeepSolrsCountOrder() throws Exception {
    SearchResponse result =
        searchService.search(COLLECTION_NAME, null, BOOKS_ONLY, List.of("genre_s"), null, null, 0);
    assertEquals(
        List.of(
            new SearchResponse.FacetField(
                "genre_s",
                List.of(
                    new SearchResponse.FacetValue("fantasy", 7),
                    new SearchResponse.FacetValue("scifi", 3)))),
        result.facets());
  }

  @Test
  void searchWithoutFacetFieldsReturnsAnEmptyFacetList() throws Exception {
    SearchResponse result = searchService.search(COLLECTION_NAME, null, null, null, null, null, 0);
    assertEquals(List.of(), result.facets());
  }
}
