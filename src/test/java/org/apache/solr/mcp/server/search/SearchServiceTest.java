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

import static org.apache.solr.mcp.server.McpResultAssertions.promptText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.json.JsonContent;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for SearchService with mocked SolrClient. */
@JsonTest
@DisabledInNativeImage
class SearchServiceTest {

  private static final String ID = "id";

  private static final String SKU = "sku";

  private static final String COLLECTION = "test_collection";

  @Autowired private JsonMapper jsonMapper;

  /**
   * A mocked client whose Schema API reports {@code uniqueKey} for every collection. Every search
   * asks for the uniqueKey before it queries, so each test needs this stub.
   */
  private static SolrClient solrClientWithUniqueKey(String uniqueKey) throws Exception {
    SolrClient client = mock(SolrClient.class);
    NamedList<Object> response = new NamedList<>();
    response.add("uniqueKey", uniqueKey);
    when(client.request(any(SchemaRequest.UniqueKey.class), any())).thenReturn(response);
    return client;
  }

  /** A service whose client answers every query with {@code documents} and {@code facets}. */
  private static SearchService serviceReturning(
      String uniqueKey, SolrDocumentList documents, List<FacetField> facets) throws Exception {
    SolrClient client = solrClientWithUniqueKey(uniqueKey);
    QueryResponse response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(documents);
    when(response.getFacetFields()).thenReturn(facets);
    when(client.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);
    return new SearchService(client);
  }

  private static SolrDocumentList documentList(long numFound, long start, SolrDocument... docs) {
    SolrDocumentList documents = new SolrDocumentList();
    documents.setNumFound(numFound);
    documents.setStart(start);
    documents.addAll(List.of(docs));
    return documents;
  }

  /** Serializes a result with the application's mapper, the way the MCP layer does. */
  private JsonContent json(Object result) {
    return new JsonContent(jsonMapper.writeValueAsString(result));
  }

  private static SolrDocument document(Map<String, Object> fields) {
    SolrDocument document = new SolrDocument();
    fields.forEach(document::setField);
    return document;
  }

  @Test
  void constructor_ShouldInitializeWithSolrClient() {
    SearchService localService = new SearchService(mock(SolrClient.class));
    assertNotNull(localService);
  }

