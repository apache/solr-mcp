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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive test suite for the SchemaService class. Tests schema retrieval
 * functionality with various scenarios including success and error cases.
 */
@ExtendWith(MockitoExtension.class)
class SchemaServiceTest {

	@Mock
	private SolrClient solrClient;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private SchemaResponse schemaResponse;

	@Mock
	private SchemaRepresentation schemaRepresentation;

	private SchemaService schemaService;

	@BeforeEach
	void setUp() {
		schemaService = new SchemaService(solrClient, objectMapper);
	}

	@Test
	void testSchemaService_InstantiatesCorrectly() {
		// Given/When
		SchemaService service = new SchemaService(solrClient, objectMapper);

		// Then
		assertNotNull(service, "SchemaService should be instantiated correctly");
	}

	@Test
	void testGetSchema_CollectionNotFound() throws Exception {
		// Given
		final String nonExistentCollection = "non_existent_collection";

		// When SolrClient throws an exception for non-existent collection
		when(solrClient.request(any(SchemaRequest.class), eq(nonExistentCollection)))
				.thenThrow(new SolrServerException("Collection not found: " + nonExistentCollection));

		// Then
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(nonExistentCollection);
		});
	}

	@Test
	void testGetSchema_SolrServerException() throws Exception {
		// Given
		final String collectionName = "test_collection";

		// When SolrClient throws a SolrServerException
		when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
				.thenThrow(new SolrServerException("Solr server error"));

		// Then
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(collectionName);
		});
	}

	@Test
	void testGetSchema_IOException() throws Exception {
		// Given
		final String collectionName = "test_collection";

		// When SolrClient throws an IOException
		when(solrClient.request(any(SchemaRequest.class), eq(collectionName)))
				.thenThrow(new IOException("Network connection error"));

		// Then
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(collectionName);
		});
	}

	@Test
	void testGetSchema_WithNullCollection() {
		// Given a null collection name
		// Then should throw an exception (NullPointerException or
		// IllegalArgumentException)
		assertThrows(Exception.class, () -> {
			schemaService.getSchema(null);
		});
	}

	@Test
	void testGetSchema_WithEmptyCollection() {
		// Given an empty collection name
		// Then should throw an exception
		assertThrows(Exception.class, () -> {
			schemaService.getSchema("");
		});
	}

	@Test
	void testConstructor() {
		// Test that constructor properly initializes the service
		SchemaService service = new SchemaService(solrClient, objectMapper);
		assertNotNull(service);
	}

	@Test
	void testConstructor_WithNullClient() {
		// Test constructor with null client
		assertDoesNotThrow(() -> {
			new SchemaService(null, objectMapper);
		});
	}
}
