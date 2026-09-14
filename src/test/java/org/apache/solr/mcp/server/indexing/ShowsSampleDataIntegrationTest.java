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
package org.apache.solr.mcp.server.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same 61 shows, indexed through the JSON, CSV and XML tools into three
 * collections, must come back from Solr as the same documents. CSV and XML are
 * forwarded to Solr's own update handlers rather than parsed here, so this is
 * the check that a tutorial step written against one format holds for the
 * others.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ShowsSampleDataIntegrationTest {

	private static final int SHOWS = 61;

	@Autowired
	private IndexingService indexingService;

	@Autowired
	private SolrClient solrClient;

	@Test
	void jsonCsvAndXmlIndexTheSameDocuments() throws Exception {
		String suffix = "_" + System.currentTimeMillis();
		String json = "shows_json" + suffix;
		String csv = "shows_csv" + suffix;
		String xml = "shows_xml" + suffix;
		for (String collection : List.of(json, csv, xml)) {
			CollectionAdminRequest.createCollection(collection, "_default", 1, 1).process(solrClient);
		}

		assertTrue(indexingService.indexJsonDocuments(json, resource("/shows.json")).contains(SHOWS + " of " + SHOWS));
		assertTrue(indexingService.indexCsvDocuments(csv, resource("/shows.csv")).contains(SHOWS + " of " + SHOWS));
		assertTrue(indexingService.indexXmlDocuments(xml, resource("/shows.xml")).contains(SHOWS + " of " + SHOWS));

		Map<String, Map<String, List<String>>> fromJson = documents(json);
		assertEquals(SHOWS, fromJson.size());
		assertEquals(fromJson, documents(csv), "CSV documents differ from JSON");
		assertEquals(fromJson, documents(xml), "XML documents differ from JSON");
	}

	private Map<String, Map<String, List<String>>> documents(String collection) throws Exception {
		Map<String, Map<String, List<String>>> byId = new TreeMap<>();
		for (SolrDocument doc : solrClient.query(collection, new SolrQuery("*:*").setRows(SHOWS + 1)).getResults()) {
			Map<String, List<String>> fields = new TreeMap<>();
			for (String name : doc.getFieldNames()) {
				if (!name.startsWith("_")) {
					fields.put(name, doc.getFieldValues(name).stream().map(String::valueOf).toList());
				}
			}
			byId.put(String.valueOf(doc.getFieldValue("id")), fields);
		}
		return byId;
	}

	private static String resource(String path) throws IOException {
		try (InputStream in = Objects.requireNonNull(ShowsSampleDataIntegrationTest.class.getResourceAsStream(path),
				"missing test resource " + path)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
