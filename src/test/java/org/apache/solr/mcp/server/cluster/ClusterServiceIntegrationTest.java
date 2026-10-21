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
package org.apache.solr.mcp.server.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.collection.AliasResult;
import org.apache.solr.mcp.server.collection.AliasService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for {@link ClusterService} against the shared single-node SolrCloud container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterServiceIntegrationTest {

  private static final String TEST_COLLECTION = "cluster_test_collection";

  private static final String TEST_ALIAS = "cluster_test_alias";

  @Autowired private ClusterService clusterService;

  @Autowired private SolrClient solrClient;

  @Autowired private AliasService aliasService;

  @Autowired private ObjectMapper objectMapper;

  @BeforeAll
  void setupCollection() throws Exception {
    CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1).process(solrClient);
    AliasResult alias = aliasService.createAlias(TEST_ALIAS, TEST_COLLECTION);
    assertThat(alias.success()).isTrue();
  }

  @Test
  void listConfigSetsContainsDefault() throws Exception {
    assertThat(clusterService.listConfigSets()).contains("_default").isSorted();
  }

  @Test
  void newCollectionHasOneActiveLeaderOnTheLiveNode() throws Exception {
    JsonNode status = objectMapper.valueToTree(clusterService.getClusterStatus(TEST_COLLECTION));

    assertThat(status.get("live_nodes")).hasSize(1);
    String liveNode = status.get("live_nodes").get(0).asString();
    assertThat(status.get("collections").propertyNames()).containsExactly(TEST_COLLECTION);

    JsonNode collection = status.get("collections").get(TEST_COLLECTION);
    assertThat(collection.get("configName").asString()).isNotBlank();
    JsonNode shard = collection.at("/shards/shard1");
    assertThat(shard.get("state").asString()).isEqualTo("active");
    assertThat(shard.get("replicas"))
        .singleElement()
        .satisfies(
            replica -> {
              assertThat(replica.get("state").asString()).isEqualTo("active");
              assertThat(replica.get("leader").asString()).isEqualTo("true");
              assertThat(replica.get("node_name").asString()).isEqualTo(liveNode);
              assertThat(replica.get("core").asString()).startsWith(TEST_COLLECTION);
              assertThat(replica.get("base_url").asString()).endsWith("/solr");
            });
  }

  @Test
  void aliasReportsTheCollectionBehindIt() throws Exception {
    assertThat(collectionNames(clusterService.getClusterStatus(TEST_ALIAS)))
        .containsExactly(TEST_COLLECTION);
  }

  @Test
  void noCollectionReportsEveryCollection() throws Exception {
    assertThat(collectionNames(clusterService.getClusterStatus(null))).contains(TEST_COLLECTION);
  }

  @Test
  void unknownCollectionFailsWithNotFound() {
    assertThatThrownBy(() -> clusterService.getClusterStatus("no_such_collection"))
        .hasMessageContaining("not found");
  }

  @Test
  void resourceReturnsTheSameStatusAsJson() {
    JsonNode json = objectMapper.readTree(clusterService.getClusterStatusResource());

    assertThat(json.has("error")).isFalse();
    assertThat(json.get("live_nodes")).isNotEmpty();
    assertThat(json.at("/collections/" + TEST_COLLECTION + "/shards/shard1/replicas")).isNotEmpty();
  }

  private Collection<String> collectionNames(Map<String, Object> status) {
    return objectMapper.valueToTree(status).get("collections").propertyNames();
  }
}