  @Test
  void search_WithBlankFiltersAndFacets_ShouldOmitThem() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    when(mockResponse.getResults()).thenReturn(createMockDocumentList());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertEquals("*:*", sentQuery.getQuery());
              assertNull(sentQuery.getFilterQueries());
              assertNull(sentQuery.getFacetFields());
              assertNull(sentQuery.get("facet"));
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    assertNotNull(
        localService.search(
            "test_collection", " \t", List.of("", " \t"), List.of("", " \n"), null, null, null));
  }

  @Test
  void search_AsksForNamedListsAsJsonObjects() throws Exception {
    // JsonResponseParser reads facet counts only in json.nl=map style.
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    when(mockResponse.getResults()).thenReturn(createMockDocumentList());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertEquals("map", sentQuery.get("json.nl"));
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    assertNotNull(
        localService.search("test_collection", null, null, List.of("genre_s"), null, null, null));
  }

  @Test
  void search_WithBlankSortFields_ShouldOmitSorting() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    when(mockResponse.getResults()).thenReturn(createMockDocumentList());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertNull(sentQuery.getSortField());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    assertNotNull(
        localService.search(
            "test_collection",
            null,
            null,
            null,
            List.of(new SortClause("", ""), new SortClause(" \t", "asc")),
            null,
            null));
  }

  @Test
  void search_WithMixedBlankAndValidOptions_ShouldPreserveValidValues() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    when(mockResponse.getResults()).thenReturn(createMockDocumentList());
    String filter = "{!tag=genre}genre_s:fantasy";
    String facet = "{!ex=genre}genre_s";
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertEquals("{!edismax qf='name author_ss'}george martin", sentQuery.getQuery());
              assertArrayEquals(
                  new String[] {filter, "price:[0 TO 10]"}, sentQuery.getFilterQueries());
              assertArrayEquals(new String[] {facet, "author_ss"}, sentQuery.getFacetFields());
              assertEquals("true", sentQuery.get("facet"));
              assertEquals(1, sentQuery.getFacetMinCount());
              assertEquals("count", sentQuery.getFacetSortString());
              assertEquals("price desc,name asc", sentQuery.getSortField());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    assertNotNull(
        localService.search(
            "test_collection",
            "{!edismax qf='name author_ss'}george martin",
            List.of("", filter, " \n", "price:[0 TO 10]"),
            List.of("\t", facet, "", "author_ss"),
            List.of(
                new SortClause("", "asc"),
                new SortClause("price", "DESC"),
                new SortClause(" \n", ""),
                new SortClause("name", " \t")),
            null,
            null));
  }

  @Test
  void search_WithInvalidNonblankSortOrder_ShouldStillFail() throws Exception {
    SearchService localService = new SearchService(solrClientWithUniqueKey(ID));
    List<SortClause> sort = List.of(new SortClause("price", "descending"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> localService.search("test_collection", null, null, null, sort, null, null));
    assertTrue(e.getMessage().contains("'asc' or 'desc'"));
  }

  @Test
  void search_BackendFailures_ShouldReturnSafeActionableErrors() throws Exception {
    String detail = "private-token http://internal-solr:8983 /private/config java.util.ArrayList";
    for (Exception failure :
        List.of(
            new IOException(detail),
            new SolrServerException(detail),
            new SolrException(SolrException.ErrorCode.SERVER_ERROR, detail),
            new SolrException(SolrException.ErrorCode.BAD_REQUEST, detail),
            new SolrException(SolrException.ErrorCode.UNAUTHORIZED, detail),
            new SolrException(SolrException.ErrorCode.FORBIDDEN, detail),
            new ClassCastException(detail),
            new IllegalStateException(detail))) {
      SolrClient mockClient = solrClientWithUniqueKey(ID);
      when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenThrow(failure);
      SearchService localService = new SearchService(mockClient);
      Exception e =
          assertThrows(
              Exception.class,
              () ->
                  localService.search(
                      "test_collection", "*:*", null, List.of("platform"), null, null, null));
      for (String sensitive :
          List.of("private-token", "internal-solr", "/private/config", "java.util.ArrayList")) {
        assertFalse(e.getMessage().contains(sensitive), e::getMessage);
      }
      assertTrue(e.getMessage().contains("Search failed"), e::getMessage);
      assertTrue(
          e.getMessage().contains("operator") || e.getMessage().contains("list-fields"),
          e::getMessage);
      assertNull(e.getCause(), "MCP must not unwrap a raw backend failure");
      if (failure instanceof SolrException solrFailure) {
        assertEquals(solrFailure.code(), assertInstanceOf(SolrException.class, e).code());
      }
    }
  }

  @Test
  void search_FacetConversionFailure_ShouldNotReturnSuccessfulSearch() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    when(mockResponse.getResults()).thenReturn(createMockDocumentListWithData());
    ClassCastException failure =
        new ClassCastException("java.util.ArrayList cannot be cast to NamedList");
    when(mockResponse.getFacetFields()).thenThrow(failure);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    List<String> facetFields = List.of("platform");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                localService.search("test_collection", "*:*", null, facetFields, null, null, null));
    assertFalse(e.getMessage().contains("ClassCastException"));
    assertFalse(e.getMessage().contains("NamedList"));
    assertTrue(e.getMessage().contains("facetFields"));
    assertTrue(e.getMessage().contains("operator"));
    assertNull(e.getCause(), "MCP must not unwrap a raw response-conversion failure");
  }

  /*
   * These stub Solr's error text rather than observe it, so they can only show
   * that a matching message produces a hint — never that Solr still emits such a
   * message. The fixture strings below are verbatim samples captured from a real
   * Solr 9.9; SearchServiceIntegrationTest is what keeps them honest.
   */

  private static SearchService serviceThrowing(SolrException e) throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenThrow(e);
    return new SearchService(mockClient);
  }

  @Test
  void search_WithUndefinedField_ShouldHintGetSchema() throws Exception {
    SearchService localService =
        serviceThrowing(
            new SolrException(SolrException.ErrorCode.BAD_REQUEST, "undefined field bogus"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> localService.search("test_collection", "bogus:x", null, null, null, null, null));
    assertFalse(
        e.getMessage().contains("undefined field bogus"), "raw Solr message must not be exposed");
    assertTrue(
        e.getMessage()
            .contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted("test_collection")));
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  @Test
  void search_WithUndefinedSortField_ShouldHintGetSchema() throws Exception {
    SearchService localService =
        serviceThrowing(
            new SolrException(
                SolrException.ErrorCode.BAD_REQUEST, "sort param field can't be found: bogus"));
    List<SortClause> sort = List.of(new SortClause("bogus", "asc"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> localService.search("test_collection", "*:*", null, null, sort, null, null));
    assertTrue(
        e.getMessage()
            .contains(SearchService.LIST_FIELDS_HINT_FORMAT.formatted("test_collection")));
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  @Test
  void search_WithQuerySyntaxError_ShouldHintLuceneSyntax() throws Exception {
    SearchService localService =
        serviceThrowing(
            new SolrException(
                SolrException.ErrorCode.BAD_REQUEST,
                "org.apache.solr.search.SyntaxError: Cannot parse 'name:('"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> localService.search("test_collection", "name:(", null, null, null, null, null));
    assertTrue(e.getMessage().contains(SearchService.LUCENE_SYNTAX_HINT));
    assertFalse(e.getMessage().contains("org.apache.solr.search.SyntaxError"));
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  /**
   * A missing collection is matched on the 404 status, not the message — Solr's 404 body is an HTML
   * page, so SolrJ surfaces it as the mime-type mismatch stubbed here, which mentions neither the
   * collection nor "404".
   */
  @Test
  void search_WithNotFoundStatus_ShouldHintListCollections() throws Exception {
    SearchService localService =
        serviceThrowing(
            new SolrException(
                SolrException.ErrorCode.NOT_FOUND,
                "Expected mime type in [application/json, text/plain] but got text/html."));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> localService.search("test_collection", "*:*", null, null, null, null, null));
    assertTrue(e.getMessage().contains(SearchService.LIST_COLLECTIONS_HINT));
    assertFalse(e.getMessage().contains("mime type"));
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  @Test
  void search_WithUnrelatedSolrError_ShouldPropagateUnchanged() throws Exception {
    SearchService localService =
        serviceThrowing(
            new SolrException(SolrException.ErrorCode.SERVER_ERROR, "internal failure"));
    SolrException e =
        assertThrows(
            SolrException.class,
            () -> localService.search("test_collection", null, null, null, null, null, null));
    assertEquals(SolrException.ErrorCode.SERVER_ERROR.code, e.code());
    assertFalse(e.getMessage().contains("internal failure"));
    assertTrue(e.getMessage().contains("operator"));
    assertTrue(e.getMessage().contains("Solr health"));
    assertNull(e.getCause(), "MCP must not unwrap a raw Solr failure");
  }

  @Test
  void search_WithNullQuery_ShouldDefaultToMatchAll() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertEquals("*:*", sentQuery.getQuery());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, null, null, null, null);
    assertNotNull(result);
  }

  /**
   * The score must be requested on every search so Solr populates {@code score} on each document
   * and {@code maxScore} on the response — otherwise both stay {@code null}/absent even though
   * {@link SearchResponse}'s javadoc and the search-collection prompt promise them.
   */
  @Test
  void search_ShouldRequestScoreInFieldList() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertTrue(
                  List.of(sentQuery.getFields().split(",")).contains("score"),
                  () -> "expected fl to request score, got: " + sentQuery.getFields());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", "name:foo", null, null, null, null, null);
    assertNotNull(result);
  }

  @Test
  void search_WithCustomQuery_ShouldUseProvidedQuery() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    String customQuery = "name:\"Spring Boot\"";
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertEquals(customQuery, sentQuery.getQuery());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", customQuery, null, null, null, null, null);
    assertNotNull(result);
  }

  @Test
  void search_WithFilterQueries_ShouldApplyFilters() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    List<String> filterQueries = List.of("genre_s:fantasy", "price:[0 TO 10]");
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertArrayEquals(filterQueries.toArray(), sentQuery.getFilterQueries());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, filterQueries, null, null, null, null);
    assertNotNull(result);
  }

  @Test
  void search_WithFacetFields_ShouldEnableFaceting() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    List<String> facetFields = List.of("genre_s", "author_ss");
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, facetFields, null, null, null);
    assertNotNull(result);
    assertNotNull(result.facets());
  }

  @Test
  void search_WithSortClauses_ShouldApplySorting() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    List<SortClause> sortClauses =
        List.of(new SortClause("price", "asc"), new SortClause("name", "desc"));
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, null, sortClauses, null, null);
    assertNotNull(result);
  }

  @Test
  void search_WithPagination_ShouldApplyStartAndRows() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    Integer start = 10;
    Integer rows = 20;
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertEquals(start, sentQuery.getStart());
              assertEquals(rows, sentQuery.getRows());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, null, null, start, rows);
    assertNotNull(result);
  }

  @Test
  void search_WithAllParameters_ShouldCombineAllOptions() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    String query = "title:Java";
    List<String> filterQueries = List.of("inStock:true");
    List<String> facetFields = List.of("category");
    List<SortClause> sortClauses = List.of(new SortClause("price", "asc"));
    Integer start = 0;
    Integer rows = 10;
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery captured = invocation.getArgument(1);
              assertEquals(query, captured.getQuery());
              assertArrayEquals(filterQueries.toArray(), captured.getFilterQueries());
              assertNotNull(captured.getFacetFields());
              assertEquals(start, captured.getStart());
              assertEquals(rows, captured.getRows());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search(
            "test_collection", query, filterQueries, facetFields, sortClauses, start, rows);
    assertNotNull(result);
  }

  @Test
  void search_WhenSolrThrowsException_ShouldPropagateException() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenThrow(new SolrServerException("Connection error"));
    SearchService localService = new SearchService(mockClient);
    assertThrows(
        SolrServerException.class,
        () -> localService.search("test_collection", null, null, null, null, null, null));
  }

  @Test
  void search_WhenIOException_ShouldPropagateException() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenThrow(new IOException("Network error"));
    SearchService localService = new SearchService(mockClient);
    assertThrows(
        IOException.class,
        () -> localService.search("test_collection", null, null, null, null, null, null));
  }

  @Test
  void search_WithEmptyResults_ShouldReturnEmptyDocumentList() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList emptyDocuments = new SolrDocumentList();
    emptyDocuments.setNumFound(0);
    emptyDocuments.setStart(0);
    when(mockResponse.getResults()).thenReturn(emptyDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", "nonexistent:value", null, null, null, null, null);
    assertNotNull(result);
    assertEquals(0, result.numFound());
    assertTrue(result.documents().isEmpty());
  }

  @Test
  void search_WithNullFilterQueries_ShouldNotApplyFilters() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertNull(sentQuery.getFilterQueries());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, null, null, null, null);
    assertNotNull(result);
  }

  @Test
  void search_WithEmptyFacetFields_ShouldNotEnableFaceting() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
        .thenAnswer(
            invocation -> {
              SolrQuery sentQuery = invocation.getArgument(1);
              assertNull(sentQuery.getFacetFields());
              return mockResponse;
            });
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, List.of(), null, null, null);
    assertNotNull(result);
  }

  @Test
  void searchResponse_ShouldContainAllFields() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentListWithData();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, List.of("genre_s"), null, null, null);
    assertNotNull(result);
    assertEquals(2, result.numFound());
    assertEquals(0, result.start());
    assertNotNull(result.documents());
    assertEquals(2, result.documents().size());
    assertNotNull(result.facets());
    assertFalse(result.facets().isEmpty());
  }

  /**
   * A hit's {@code fields} is a {@link java.util.LinkedHashMap} copy of the {@link SolrDocument},
   * so Solr's field order survives rather than being scrambled by a {@link java.util.HashMap}.
   */
  @Test
  void searchResponse_ShouldPreserveDocumentFieldOrder() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentListWithData();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(null);
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search("test_collection", null, null, null, null, null, null);
    SearchResponse.SearchHit firstHit = result.documents().getFirst();
    assertEquals("book001", firstHit.id());
    assertEquals(
        List.of("name", "author_ss", "price", "genre_s"),
        List.copyOf(firstHit.fields().keySet()),
        "hit field order must match Solr's original order, without the uniqueKey");
  }

  /**
   * Solr already sorts facet buckets by count ({@code facet.sort=count}, the default this service
   * sets); a {@link java.util.HashMap} would discard that order before the LLM ever sees it.
   */
  @Test
  void searchResponse_ShouldPreserveFacetOrder() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    SolrDocumentList mockDocuments = createMockDocumentList();
    when(mockResponse.getResults()).thenReturn(mockDocuments);
    when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    SearchService localService = new SearchService(mockClient);
    SearchResponse result =
        localService.search(
            "test_collection", null, null, List.of("genre_s", "author_ss"), null, null, null);
    assertEquals(
        List.of("genre_s", "author_ss"),
        result.facets().stream().map(SearchResponse.FacetField::field).toList(),
        "facet field order must match Solr's response order");
    assertEquals(
        List.of(
            new SearchResponse.FacetValue("technology", 5),
            new SearchResponse.FacetValue("fiction", 3)),
        result.facets().getFirst().values(),
        "facet value order (count-sorted by Solr) must be preserved");
  }

  private SolrDocumentList createMockDocumentList() {
    SolrDocumentList documents = new SolrDocumentList();
    documents.setNumFound(0);
    documents.setStart(0);
    return documents;
  }

  private SolrDocumentList createMockDocumentListWithData() {
    SolrDocumentList documents = new SolrDocumentList();
    documents.setNumFound(2);
    documents.setStart(0);
    documents.setMaxScore(1.0f);
    SolrDocument doc1 = new SolrDocument();
    doc1.setField("id", "book001");
    doc1.setField("name", "Spring Boot in Action");
    doc1.setField("author_ss", List.of("Craig Walls"));
    doc1.setField("price", 39.99);
    doc1.setField("genre_s", "technology");
    documents.add(doc1);
    SolrDocument doc2 = new SolrDocument();
    doc2.setField("id", "book002");
    doc2.setField("name", "Effective Java");
    doc2.setField("author_ss", List.of("Joshua Bloch"));
    doc2.setField("price", 44.99);
    doc2.setField("genre_s", "technology");
    documents.add(doc2);
    return documents;
  }

  /** Runs a search with the given fields/queryFields and returns the request sent to Solr. */
  private SolrQuery searchAndCapture(String query, List<String> fields, List<String> queryFields)
      throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    QueryResponse mockResponse = mock(QueryResponse.class);
    when(mockResponse.getResults()).thenReturn(createMockDocumentList());
    when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
    new SearchService(mockClient)
        .search("test_collection", query, null, null, null, null, null, fields, queryFields);
    ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(mockClient).query(eq("test_collection"), captor.capture());
    return captor.getValue();
  }

  @Test
  void search_WithoutFieldsOrQueryFields_ShouldSendDefaultParams() throws Exception {
    SolrQuery sentQuery = searchAndCapture("george", null, null);
    assertEquals("*,score", sentQuery.getFields());
    assertNull(sentQuery.get("qf"));
    assertNull(sentQuery.get("defType"));
  }

  @Test
  void search_WithFields_ShouldLimitFlAndKeepScore() throws Exception {
    SolrQuery sentQuery = searchAndCapture(null, List.of("id", " \t", "name"), null);
    assertEquals("id,name,score", sentQuery.getFields());
  }

  @Test
  void search_WithOnlyBlankFields_ShouldReturnAllFields() throws Exception {
    SolrQuery sentQuery = searchAndCapture(null, List.of("", " \n"), null);
    assertEquals("*,score", sentQuery.getFields());
  }

  @Test
  void search_WithQueryFields_ShouldSendQfAndEdismax() throws Exception {
    SolrQuery sentQuery =
        searchAndCapture("george martin", null, List.of("name^2", "", "author_ss"));
    assertEquals("george martin", sentQuery.getQuery());
    assertEquals("name^2 author_ss", sentQuery.get("qf"));
    assertEquals("edismax", sentQuery.get("defType"));
  }

  @Test
  void search_WithOnlyBlankQueryFields_ShouldNotSwitchParser() throws Exception {
    SolrQuery sentQuery = searchAndCapture("george", null, List.of(" \t", ""));
    assertNull(sentQuery.get("qf"));
    assertNull(sentQuery.get("defType"));
  }

  @Test
  void search_WithLocalParamsAndNoQueryFields_ShouldSendQueryUnchanged() throws Exception {
    SolrQuery sentQuery = searchAndCapture("{!edismax qf=name}george", null, null);
    assertEquals("{!edismax qf=name}george", sentQuery.getQuery());
    assertNull(sentQuery.get("defType"));
  }

  @Test
  void search_WithQueryFieldsAndLocalParams_ShouldRejectBeforeQuerying() throws Exception {
    SolrClient mockClient = solrClientWithUniqueKey(ID);
    SearchService localService = new SearchService(mockClient);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                localService.search(
                    "test_collection",
                    "{!edismax qf=name}george",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of("author_ss")));
    assertEquals(SearchService.QUERY_FIELDS_WITH_LOCAL_PARAMS_ERROR, e.getMessage());
    verify(mockClient, never()).query(any(), any(SolrQuery.class));
  }

  private List<FacetField> createMockFacetFields() {
    FacetField genreFacet = new FacetField("genre_s");
    genreFacet.add("technology", 5);
    genreFacet.add("fiction", 3);
    FacetField authorFacet = new FacetField("author_ss");
    authorFacet.add("Craig Walls", 2);
    authorFacet.add("Joshua Bloch", 1);
    return List.of(genreFacet, authorFacet);
  }

  // ===== typed search contract (spec: search) =====

  @Test
  void search_PagedSearch_SendsStartAndRowsAndReportsNumFoundAndStart() throws Exception {
    SolrClient client = solrClientWithUniqueKey(ID);
    QueryResponse response = mock(QueryResponse.class);
    SolrDocumentList page =
        documentList(42, 10, document(Map.of(ID, "doc10")), document(Map.of(ID, "doc11")));
    when(response.getResults()).thenReturn(page);
    when(client.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

    SearchResponse result =
        new SearchService(client).search(COLLECTION, null, null, null, null, 10, 5);

    ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client).query(eq(COLLECTION), captor.capture());
    assertEquals(10, captor.getValue().getStart());
    assertEquals(5, captor.getValue().getRows());
    assertEquals(42, result.numFound());
    assertEquals(10, result.start());
    assertEquals(
        List.of("doc10", "doc11"),
        result.documents().stream().map(SearchResponse.SearchHit::id).toList());
  }

  @Test
  void search_WithoutScores_OmitsMaxScoreAndHitScore() throws Exception {
    SolrDocumentList page = documentList(1, 0, document(Map.of(ID, "doc1", "name", "x")));
    SearchResponse result =
        serviceReturning(ID, page, null).search(COLLECTION, null, null, null, null, null, null);
    assertNull(result.maxScore());
    assertNull(result.documents().getFirst().score());

    JsonContent json = json(result);
    assertThat(json).doesNotHavePath("$.maxScore");
    assertThat(json)
        .extractingPath("$.documents[0]")
        .isEqualTo(Map.of(ID, "doc1", "fields", Map.of("name", "x")));
  }

  @Test
  void search_UniqueKeyNotNamedId_BecomesHitIdAndLeavesFields() throws Exception {
    SolrDocument doc = new SolrDocument();
    doc.setField("name", "Widget");
    doc.setField(SKU, "A-1");
    SearchResponse result =
        serviceReturning(SKU, documentList(1, 0, doc), null)
            .search(COLLECTION, null, null, null, null, null, null);
    SearchResponse.SearchHit hit = result.documents().getFirst();
    assertEquals("A-1", hit.id());
    assertEquals(Map.of("name", "Widget"), hit.fields());
  }

  @Test
  void search_NonStringUniqueKey_IsReportedAsString() throws Exception {
    SearchResponse result =
        serviceReturning(SKU, documentList(1, 0, document(Map.of(SKU, 7L))), null)
            .search(COLLECTION, null, null, null, null, null, null);
    assertEquals("7", result.documents().getFirst().id());
  }

  @Test
  void search_WithScores_MovesScoreOutOfFields() throws Exception {
    SolrDocument doc = new SolrDocument();
    doc.setField(ID, "doc1");
    doc.setField("name", "x");
    doc.setField("score", 1.5f);
    SolrDocumentList page = documentList(1, 0, doc);
    page.setMaxScore(1.5f);
    SearchResponse result =
        serviceReturning(ID, page, null)
            .search(
                COLLECTION, "name:x", null, null, null, null, null, List.of("name", "score"), null);
    SearchResponse.SearchHit hit = result.documents().getFirst();
    assertEquals(1.5f, hit.score());
    assertEquals(1.5f, result.maxScore());
    assertEquals(Map.of("name", "x"), hit.fields());
  }

  @Test
  void search_FieldValuesKeepTheirJsonTypes() throws Exception {
    SolrDocument doc = new SolrDocument();
    doc.setField(ID, "doc1");
    doc.setField("year", 1965L);
    doc.setField("genres", List.of("drama", "war"));
    SearchResponse result =
        serviceReturning(ID, documentList(1, 0, doc), null)
            .search(COLLECTION, null, null, null, null, null, null);

    JsonContent json = json(result);
    // A JSON number, not the string "1965".
    assertThat(json).extractingPath("$.documents[0].fields.year").isEqualTo(1965);
    assertThat(json)
        .extractingPath("$.documents[0].fields.genres")
        .asArray()
        .containsExactly("drama", "war");
  }

  @Test
  void search_FieldsWithoutUniqueKey_AddsItToFl() throws Exception {
    SolrClient client = solrClientWithUniqueKey(SKU);
    QueryResponse response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(documentList(1, 0, document(Map.of(SKU, "A-1"))));
    when(client.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);

    SearchResponse result =
        new SearchService(client)
            .search(COLLECTION, null, null, null, null, null, null, List.of("name"), null);

    ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
    verify(client).query(eq(COLLECTION), captor.capture());
    assertEquals("name,score,sku", captor.getValue().getFields());
    assertEquals("A-1", result.documents().getFirst().id());
  }

  @Test
  void search_FieldsWithUniqueKey_DoesNotRepeatIt() throws Exception {
    SolrQuery sentQuery = searchAndCapture(null, List.of("name", ID), null);
    assertEquals("name,id,score", sentQuery.getFields());
  }

  @Test
  void search_FacetOnOneField_IsAListOfOrderedValueCounts() throws Exception {
    FacetField genre = new FacetField("genre");
    genre.add("drama", 12);
    genre.add("comedy", 7);
    SearchResponse result =
        serviceReturning(ID, documentList(19, 0), List.of(genre))
            .search(COLLECTION, null, null, List.of("genre"), null, null, 0);

    // Strict: the values keep Solr's order, most frequent first.
    assertThat(json(result.facets()))
        .isStrictlyEqualTo(
            """
            [{"field": "genre",
              "values": [{"value": "drama", "count": 12}, {"value": "comedy", "count": 7}]}]
            """);
  }

  @Test
  void search_WithoutFacetFields_ReturnsEmptyFacetList() throws Exception {
    SearchResponse result =
        serviceReturning(ID, documentList(0, 0), null)
            .search(COLLECTION, null, null, null, null, null, null);
    assertEquals(List.of(), result.facets());
    assertThat(json(result)).extractingPath("$.facets").asArray().isEmpty();
  }

  @Test
  void search_RepeatedOnOneCollection_AsksTheSchemaApiOnce() throws Exception {
    SolrClient client = solrClientWithUniqueKey(ID);
    QueryResponse response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(documentList(0, 0));
    when(client.query(any(), any(SolrQuery.class))).thenReturn(response);
    SearchService localService = new SearchService(client);

    localService.search(COLLECTION, null, null, null, null, null, null);
    localService.search(COLLECTION, "name:x", null, null, null, null, null);
    localService.search(COLLECTION, null, null, null, null, 10, 10);
    localService.search("other_collection", null, null, null, null, null, null);

    verify(client, times(1)).request(any(SchemaRequest.UniqueKey.class), eq(COLLECTION));
    verify(client, times(1)).request(any(SchemaRequest.UniqueKey.class), eq("other_collection"));
    verify(client, times(4)).query(any(), any(SolrQuery.class));
  }

  /**
   * A hit without the remembered uniqueKey means the collection was recreated with another schema.
   * The call fails rather than return a hit without an id, and the next search looks the key up
   * again.
   */
  @Test
  void search_HitWithoutUniqueKey_FailsAndLooksTheKeyUpAgain() throws Exception {
    SolrClient client = solrClientWithUniqueKey(SKU);
    QueryResponse response = mock(QueryResponse.class);
    when(response.getResults()).thenReturn(documentList(1, 0, document(Map.of(ID, "doc1"))));
    when(client.query(eq(COLLECTION), any(SolrQuery.class))).thenReturn(response);
    SearchService localService = new SearchService(client);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> localService.search(COLLECTION, null, null, null, null, null, null));
    assertEquals(SearchService.SCHEMA_CHANGED_ERROR, e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw failure");
    assertThrows(
        IllegalStateException.class,
        () -> localService.search(COLLECTION, null, null, null, null, null, null));
    verify(client, times(2)).request(any(SchemaRequest.UniqueKey.class), eq(COLLECTION));
  }

  @Test
  void search_SchemaWithoutUniqueKey_FailsWithoutQuerying() throws Exception {
    SolrClient client = mock(SolrClient.class);
    when(client.request(any(SchemaRequest.UniqueKey.class), any())).thenReturn(new NamedList<>());
    SearchService localService = new SearchService(client);
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> localService.search(COLLECTION, null, null, null, null, null, null));
    assertEquals(SearchService.NO_UNIQUE_KEY_ERROR, e.getMessage());
    assertNull(e.getCause(), "MCP must not unwrap a raw failure");
    verify(client, never()).query(any(), any(SolrQuery.class));
  }

  @Test
  void search_FieldsRenamingAnotherFieldToTheUniqueKey_IsRejectedBeforeQuerying() throws Exception {
    SolrClient client = solrClientWithUniqueKey(ID);
    SearchService localService = new SearchService(client);
    List<String> fields = List.of("name", "id:name_s");
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                localService.search(COLLECTION, null, null, null, null, null, null, fields, null));
    assertEquals(SearchService.RENAME_TO_UNIQUE_KEY_ERROR, e.getMessage());
    verify(client, never()).query(any(), any(SolrQuery.class));
  }

  @Test
  void search_FieldsRenamingTheUniqueKeyItself_IsAllowed() throws Exception {
    SolrQuery sentQuery = searchAndCapture(null, List.of("key:id", "name"), null);
    assertEquals("key:id,name,score,id", sentQuery.getFields());
  }

  @Test
  void search_UniqueKeyLookupOnMissingCollection_HintsListCollections() throws Exception {
    SolrClient client = mock(SolrClient.class);
    when(client.request(any(SchemaRequest.UniqueKey.class), any()))
        .thenThrow(new SolrException(SolrException.ErrorCode.NOT_FOUND, "not found"));
    SearchService localService = new SearchService(client);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> localService.search(COLLECTION, null, null, null, null, null, null));
    assertTrue(e.getMessage().contains(SearchService.LIST_COLLECTIONS_HINT));
    verify(client, never()).query(any(), any(SolrQuery.class));
  }

  @Test
  void searchCollectionPrompt_includesKeyWorkflowSteps() {
    SearchService localService = new SearchService(mock(SolrClient.class));
    String body =
        promptText(
            localService.searchCollectionPrompt("shows", "What sci-fi shows are on Netflix?"));

    assertNotNull(body);
    assertTrue(body.contains("shows"), "Prompt should mention the target collection name");
    assertTrue(
        body.contains("What sci-fi shows are on Netflix?"),
        "Prompt should embed the user question");
    assertTrue(body.contains("list-fields"), "Prompt should reference list-fields tool");
    assertTrue(body.contains("search"), "Prompt should reference search tool");
    assertTrue(body.contains("filterQueries"), "Prompt should explain filterQueries");
    assertTrue(body.contains("queryFields"), "Prompt should explain queryFields");
    assertTrue(body.contains("`fields`"), "Prompt should explain fields");
    assertTrue(body.contains("numFound"), "Prompt should mention numFound interpretation");
  }
}
