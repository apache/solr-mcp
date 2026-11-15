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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link JsonResponseParser}'s conversion of Solr's JSON wire format into the {@link
 * NamedList} tree SolrJ expects.
 *
 * <p>Search requests carry {@code json.nl=map}, so the facet inputs here use Solr's map style:
 * every NamedList is a JSON object. SolrJ's {@link QueryResponse} casts every {@code
 * facet_counts/facet_fields} entry to a {@link NamedList}, including an empty one.
 */
class JsonResponseParserTest {

  private final JsonResponseParser parser = new JsonResponseParser(JsonMapper.builder().build());

  private NamedList<Object> parse(String json) {
    return parser.processResponse(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "UTF-8");
  }

  @SuppressWarnings("unchecked")
  private static NamedList<Object> facetFields(NamedList<Object> response) {
    NamedList<Object> facetCounts = (NamedList<Object>) response.get("facet_counts");
    return (NamedList<Object>) facetCounts.get("facet_fields");
  }

  @Test
  @DisplayName("a facet written as an object converts to a NamedList of counts")
  void facetObjectBecomesNamedList() {
    NamedList<Object> response =
        parse(
            """
				{"facet_counts":{"facet_fields":{"genre":{"fantasy":10,"scifi":5}}}}
				""");

    NamedList<Object> counts =
        assertInstanceOf(NamedList.class, facetFields(response).get("genre"));
    assertEquals(2, counts.size());
    assertEquals(10, counts.get("fantasy"));
    assertEquals(5, counts.get("scifi"));
  }

  @Test
  @DisplayName("an empty facet object converts to an empty NamedList")
  void emptyFacetBecomesEmptyNamedList() {
    NamedList<Object> response =
        parse(
            """
				{"facet_counts":{"facet_fields":{"genre":{}}}}
				""");

    NamedList<Object> counts =
        assertInstanceOf(NamedList.class, facetFields(response).get("genre"));
    assertEquals(0, counts.size());
  }

  @Test
  @DisplayName("arrays are always lists, whatever their contents look like")
  void arraysStayLists() {
    NamedList<Object> response =
        parse(
            """
				{"responseHeader":{"warnings":[],"fields":["col1","col2"],"pairs":["a",1,"b",2]}}
				""");

    @SuppressWarnings("unchecked")
    NamedList<Object> header = (NamedList<Object>) response.get("responseHeader");
    assertEquals(List.of(), header.get("warnings"));
    assertEquals(List.of("col1", "col2"), header.get("fields"));
    assertEquals(List.of("a", 1, "b", 2), header.get("pairs"));
  }

  @Test
  @DisplayName("QueryResponse can read facets when one field has zero matches")
  void queryResponseHandlesEmptyFacet() {
    NamedList<Object> response =
        parse(
            """
				{"responseHeader":{"status":0,"QTime":1},
				 "response":{"numFound":0,"start":0,"docs":[]},
				 "facet_counts":{"facet_fields":{"genre":{},"author":{"asimov":3}}}}
				""");

    QueryResponse queryResponse = new QueryResponse();
    queryResponse.setResponse(response);

    assertEquals(2, queryResponse.getFacetFields().size());
    assertTrue(queryResponse.getFacetField("genre").getValues().isEmpty());
    assertEquals(1, queryResponse.getFacetField("author").getValues().size());
  }

  @Test
  @DisplayName("an object that only has keys named numFound and docs is not a result list")
  void lukeFieldsNamedNumFoundAndDocsStayNamedList() {
    // Luke's fields section is keyed by field name, so a collection with fields
    // called numFound and docs produces exactly these keys.
    NamedList<Object> response =
        parse(
            """
				{"fields":{"numFound":{"type":"plongs","docs":1},"docs":{"type":"text_general","docs":1}}}
				""");

    NamedList<Object> fields = assertInstanceOf(NamedList.class, response.get("fields"));
    assertEquals(2, fields.size());
  }

  @Test
  @DisplayName("a result list's documents keep decimal field values at full precision")
  void documentDecimalsAreDoubles() {
    NamedList<Object> response =
        parse(
            """
				{"response":{"numFound":1,"start":0,"maxScore":1.5,
				  "docs":[{"id":"1","price_d":1.1234567890123,"scores_d":[2.5]}]}}
				""");

    SolrDocumentList docs = assertInstanceOf(SolrDocumentList.class, response.get("response"));
    assertEquals(1.5f, docs.getMaxScore());
    SolrDocument doc = docs.getFirst();
    assertEquals(1.1234567890123d, doc.getFieldValue("price_d"));
    assertEquals(List.of(2.5d), doc.getFieldValue("scores_d"));
  }

  @Test
  @DisplayName("decimals outside documents stay Float, which SchemaResponse casts to")
  void schemaVersionStaysFloat() {
    NamedList<Object> response =
        parse(
            """
				{"responseHeader":{"status":0,"QTime":1},"schema":{"name":"default-config","version":1.7,
				  "uniqueKey":"id","fields":[],"dynamicFields":[],"fieldTypes":[],"copyFields":[]}}
				""");

    SchemaResponse schemaResponse = new SchemaResponse();
    schemaResponse.setResponse(response);

    assertEquals(1.7f, schemaResponse.getSchemaRepresentation().getVersion());
  }
}
