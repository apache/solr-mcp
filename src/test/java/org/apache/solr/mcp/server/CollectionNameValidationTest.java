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
package org.apache.solr.mcp.server;

import static org.apache.solr.mcp.server.util.ToolArguments.BLANK_COLLECTION_NAME_ERROR;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.apache.solr.mcp.server.schema.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that every MCP tool method taking a collection name rejects a
 * missing, empty, or whitespace-only value with the same message.
 *
 * <p>
 * The services are exercised directly with mocked collaborators: validation
 * must happen before any Solr call, so a client that omits the argument gets an
 * actionable message rather than an opaque downstream failure. Asserting
 * against {@code BLANK_COLLECTION_NAME_ERROR} rather than a copied string
 * literal is deliberate — it is what keeps the four services from drifting
 * apart.
 */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class CollectionNameValidationTest {

	private static final String BLANK = "   ";

	@Mock
	private SolrClient solrClient;

	@Mock
	private IndexingDocumentCreator indexingDocumentCreator;

	private CollectionService collectionService;
	private IndexingService indexingService;
	private SchemaService schemaService;
	private SearchService searchService;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper();
		collectionService = new CollectionService(solrClient, objectMapper);
		indexingService = new IndexingService(solrClient, indexingDocumentCreator);
		schemaService = new SchemaService(solrClient, objectMapper);
		searchService = new SearchService(solrClient);
	}

	private static void assertRejectsBlankCollection(ThrowingCallable withNull, ThrowingCallable withBlank) {
		assertThatThrownBy(withNull).isInstanceOf(IllegalArgumentException.class)
				.hasMessage(BLANK_COLLECTION_NAME_ERROR);
		assertThatThrownBy(withBlank).isInstanceOf(IllegalArgumentException.class)
				.hasMessage(BLANK_COLLECTION_NAME_ERROR);
	}

	@Test
	void createCollectionRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> collectionService.createCollection(null, null, null, null),
				() -> collectionService.createCollection(BLANK, null, null, null));
	}

	@Test
	void getCollectionStatsRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> collectionService.getCollectionStats(null),
				() -> collectionService.getCollectionStats(BLANK));
	}

	@Test
	void checkHealthRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> collectionService.checkHealth(null),
				() -> collectionService.checkHealth(BLANK));
	}

	@Test
	void indexJsonDocumentsRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> indexingService.indexJsonDocuments(null, "[]"),
				() -> indexingService.indexJsonDocuments(BLANK, "[]"));
	}

	@Test
	void indexCsvDocumentsRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> indexingService.indexCsvDocuments(null, "id\n1"),
				() -> indexingService.indexCsvDocuments(BLANK, "id\n1"));
	}

	@Test
	void indexXmlDocumentsRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> indexingService.indexXmlDocuments(null, "<docs/>"),
				() -> indexingService.indexXmlDocuments(BLANK, "<docs/>"));
	}

	@Test
	void getSchemaRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> schemaService.getSchema(null), () -> schemaService.getSchema(BLANK));
	}

	@Test
	void getSchemaResourceRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> schemaService.getSchemaResource(null),
				() -> schemaService.getSchemaResource(BLANK));
	}

	@Test
	void searchRejectsBlankCollectionName() {
		assertRejectsBlankCollection(() -> searchService.search(null, null, null, null, null, null, null),
				() -> searchService.search(BLANK, null, null, null, null, null, null));
	}
}
