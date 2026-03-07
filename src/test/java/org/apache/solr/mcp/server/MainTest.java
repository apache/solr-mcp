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

import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.metadata.CollectionService;
import org.apache.solr.mcp.server.metadata.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Application context loading test with mocked services. This test verifies
 * that the Spring application context can be loaded successfully without
 * requiring actual Solr connections, using mocked beans to prevent external
 * dependencies.
 */
@SpringBootTest
class MainTest {

	@MockitoBean
	private SearchService searchService;

	@MockitoBean
	private IndexingService indexingService;

	@MockitoBean
	private CollectionService collectionService;

	@MockitoBean
	private SchemaService schemaService;

	@Test
	void contextLoads() {
		// Context loading test - all services are mocked to prevent Solr API calls
	}
}
