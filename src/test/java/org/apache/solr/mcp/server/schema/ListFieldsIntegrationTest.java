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
package org.apache.solr.mcp.server.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@code list-fields} against a real Solr.
 *
 * <p>Only {@code *_s}, {@code *_i} and {@code id} fields are indexed, so documents match {@code
 * _default}'s dynamic fields and nothing goes through schemaless field guessing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListFieldsIntegrationTest {

  private static final String ONE_SHARD = "list_fields_one_shard";

  private static final String TWO_SHARDS = "list_fields_two_shards";

  @Autowired private SchemaService schemaService;

  @Autowired private SolrClient solrClient;

  @BeforeAll
  void createCollections() throws Exception {
    CollectionAdminRequest.createCollection(ONE_SHARD, "_default", 1, 1).process(solrClient);
    UpdateRequest oneShard = new UpdateRequest();
    oneShard.add(doc("a", Map.of("genre_s", "drama", "year_i", 2020)));
    oneShard.add(doc("b", Map.of("genre_s", "comedy", "year_i", 2021)));
    oneShard.commit(solrClient, ONE_SHARD);

    // The implicit router lets the test decide which shard each document lands on.
    CollectionAdminRequest.createCollectionWithImplicitRouter(
            TWO_SHARDS, "_default", "shard_a,shard_b", 1)
        .process(solrClient);
    indexToShard("shard_a", doc("on-a", Map.of("only_a_s", "x")));
    indexToShard("shard_b", doc("on-b", Map.of("only_b_s", "y")));
    new UpdateRequest().commit(solrClient, TWO_SHARDS);
  }

  @Test
  void listsDynamicFieldInstancesWithTheirBase() throws Exception {
    Map<String, Map<String, Object>> fields = fields(ONE_SHARD);

    Map<String, Object> genre = fields.get("genre_s");
    assertNotNull(genre, "genre_s should be listed: " + fields.keySet());
    assertEquals("*_s", genre.get("dynamicBase"));
    assertEquals("string", genre.get("type"));
    assertEquals(2L, ((Number) genre.get("docs")).longValue());
    assertFalse(fields.get("id").containsKey("dynamicBase"), "id is an explicit field");
  }

  @Test
  void pointFieldsHaveNoDocCount() throws Exception {
    Map<String, Object> year = fields(ONE_SHARD).get("year_i");

    assertNotNull(year, "year_i should be listed");
    assertEquals("pint", year.get("type"));
    // Luke cannot count a field without terms: before 9.11/10.1 it leaves docs
    // out, from 9.11/10.1 it reports 0.
    Object docs = year.get("docs");
    assertTrue(docs == null || ((Number) docs).longValue() == 0, "docs: " + docs);
  }

  @Test
  void passesThroughTheFlagLegendAndNotTheIndexSection() throws Exception {
    Map<String, Object> listing = schemaService.listFields(ONE_SHARD);

    assertEquals(java.util.Set.of("fields", "info"), listing.keySet());
    Map<?, ?> key = (Map<?, ?>) ((Map<?, ?>) listing.get("info")).get("key");
    assertEquals("Indexed", key.get("I"), "info.key decodes the schema flag letters: " + key);
  }

  @Test
  void twoShardListingCoversBothShardsOnlyWhereSolrDistributesLuke() throws Exception {
    Map<String, Map<String, Object>> fields = fields(TWO_SHARDS);

    // Worked out from the server's version, not from the tool's answer.
    if (solrDistributesLuke()) {
      assertEquals(1L, ((Number) fields.get("only_a_s").get("docs")).longValue(), "" + fields);
      assertEquals(1L, ((Number) fields.get("only_b_s").get("docs")).longValue(), "" + fields);
    } else {
      assertNotEquals(
          fields.containsKey("only_a_s"),
          fields.containsKey("only_b_s"),
          "a single core holds exactly one of the two shard-only fields: " + fields.keySet());
    }
  }

  @Test
  void unknownCollectionFailsWithNotFound() {
    SolrException e =
        assertThrows(SolrException.class, () -> schemaService.listFields("no_such_collection_xyz"));
    assertEquals(SolrException.ErrorCode.NOT_FOUND.code, e.code());
  }

  private void indexToShard(String shard, SolrInputDocument document) throws Exception {
    UpdateRequest update = new UpdateRequest();
    update.add(document);
    update.setParam("_route_", shard);
    update.process(solrClient, TWO_SHARDS);
  }

  private static SolrInputDocument doc(String id, Map<String, Object> fields) {
    SolrInputDocument document = new SolrInputDocument();
    document.addField("id", id);
    fields.forEach(document::addField);
    return document;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> fields(String collection) throws Exception {
    return (Map<String, Map<String, Object>>) schemaService.listFields(collection).get("fields");
  }

  /** SOLR-8127 shipped in 9.11 and 10.1; 10.0 does not have it. */
  private boolean solrDistributesLuke() throws Exception {
    NamedList<Object> info =
        new GenericSolrRequest(SolrRequest.METHOD.GET, "/admin/info/system")
            .process(solrClient)
            .getResponse();
    String version = (String) ((NamedList<?>) info.get("lucene")).get("solr-spec-version");
    String[] parts = version.split("\\.");
    int major = Integer.parseInt(parts[0]);
    int minor = Integer.parseInt(parts[1]);
    return major > 10 || (major == 10 && minor >= 1) || (major == 9 && minor >= 11);
  }
}
