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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

/**
 * Unit tests for SearchService with mocked SolrClient.
 */
@DisabledInNativeImage
class SearchServiceTest {

	@Test
	void constructor_ShouldInitializeWithSolrClient() {
		SearchService localService = new SearchService(mock(SolrClient.class));
		assertNotNull(localService);
	}

	/*
	 * These stub Solr's error text rather than observe it, so they can only show
	 * that a matching message produces a hint — never that Solr still emits such a
	 * message. The fixture strings below are verbatim samples captured from a real
	 * Solr 9.9; SearchServiceIntegrationTest is what keeps them honest.
	 */

	private static SearchService serviceThrowing(SolrException e) throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenThrow(e);
		return new SearchService(mockClient);
	}

	@Test
	void search_WithUndefinedField_ShouldHintGetSchema() throws Exception {
		SearchService localService = serviceThrowing(
				new SolrException(SolrException.ErrorCode.BAD_REQUEST, "undefined field bogus"));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", "bogus:x", null, null, null, null, null));
		assertTrue(e.getMessage().contains("undefined field bogus"), "original Solr message must be preserved");
		assertTrue(e.getMessage().contains(SearchService.GET_SCHEMA_HINT_FORMAT.formatted("test_collection")));
	}

	@Test
	void search_WithUndefinedSortField_ShouldHintGetSchema() throws Exception {
		SearchService localService = serviceThrowing(
				new SolrException(SolrException.ErrorCode.BAD_REQUEST, "sort param field can't be found: bogus"));
		List<Map<String, String>> sort = List
				.of(Map.of(SearchService.SORT_ITEM, "bogus", SearchService.SORT_ORDER, "asc"));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", "*:*", null, null, sort, null, null));
		assertTrue(e.getMessage().contains(SearchService.GET_SCHEMA_HINT_FORMAT.formatted("test_collection")));
	}

	@Test
	void search_WithQuerySyntaxError_ShouldHintLuceneSyntax() throws Exception {
		SearchService localService = serviceThrowing(new SolrException(SolrException.ErrorCode.BAD_REQUEST,
				"org.apache.solr.search.SyntaxError: Cannot parse 'name:('"));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", "name:(", null, null, null, null, null));
		assertTrue(e.getMessage().contains(SearchService.LUCENE_SYNTAX_HINT));
	}

	/**
	 * A missing collection is matched on the 404 status, not the message — Solr's
	 * 404 body is an HTML page, so SolrJ surfaces it as the mime-type mismatch
	 * stubbed here, which mentions neither the collection nor "404".
	 */
	@Test
	void search_WithNotFoundStatus_ShouldHintListCollections() throws Exception {
		SearchService localService = serviceThrowing(new SolrException(SolrException.ErrorCode.NOT_FOUND,
				"Expected mime type in [application/json, text/plain] but got text/html."));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", "*:*", null, null, null, null, null));
		assertTrue(e.getMessage().contains(SearchService.LIST_COLLECTIONS_HINT));
	}

	@Test
	void search_WithUnrelatedSolrError_ShouldPropagateUnchanged() throws Exception {
		SearchService localService = serviceThrowing(
				new SolrException(SolrException.ErrorCode.SERVER_ERROR, "internal failure"));
		SolrException e = assertThrows(SolrException.class,
				() -> localService.search("test_collection", null, null, null, null, null, null));
		assertTrue(e.getMessage().contains("internal failure"));
		assertFalse(e.getMessage().contains("Hint:"));
	}

	@Test
	void search_WithNullQuery_ShouldDefaultToMatchAll() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals("*:*", q.getQuery());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, null, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithCustomQuery_ShouldUseProvidedQuery() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		String customQuery = "name:\"Spring Boot\"";
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals(customQuery, q.getQuery());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", customQuery, null, null, null, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithFilterQueries_ShouldApplyFilters() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		List<String> filterQueries = List.of("genre_s:fantasy", "price:[0 TO 10]");
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertArrayEquals(filterQueries.toArray(), q.getFilterQueries());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, filterQueries, null, null, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithFacetFields_ShouldEnableFaceting() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		List<String> facetFields = List.of("genre_s", "author_ss");
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> mockResponse);
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, facetFields, null, null, null);
		assertNotNull(result);
		assertNotNull(result.facets());
	}

	@Test
	void search_WithSortClauses_ShouldApplySorting() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "asc"),
				Map.of("item", "name", "order", "desc"));
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> mockResponse);
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, sortClauses, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithPagination_ShouldApplyStartAndRows() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		Integer start = 10;
		Integer rows = 20;
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals(start, q.getStart());
			assertEquals(rows, q.getRows());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, null, start, rows);
		assertNotNull(result);
	}

	@Test
	void search_WithAllParameters_ShouldCombineAllOptions() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		String query = "title:Java";
		List<String> filterQueries = List.of("inStock:true");
		List<String> facetFields = List.of("category");
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "asc"));
		Integer start = 0;
		Integer rows = 10;
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery captured = invocation.getArgument(1);
			assertEquals(query, captured.getQuery());
			assertArrayEquals(filterQueries.toArray(), captured.getFilterQueries());
			assertNotNull(captured.getFacetFields());
			assertEquals(start, captured.getStart());
			assertEquals(rows, captured.getRows());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", query, filterQueries, facetFields, sortClauses,
				start, rows);
		assertNotNull(result);
	}

	@Test
	void search_WhenSolrThrowsException_ShouldPropagateException() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class)))
				.thenThrow(new SolrServerException("Connection error"));
		SearchService localService = new SearchService(mockClient);
		assertThrows(SolrServerException.class,
				() -> localService.search("test_collection", null, null, null, null, null, null));
	}

	@Test
	void search_WhenIOException_ShouldPropagateException() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenThrow(new IOException("Network error"));
		SearchService localService = new SearchService(mockClient);
		assertThrows(IOException.class,
				() -> localService.search("test_collection", null, null, null, null, null, null));
	}

	@Test
	void search_WithEmptyResults_ShouldReturnEmptyDocumentList() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList emptyDocuments = new SolrDocumentList();
		emptyDocuments.setNumFound(0);
		emptyDocuments.setStart(0);
		when(mockResponse.getResults()).thenReturn(emptyDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", "nonexistent:value", null, null, null, null,
				null);
		assertNotNull(result);
		assertEquals(0, result.numFound());
		assertTrue(result.documents().isEmpty());
	}

	@Test
	void search_WithNullFilterQueries_ShouldNotApplyFilters() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertNull(q.getFilterQueries());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, null, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithEmptyFacetFields_ShouldNotEnableFaceting() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertNull(q.getFacetFields());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, List.of(), null, null, null);
		assertNotNull(result);
	}

	@Test
	void searchResponse_ShouldContainAllFields() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentListWithData();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(createMockFacetFields());
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenReturn(mockResponse);
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, List.of("genre_s"), null, null,
				null);
		assertNotNull(result);
		assertEquals(2, result.numFound());
		assertEquals(0, result.start());
		assertNotNull(result.documents());
		assertEquals(2, result.documents().size());
		assertNotNull(result.facets());
		assertFalse(result.facets().isEmpty());
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

	private List<FacetField> createMockFacetFields() {
		FacetField genreFacet = new FacetField("genre_s");
		genreFacet.add("technology", 5);
		genreFacet.add("fiction", 3);
		FacetField authorFacet = new FacetField("author_ss");
		authorFacet.add("Craig Walls", 2);
		authorFacet.add("Joshua Bloch", 1);
		return List.of(genreFacet, authorFacet);
	}

	@Test
	void searchCollectionPrompt_includesKeyWorkflowSteps() {
		SearchService localService = new SearchService(mock(SolrClient.class));
		String body = localService.searchCollectionPrompt("shows", "What sci-fi shows are on Netflix?");

		assertNotNull(body);
		assertTrue(body.contains("shows"), "Prompt should mention the target collection name");
		assertTrue(body.contains("What sci-fi shows are on Netflix?"), "Prompt should embed the user question");
		assertTrue(body.contains("get-schema"), "Prompt should reference get-schema tool");
		assertTrue(body.contains("search"), "Prompt should reference search tool");
		assertTrue(body.contains("filterQueries"), "Prompt should explain filterQueries");
		assertTrue(body.contains("numFound"), "Prompt should mention numFound interpretation");
	}
}
