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
package org.apache.solr.mcp.server.metadata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CollectionServiceIntegrationTest {

	private static final String TEST_COLLECTION = "test_collection";
	@Autowired
	private CollectionService collectionService;
	@Autowired
	private SolrClient solrClient;
	private static boolean initialized = false;

	@BeforeEach
	void setupCollection() throws Exception {

		if (!initialized) {
			CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(TEST_COLLECTION,
					"_default", 1, 1);
			createRequest.process(solrClient);

			CollectionAdminRequest.List listRequest = new CollectionAdminRequest.List();
			listRequest.process(solrClient);

			initialized = true;
		}
	}

	@Test
	void testListCollections() {
		List<String> collections = collectionService.listCollections();

		assertNotNull(collections, "Collections list should not be null");
		assertFalse(collections.isEmpty(), "Collections list should not be empty");

		boolean testCollectionExists = collections.contains(TEST_COLLECTION)
				|| collections.stream().anyMatch(col -> col.startsWith(TEST_COLLECTION + "_shard"));
		assertTrue(testCollectionExists,
				"Collections should contain the test collection: " + TEST_COLLECTION + " (found: " + collections + ")");

		for (String collection : collections) {
			assertNotNull(collection, "Collection name should not be null");
			assertFalse(collection.trim().isEmpty(), "Collection name should not be empty");
		}

		assertEquals(collections.size(), collections.stream().distinct().count(), "Collection names should be unique");
	}

	@Test
	void testGetCollectionStats() throws Exception {
		SolrMetrics metrics = collectionService.getCollectionStats(TEST_COLLECTION);

		assertNotNull(metrics, "Collection stats should not be null");
		assertNotNull(metrics.timestamp(), "Timestamp should not be null");

		// Verify index stats
		assertNotNull(metrics.indexStats(), "Index stats should not be null");
		IndexStats indexStats = metrics.indexStats();
		assertNotNull(indexStats.numDocs(), "Number of documents should not be null");
		assertTrue(indexStats.numDocs() >= 0, "Number of documents should be non-negative");

		// Verify query stats
		assertNotNull(metrics.queryStats(), "Query stats should not be null");
		QueryStats queryStats = metrics.queryStats();
		assertNotNull(queryStats.queryTime(), "Query time should not be null");
		assertTrue(queryStats.queryTime() >= 0, "Query time should be non-negative");
		assertNotNull(queryStats.totalResults(), "Total results should not be null");
		assertTrue(queryStats.totalResults() >= 0, "Total results should be non-negative");
		assertNotNull(queryStats.start(), "Start should not be null");
		assertTrue(queryStats.start() >= 0, "Start should be non-negative");

		// Verify timestamp is recent (within last 10 seconds)
		long currentTime = System.currentTimeMillis();
		long timestampTime = metrics.timestamp().getTime();
		assertTrue(currentTime - timestampTime < 10000, "Timestamp should be recent (within 10 seconds)");

		// Cache stats via Metrics API - should always be available
		assertNotNull(metrics.cacheStats(), "Cache stats should not be null");
		CacheStats cacheStats = metrics.cacheStats();
		assertNotNull(cacheStats.queryResultCache(), "Query result cache should be present");
		assertNotNull(cacheStats.documentCache(), "Document cache should be present");
		assertNotNull(cacheStats.filterCache(), "Filter cache should be present");

		CacheInfo qrc = cacheStats.queryResultCache();
		assertNotNull(qrc.lookups(), "Query result cache lookups should not be null");
		assertTrue(qrc.lookups() >= 0, "Query result cache lookups should be non-negative");
		assertNotNull(qrc.size(), "Query result cache size should not be null");
		assertTrue(qrc.size() >= 0, "Query result cache size should be non-negative");

		// Handler stats via Metrics API - should always be available
		assertNotNull(metrics.handlerStats(), "Handler stats should not be null");
		HandlerStats handlerStats = metrics.handlerStats();
		assertNotNull(handlerStats.selectHandler(), "Select handler should be present");
		assertNotNull(handlerStats.updateHandler(), "Update handler should be present");

		HandlerInfo selectHandler = handlerStats.selectHandler();
		assertNotNull(selectHandler.requests(), "Select handler requests should not be null");
		assertTrue(selectHandler.requests() >= 0, "Select handler requests should be non-negative");
	}

	@Test
	void testCheckHealthHealthy() {
		SolrHealthStatus status = collectionService.checkHealth(TEST_COLLECTION);

		assertNotNull(status, "Health status should not be null");
		assertTrue(status.isHealthy(), "Collection should be healthy");

		assertNotNull(status.responseTime(), "Response time should not be null");
		assertTrue(status.responseTime() >= 0, "Response time should be non-negative");
		assertTrue(status.responseTime() < 30000, "Response time should be reasonable (< 30 seconds)");

		assertNotNull(status.totalDocuments(), "Total documents should not be null");
		assertTrue(status.totalDocuments() >= 0, "Total documents should be non-negative");

		assertNotNull(status.lastChecked(), "Last checked timestamp should not be null");
		long currentTime = System.currentTimeMillis();
		long lastCheckedTime = status.lastChecked().getTime();
		assertTrue(currentTime - lastCheckedTime < 5000,
				"Last checked timestamp should be very recent (within 5 seconds)");

		assertNull(status.errorMessage(), "Error message should be null for healthy collection");
	}

	@Test
	void testCheckHealthUnhealthy() {
		String nonExistentCollection = "non_existent_collection";
		SolrHealthStatus status = collectionService.checkHealth(nonExistentCollection);

		assertNotNull(status, "Health status should not be null");
		assertFalse(status.isHealthy(), "Collection should not be healthy");

		assertNotNull(status.lastChecked(), "Last checked timestamp should not be null");
		long currentTime = System.currentTimeMillis();
		long lastCheckedTime = status.lastChecked().getTime();
		assertTrue(currentTime - lastCheckedTime < 5000,
				"Last checked timestamp should be very recent (within 5 seconds)");

		assertNotNull(status.errorMessage(), "Error message should not be null for unhealthy collection");
		assertFalse(status.errorMessage().trim().isEmpty(),
				"Error message should not be empty for unhealthy collection");

		assertNull(status.responseTime(), "Response time should be null for unhealthy collection");
		assertNull(status.totalDocuments(), "Total documents should be null for unhealthy collection");
	}

	@Test
	void testCollectionNameExtraction() {
		assertEquals(TEST_COLLECTION, collectionService.extractCollectionName(TEST_COLLECTION),
				"Regular collection name should be returned as-is");

		assertEquals("films", collectionService.extractCollectionName("films_shard1_replica_n1"),
				"Shard name should be extracted to base collection name");

		assertEquals("products", collectionService.extractCollectionName("products_shard2_replica_n3"),
				"Complex shard name should be extracted correctly");

		assertNull(collectionService.extractCollectionName(null), "Null input should return null");

		assertEquals("", collectionService.extractCollectionName(""), "Empty string should return empty string");
	}
}
