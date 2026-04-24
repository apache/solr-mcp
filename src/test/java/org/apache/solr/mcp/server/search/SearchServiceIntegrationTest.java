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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for SearchService using a real Solr instance via
 * Testcontainers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisabledInNativeImage
class SearchServiceIntegrationTest {

	private static final String COLLECTION_NAME = "search_test_" + System.currentTimeMillis();

	@Autowired
	private SearchService searchService;
	@Autowired
	private IndexingService indexingService;
	@Autowired
	private SolrClient solrClient;

	private static boolean initialized = false;

	@BeforeEach
	void setUp() throws Exception {
		if (!initialized) {
			CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(COLLECTION_NAME,
					"_default", 1, 1);
			createRequest.process(solrClient);

			String sampleData = """
					[
					  {
					    "id": "book001",
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

			indexingService.indexJsonDocuments(COLLECTION_NAME, sampleData);
			solrClient.commit(COLLECTION_NAME);
			initialized = true;
		}
	}

	@Test
	void testBasicSearch() throws SolrServerException, IOException {
		SearchResponse result = searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		assertEquals(10, documents.size());
	}

	@Test
	void testSearchWithQuery() throws SolrServerException, IOException {
		SearchResponse result = searchService.search(COLLECTION_NAME, "name:\"Game of Thrones\"", null, null, null,
				null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertEquals(1, documents.size());
		Map<String, Object> book = documents.getFirst();
		List<?> nameField = (List<?>) book.get("name");
		assertNotNull(nameField);
		assertEquals("A Game of Thrones", nameField.getFirst());
	}

	@Test
	void testSearchReturnsAuthor() throws Exception {
		SearchResponse result = searchService.search(COLLECTION_NAME, "author_ss:\"George R.R. Martin\"", null, null,
				null, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertEquals(3, documents.size());
		Map<String, Object> book = documents.getFirst();
		List<?> authorField = (List<?>) book.get("author_ss");
		assertNotNull(authorField);
		assertEquals("George R.R. Martin", authorField.getFirst());
	}

	@Test
	void testSearchWithFacets() throws Exception {
		SearchResponse result = searchService.search(COLLECTION_NAME, null, null, List.of("genre_s"), null, null, null);
		assertNotNull(result);
		Map<String, Map<String, Long>> facets = result.facets();
		assertNotNull(facets);
		assertTrue(facets.containsKey("genre_s"));
	}

	@Test
	void testSearchWithPrice() throws Exception {
		SearchResponse result = searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		Map<String, Object> book = documents.getFirst();
		List<?> priceField = (List<?>) book.get("price");
		assertNotNull(priceField);
		double currentPrice = priceField.isEmpty() ? 0.0 : ((Number) priceField.getFirst()).doubleValue();
		assertTrue(currentPrice > 0);
	}

	@Test
	void testSortByPriceAscending() throws Exception {
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "asc"));
		SearchResponse result = searchService.search(COLLECTION_NAME, null, null, null, sortClauses, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		double previousPrice = 0.0;
		for (Map<String, Object> book : documents) {
			OptionalDouble priceOpt = extractPrice(book);
			if (priceOpt.isEmpty())
				continue;
			double currentPrice = priceOpt.getAsDouble();
			assertTrue(currentPrice >= previousPrice, "Books should be sorted by price in ascending order");
			previousPrice = currentPrice;
		}
	}

	@Test
	void testSortByPriceDescending() throws Exception {
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "desc"));
		SearchResponse result = searchService.search(COLLECTION_NAME, null, null, null, sortClauses, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		double previousPrice = Double.MAX_VALUE;
		for (Map<String, Object> book : documents) {
			OptionalDouble priceOpt = extractPrice(book);
			if (priceOpt.isEmpty())
				continue;
			double currentPrice = priceOpt.getAsDouble();
			assertTrue(currentPrice <= previousPrice, "Books should be sorted by price in descending order");
			previousPrice = currentPrice;
		}
	}

	@Test
	void testSortBySequence() throws Exception {
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "sequence_i", "order", "asc"));
		List<String> filterQueries = List.of("series_s:\"A Song of Ice and Fire\"");
		SearchResponse result = searchService.search(COLLECTION_NAME, null, filterQueries, null, sortClauses, null,
				null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		int previousSequence = 0;
		for (Map<String, Object> book : documents) {
			Number sequenceValue = (Number) book.get("sequence_i");
			assertNotNull(sequenceValue);
			int currentSequence = sequenceValue.intValue();
			assertTrue(currentSequence >= previousSequence, "Books should be sorted by sequence_i in ascending order");
			previousSequence = currentSequence;
		}
	}

	@Test
	void testFilterByGenre() throws Exception {
		List<String> filterQueries = List.of("genre_s:fantasy");
		SearchResponse result = searchService.search(COLLECTION_NAME, null, filterQueries, null, null, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		for (Map<String, Object> book : documents) {
			String genre = (String) book.get("genre_s");
			assertEquals("fantasy", genre, "All books should have genre_s = fantasy");
		}
	}

	@Test
	void testFilterByPriceRange() throws Exception {
		List<String> filterQueries = List.of("price:[6.0 TO 7.0]");
		SearchResponse result = searchService.search(COLLECTION_NAME, null, filterQueries, null, null, null, null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
		assertFalse(documents.isEmpty());
		for (Map<String, Object> book : documents) {
			if (book.get("price") == null)
				continue;
			OptionalDouble priceOpt = extractPrice(book);
			if (priceOpt.isEmpty())
				continue;
			double price = priceOpt.getAsDouble();
			assertTrue(price >= 6.0 && price <= 7.0, "All books should have price between 6.0 and 7.0");
		}
	}

	@Test
	void testCombinedSortingAndFiltering() throws Exception {
		List<Map<String, String>> sortClauses = List.of(Map.of("item", "price", "order", "desc"));
		List<String> filterQueries = List.of("genre_s:fantasy");
		SearchResponse result = searchService.search(COLLECTION_NAME, null, filterQueries, null, sortClauses, null,
				null);
		assertNotNull(result);
		List<Map<String, Object>> documents = result.documents();
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
			assertTrue(currentPrice <= previousPrice, "Books should be sorted by price in descending order");
			previousPrice = currentPrice;
		}
	}

	@Test
	void testPagination() throws Exception {
		SearchResponse allResults = searchService.search(COLLECTION_NAME, null, null, null, null, null, null);
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
		List<String> firstPageIds = getDocumentIds(firstPage.documents());
		List<String> secondPageIds = getDocumentIds(secondPage.documents());
		for (String id : firstPageIds) {
			assertFalse(secondPageIds.contains(id), "Second page should not contain documents from first page");
		}
	}

	@Test
	void testSpecialCharactersInQuery() throws Exception {
		String specialJson = """
				[
				  {
				    "id": "special001",
				    "title": "Book with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /",
				    "author_ss": ["Special Author (with parentheses)"],
				    "description": "This is a test document with special characters: & + - ! ( ) { } [ ] ^ \\" ~ * ? : \\\\ /"
				  }
				]
				""";
		indexingService.indexJsonDocuments(COLLECTION_NAME, specialJson);
		solrClient.commit(COLLECTION_NAME);
		String query = "id:special001";
		SearchResponse result = searchService.search(COLLECTION_NAME, query, null, null, null, null, null);
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

	private List<String> getDocumentIds(List<Map<String, Object>> documents) {
		List<String> ids = new ArrayList<>();
		for (Map<String, Object> doc : documents) {
			Object idObj = doc.get("id");
			if (idObj instanceof List) {
				ids.add(((List<?>) idObj).getFirst().toString());
			} else if (idObj != null) {
				ids.add(idObj.toString());
			}
		}
		return ids;
	}
}
