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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.search.SearchResponse;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test that exercises the full create, index, and search
 * workflow using the DevNexus 2026 conference schedule sample data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConferenceEndToEndIntegrationTest {

	private static final String COLLECTION = "conferences";

	private static final int TOTAL_SESSIONS = 116;

	@Autowired
	private CollectionService collectionService;

	@Autowired
	private IndexingService indexingService;

	@Autowired
	private SearchService searchService;

	@BeforeAll
	void createAndIndexConferences() throws Exception {
		CollectionCreationResult result = collectionService.createCollection(COLLECTION, null, null, null);
		assertTrue(result.success(), "Collection creation should succeed: " + result.message());

		String json = Files.readString(Path.of("mydata/devnexus-2026.json"));
		indexingService.indexJsonDocuments(COLLECTION, json);
	}

	@Test
	@Order(1)
	void collectionAppearsInList() {
		List<String> collections = collectionService.listCollections();
		boolean found = collections.contains(COLLECTION)
				|| collections.stream().anyMatch(c -> c.startsWith(COLLECTION + "_shard"));
		assertTrue(found, "conferences collection should be listed, found: " + collections);
	}

	@Test
	@Order(2)
	void collectionHealthIsHealthy() {
		SolrHealthStatus health = collectionService.checkHealth(COLLECTION);
		assertTrue(health.isHealthy(), "conferences collection should be healthy");
		assertEquals(Long.valueOf(TOTAL_SESSIONS), health.totalDocuments(), "Should contain 116 conference sessions");
	}

	@Test
	@Order(3)
	void searchAllDocumentsReturns116() throws Exception {
		SearchResponse response = searchService.search(COLLECTION, "*:*", null, null, null, 0, 0);
		assertEquals(TOTAL_SESSIONS, response.numFound(), "Total results should be 116");
	}

	@Test
	@Order(4)
	void searchByTrackReturnsFilteredResults() throws Exception {
		SearchResponse response = searchService.search(COLLECTION, "*:*", List.of("track:Workshop"), null, null, 0, 10);
		assertTrue(response.numFound() > 0, "Should find workshop sessions");
		for (Map<String, Object> doc : response.documents()) {
			Object track = doc.get("track");
			String trackValue = track instanceof List ? ((List<?>) track).get(0).toString() : track.toString();
			assertEquals("Workshop", trackValue, "All results should be workshops");
		}
	}

	@Test
	@Order(5)
	void searchByKeywordFindsMatchingSessions() throws Exception {
		SearchResponse response = searchService.search(COLLECTION, "title:Spring", null, null, null, 0, 50);
		assertTrue(response.numFound() > 0, "Should find sessions with 'Spring' in the title");
		for (Map<String, Object> doc : response.documents()) {
			Object title = doc.get("title");
			String titleValue = title instanceof List ? ((List<?>) title).get(0).toString() : title.toString();
			assertTrue(titleValue.toLowerCase().contains("spring"), "Title should contain 'spring': " + titleValue);
		}
	}

	@Test
	@Order(6)
	void facetByIdReturnsResults() throws Exception {
		// Facet on 'id' which is always a string type in Solr's _default configset;
		// schema-less text fields (track, day) are tokenized and return empty facets.
		SearchResponse response = searchService.search(COLLECTION, "*:*", null, List.of("id"), null, 0, 0);
		assertNotNull(response.facets(), "Facets should not be null");
		assertTrue(response.facets().containsKey("id"), "Should have id facet");

		Map<String, Long> idFacets = response.facets().get("id");
		assertFalse(idFacets.isEmpty(), "Id facets should not be empty");
		// Solr default facet.limit is 100, so we get at most 100 entries
		assertTrue(idFacets.size() >= 100, "Should have facet entries for sessions");
	}

	@Test
	@Order(7)
	void paginationWorks() throws Exception {
		SearchResponse page1 = searchService.search(COLLECTION, "*:*", null, null, null, 0, 10);
		SearchResponse page2 = searchService.search(COLLECTION, "*:*", null, null, null, 10, 10);

		assertEquals(10, page1.documents().size(), "Page 1 should have 10 documents");
		assertEquals(10, page2.documents().size(), "Page 2 should have 10 documents");
		assertEquals(TOTAL_SESSIONS, page1.numFound(), "Total should be 116 across pages");

		Object id1 = page1.documents().get(0).get("id");
		Object id2 = page2.documents().get(0).get("id");
		assertNotEquals(id1, id2, "Pages should return different documents");
	}

	@Test
	@Order(8)
	void collectionStatsShowIndexedDocuments() throws Exception {
		SolrMetrics metrics = collectionService.getCollectionStats(COLLECTION);
		assertNotNull(metrics, "Metrics should not be null");
		assertNotNull(metrics.indexStats(), "Index stats should not be null");
		assertEquals(Integer.valueOf(TOTAL_SESSIONS), metrics.indexStats().numDocs(),
				"Should report 116 indexed documents");
	}

}
