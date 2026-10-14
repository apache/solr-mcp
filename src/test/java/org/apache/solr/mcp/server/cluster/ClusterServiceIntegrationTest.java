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
  void newCollectionIsHealthyWithOneLeader() throws Exception {
    ClusterStatusResult result = clusterService.getClusterStatus(TEST_COLLECTION);

    assertThat(result.healthy()).as("issues: %s", result.issues()).isTrue();
    assertThat(result.liveNodes()).hasSize(1);
    assertThat(result.collections()).containsOnlyKeys(TEST_COLLECTION);

    CollectionState collection = result.collections().get(TEST_COLLECTION);
    assertThat(collection.configName()).isNotBlank();
    assertThat(collection.shards()).hasSize(1);

    ShardState shard = collection.shards().values().iterator().next();
    assertThat(shard.state()).isEqualTo("active");
    assertThat(shard.leader()).isNotNull();
    assertThat(shard.replicas())
        .singleElement()
        .satisfies(
            replica -> {
              assertThat(replica.leader()).isTrue();
              assertThat(replica.state()).isEqualTo("active");
              assertThat(replica.node()).isEqualTo(result.liveNodes().getFirst());
              assertThat(replica.core()).startsWith(TEST_COLLECTION);
              assertThat(replica.baseUrl()).endsWith("/solr");
            });
  }

  @Test
  void aliasReportsTheCollectionBehindIt() throws Exception {
    ClusterStatusResult result = clusterService.getClusterStatus(TEST_ALIAS);

    assertThat(result.collections()).containsOnlyKeys(TEST_COLLECTION);
  }

  @Test
  void noCollectionReportsEveryCollection() throws Exception {
    assertThat(clusterService.getClusterStatus(null).collections()).containsKey(TEST_COLLECTION);
  }

  @Test
  void unknownCollectionFailsWithNotFound() {
    assertThatThrownBy(() -> clusterService.getClusterStatus("no_such_collection"))
        .hasMessageContaining("not found");
  }

  @Test
  void resourceReturnsStatusAsJsonWithoutBaseUrl() {
    JsonNode json = objectMapper.readTree(clusterService.getClusterStatusResource());

    assertThat(json.has("healthy")).isTrue();
    assertThat(json.has("error")).isFalse();
    JsonNode replica = json.at("/collections/" + TEST_COLLECTION + "/shards/shard1/replicas/0");
    assertThat(replica.has("node")).isTrue();
    assertThat(replica.has("baseUrl")).isFalse();
  }
}
