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
			assertArrayEquals(new String[]{"{!edismax v=$fq0}", "{!edismax v=$fq1}"}, q.getFilterQueries());
			assertEquals("genre_s:fantasy", q.get("fq0"));
			assertEquals("price:[0 TO 10]", q.get("fq1"));
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
			assertArrayEquals(new String[]{"{!edismax v=$fq0}"}, captured.getFilterQueries());
			assertEquals("inStock:true", captured.get("fq0"));
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

	// --- Filter-query injection tests (CWE-943) -----------------------------

	@Test
	void search_WithPlainFilterQuery_ShouldBindToDereferencedParameter() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertArrayEquals(new String[]{"{!edismax v=$fq0}"}, q.getFilterQueries());
			assertEquals("status:active", q.get("fq0"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, List.of("status:active"), null, null, null,
				null);
		assertNotNull(result);
	}

	@Test
	void search_WithFilterQueryXmlParserInjection_ShouldBindIntoDereferencedParameter() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		String malicious = "{!xmlparser v='<root/>'}";
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertArrayEquals(new String[]{"{!edismax v=$fq0}"}, q.getFilterQueries());
			assertEquals(malicious, q.get("fq0"));
			// The malicious string must NOT appear as an actual fq value.
			for (String fq : q.getFilterQueries()) {
				assertNotEquals(malicious, fq);
			}
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, List.of(malicious), null, null, null,
				null);
		assertNotNull(result);
	}

	@Test
	void search_WithFilterQueryJoinInjection_ShouldBindIntoDereferencedParameter() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		String malicious = "{!join from=id fromIndex=other to=id}*:*";
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertArrayEquals(new String[]{"{!edismax v=$fq0}"}, q.getFilterQueries());
			assertEquals(malicious, q.get("fq0"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, List.of(malicious), null, null, null,
				null);
		assertNotNull(result);
	}

	@Test
	void search_WithFilterQueryFrangeInjection_ShouldBindIntoDereferencedParameter() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		String malicious = "{!frange l=0 u=100}price";
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertArrayEquals(new String[]{"{!edismax v=$fq0}"}, q.getFilterQueries());
			assertEquals(malicious, q.get("fq0"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, List.of(malicious), null, null, null,
				null);
		assertNotNull(result);
	}

	@Test
	void search_WithBlankFilterQuery_ShouldSkip() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertNull(q.getFilterQueries());
			assertNull(q.get("fq0"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, List.of(" "), null, null, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithMultipleFilterQueries_ShouldBindEachToOwnParameter() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertArrayEquals(new String[]{"{!edismax v=$fq0}", "{!edismax v=$fq1}"}, q.getFilterQueries());
			assertEquals("a:1", q.get("fq0"));
			assertEquals("b:2", q.get("fq1"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, List.of("a:1", "b:2"), null, null, null,
				null);
		assertNotNull(result);
	}

	// --- Sort-clause validation tests ---------------------------------------

	@Test
	void search_WithValidSortField_ShouldApplySorting() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals("title asc", q.get("sort"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null,
				List.of(Map.of("item", "title", "order", "asc")), null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithDottedSortField_ShouldApplySorting() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals("nested.field desc", q.get("sort"));
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null,
				List.of(Map.of("item", "nested.field", "order", "desc")), null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithFunctionQuerySortInjection_ShouldThrow() {
		SolrClient mockClient = mock(SolrClient.class);
		SearchService localService = new SearchService(mockClient);
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "if(rord(x),1,0)", "order", "asc"));
		assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", null, null, null, sortClauses, null, null));
	}

	@Test
	void search_WithBraceSortInjection_ShouldThrow() {
		SolrClient mockClient = mock(SolrClient.class);
		SearchService localService = new SearchService(mockClient);
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "{!func}foo", "order", "asc"));
		assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", null, null, null, sortClauses, null, null));
	}

	@Test
	void search_WithValSortInjection_ShouldThrow() {
		SolrClient mockClient = mock(SolrClient.class);
		SearchService localService = new SearchService(mockClient);
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "_val_:expensive", "order", "asc"));
		assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", null, null, null, sortClauses, null, null));
	}

	@Test
	void search_WithInvalidSortOrder_ShouldThrow() {
		SolrClient mockClient = mock(SolrClient.class);
		SearchService localService = new SearchService(mockClient);
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "title", "order", "hack"));
		assertThrows(IllegalArgumentException.class,
				() -> localService.search("test_collection", null, null, null, sortClauses, null, null));
	}

	// --- rows / start clamping tests (CWE-1284) -----------------------------

	@Test
	void search_WithExcessiveRows_ShouldClampToMax() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals(Integer.valueOf(1000), q.getRows());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, null, null, 2_000_000_000);
		assertNotNull(result);
	}

	@Test
	void search_WithNullRows_ShouldNotSetRowsParameter() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertNull(q.getRows());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, null, null, null);
		assertNotNull(result);
	}

	@Test
	void search_WithExcessiveStart_ShouldClampToMax() throws Exception {
		SolrClient mockClient = mock(SolrClient.class);
		QueryResponse mockResponse = mock(QueryResponse.class);
		SolrDocumentList mockDocuments = createMockDocumentList();
		when(mockResponse.getResults()).thenReturn(mockDocuments);
		when(mockResponse.getFacetFields()).thenReturn(null);
		when(mockClient.query(eq("test_collection"), any(SolrQuery.class))).thenAnswer(invocation -> {
			SolrQuery q = invocation.getArgument(1);
			assertEquals(Integer.valueOf(100_000), q.getStart());
			return mockResponse;
		});
		SearchService localService = new SearchService(mockClient);
		SearchResponse result = localService.search("test_collection", null, null, null, null, 200_000, null);
		assertNotNull(result);
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
}
