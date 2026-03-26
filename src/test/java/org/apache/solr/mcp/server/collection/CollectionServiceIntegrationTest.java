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

import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
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
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectionServiceIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(CollectionServiceIntegrationTest.class);

	private static final String TEST_COLLECTION = "test_collection";

	private static final int DOC_COUNT = 50;

	@Autowired
	private CollectionService collectionService;

	@Autowired
	private SolrClient solrClient;

	@BeforeAll
	void setupCollectionWithData() throws Exception {
		// 1. Create collection
		CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1).process(solrClient);
		log.debug("Test collection created: {}", TEST_COLLECTION);

		// 2. Index documents so metrics have real data
		for (int i = 0; i < DOC_COUNT; i++) {
			SolrInputDocument doc = new SolrInputDocument();
			doc.addField("id", "doc-" + i);
			doc.addField("title_s", "Document " + i);
			doc.addField("category_s", (i % 2 == 0) ? "even" : "odd");
			doc.addField("count_i", i);
			solrClient.add(TEST_COLLECTION, doc);
		}
		solrClient.commit(TEST_COLLECTION);

		// 3. Run several queries to populate query result cache and handler stats
		for (int i = 0; i < 5; i++) {
			solrClient.query(TEST_COLLECTION, new SolrQuery("*:*").setRows(10));
			solrClient.query(TEST_COLLECTION, new SolrQuery("title_s:Document").setRows(5));
			solrClient.query(TEST_COLLECTION, new SolrQuery("*:*").addFilterQuery("category_s:even").setRows(10));
		}
		log.debug("Indexed {} documents and ran warm-up queries", DOC_COUNT);
	}

	@Test
	void testListCollections() {
		List<String> collections = collectionService.listCollections();

		log.debug("Collections: {}", collections);

		assertNotNull(collections);
		assertFalse(collections.isEmpty());

		boolean testCollectionExists = collections.contains(TEST_COLLECTION)
				|| collections.stream().anyMatch(col -> col.startsWith(TEST_COLLECTION + "_shard"));
		assertTrue(testCollectionExists,
				"Collections should contain " + TEST_COLLECTION + " (found: " + collections + ")");

		assertEquals(collections.size(), collections.stream().distinct().count(), "Collection names should be unique");
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
		assertEquals((long) DOC_COUNT, queryStats.totalResults(), "totalResults should match indexed document count");
		assertEquals(0L, queryStats.start());

		// Cache stats should be present after warm-up queries
		assertNotNull(metrics.cacheStats(), "Cache stats should not be null after queries ran");
		CacheStats cacheStats = metrics.cacheStats();
		assertNotNull(cacheStats.queryResultCache());
		assertTrue(cacheStats.queryResultCache().lookups() > 0, "Query result cache should have lookups after queries");
		assertNotNull(cacheStats.documentCache());
		assertTrue(cacheStats.documentCache().lookups() > 0, "Document cache should have lookups after queries");
		assertNotNull(cacheStats.filterCache());
		assertTrue(cacheStats.filterCache().lookups() > 0, "Filter cache should have lookups after filter queries");

		// Handler stats should reflect actual request counts
		assertNotNull(metrics.handlerStats(), "Handler stats should not be null after queries ran");
		HandlerStats handlerStats = metrics.handlerStats();
		assertNotNull(handlerStats.selectHandler());
		assertTrue(handlerStats.selectHandler().requests() > 0, "Select handler should have processed requests");
		assertNotNull(handlerStats.updateHandler());
		assertTrue(handlerStats.updateHandler().requests() > 0,
				"Update handler should have processed requests from indexing");
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

		assertEquals((long) DOC_COUNT, status.totalDocuments(), "Health check should report indexed document count");

		assertNotNull(status.lastChecked());
		assertTrue(System.currentTimeMillis() - status.lastChecked().getTime() < 5000);
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
		assertNull(collectionService.extractCollectionName(null));
		assertEquals("", collectionService.extractCollectionName(""));
	}

	@Test
	void testGetCacheMetrics_afterQueries() {
		CacheStats cacheStats = collectionService.getCacheMetrics(TEST_COLLECTION);

		assertNotNull(cacheStats, "Cache stats should not be null after warm-up queries");

		// Query result cache: warm-up queries should have generated lookups
		CacheInfo qrc = cacheStats.queryResultCache();
		assertNotNull(qrc);
		assertTrue(qrc.lookups() > 0, "queryResultCache lookups should be positive after queries");
		assertNotNull(qrc.hitratio());
		assertNotNull(qrc.size());

		// Document cache: reading documents populates this cache
		CacheInfo dc = cacheStats.documentCache();
		assertNotNull(dc);
		assertTrue(dc.lookups() > 0, "documentCache lookups should be positive after queries");

		// Filter cache: the filter queries we ran should generate lookups
		CacheInfo fc = cacheStats.filterCache();
		assertNotNull(fc);
		assertTrue(fc.lookups() > 0, "filterCache lookups should be positive after filter queries");
	}

	@Test
	void testGetHandlerMetrics_afterQueriesAndIndexing() {
		HandlerStats handlerStats = collectionService.getHandlerMetrics(TEST_COLLECTION);

		assertNotNull(handlerStats, "Handler stats should not be null after activity");

		// Select handler: warm-up queries should have driven request counts > 0
		HandlerInfo select = handlerStats.selectHandler();
		assertNotNull(select);
		assertTrue(select.requests() > 0, "Select handler requests should be positive after queries");
		assertNotNull(select.errors());
		assertNotNull(select.timeouts());

		// Update handler: indexing 50 docs should have driven request counts > 0
		HandlerInfo update = handlerStats.updateHandler();
		assertNotNull(update);
		assertTrue(update.requests() > 0, "Update handler requests should be positive after indexing");
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
		boolean exists = collections.contains(name)
				|| collections.stream().anyMatch(col -> col.startsWith(name + "_shard"));
		assertTrue(exists, "Newly created collection should appear in list (found: " + collections + ")");
	}
}
