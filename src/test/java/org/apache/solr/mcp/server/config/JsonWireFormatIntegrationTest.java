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
package org.apache.solr.mcp.server.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.search.SearchResponse;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Responses that the JSON wire format used to get wrong, read from a real Solr through {@link
 * JsonResponseParser}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonWireFormatIntegrationTest {

  private static final String DOCS = "json_wire_docs";

  private static final String ODD_NAMES = "json_wire_odd_names";

  @Autowired private SolrClient solrClient;

  @Autowired private SearchService searchService;

  @BeforeAll
  void index() throws Exception {
    CollectionAdminRequest.createCollection(DOCS, "_default", 1, 1).process(solrClient);
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", "p1");
    doc.addField("price_d", 1.1234567890123d);
    doc.addField("tags_ss", List.of("only"));
    UpdateRequest update = new UpdateRequest();
    update.add(doc);
    update.commit(solrClient, DOCS);

    // Schemaless _default adds fields literally called numFound and docs.
    CollectionAdminRequest.createCollection(ODD_NAMES, "_default", 1, 1).process(solrClient);
    SolrInputDocument odd = new SolrInputDocument();
    odd.addField("id", "o1");
    odd.addField("numFound", 3);
    odd.addField("docs", "x");
    UpdateRequest oddUpdate = new UpdateRequest();
    oddUpdate.add(odd);
    oddUpdate.commit(solrClient, ODD_NAMES);
  }

  @Test
  void doubleFieldKeepsFullPrecision() throws Exception {
    Map<String, Object> doc = search(null).documents().getFirst();

    assertEquals(1.1234567890123d, doc.get("price_d"));
  }

  @Test
  void singleValueOfMultiValuedFieldStaysList() throws Exception {
    Map<String, Object> doc = search(null).documents().getFirst();

    assertEquals(List.of("only"), doc.get("tags_ss"));
  }

  @Test
  void facetWithNoMatchingValuesIsEmpty() throws Exception {
    SearchResponse response = search(List.of("no_such_values_s"));

    assertEquals(Map.of(), response.facets().get("no_such_values_s"));
  }

  @Test
  void lukeListsFieldsNamedNumFoundAndDocs() throws Exception {
    LukeRequest luke = new LukeRequest();
    luke.setNumTerms(0);
    NamedList<Object> response = solrClient.request(luke, ODD_NAMES);

    NamedList<?> fields = assertInstanceOf(NamedList.class, response.get("fields"));
    assertNotNull(fields.get("numFound"), "fields: " + fields);
    assertNotNull(fields.get("docs"), "fields: " + fields);
  }

  private SearchResponse search(List<String> facetFields) throws Exception {
    return searchService.search(DOCS, "*:*", null, facetFields, null, null, null);
  }
}
