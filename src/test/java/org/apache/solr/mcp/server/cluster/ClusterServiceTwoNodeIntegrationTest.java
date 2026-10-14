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

import static org.apache.solr.mcp.server.cluster.TwoNodeSolrCloudConfiguration.NODE1;
import static org.apache.solr.mcp.server.cluster.TwoNodeSolrCloudConfiguration.NODE2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stops one node of a two-node SolrCloud and checks that {@code get-cluster-status} names every
 * replica that lived on it.
 */
@SpringBootTest
@Import(TwoNodeSolrCloudConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// The test stops solr2, so the context must not be reused.
@DirtiesContext
class ClusterServiceTwoNodeIntegrationTest {

  private static final String ON_NODE1 = "on_node1";

  private static final String ON_NODE2 = "on_node2";

  @Autowired private ClusterService clusterService;

  @Autowired private SolrClient solrClient;

  @Autowired
  @Qualifier("solr2") private GenericContainer<?> solr2;

  @BeforeAll
  void createCollections() throws Exception {
    CollectionAdminRequest.createCollection(ON_NODE2, "_default", 2, 1)
        .setCreateNodeSet(NODE2)
        .process(solrClient);
    CollectionAdminRequest.createCollection(ON_NODE1, "_default", 1, 1)
        .setCreateNodeSet(NODE1)
        .process(solrClient);
  }

  @Test
  @Order(1)
  void bothNodesUpIsHealthy() throws Exception {
    ClusterStatusResult result = clusterService.getClusterStatus(null);

    // Checked first: a node advertising another name leaves the pinned
    // collections without replicas, which would show up only as no-leader.
    assertThat(result.liveNodes()).containsExactly(NODE1, NODE2);
    assertThat(result.healthy()).as("issues: %s", result.issues()).isTrue();
    assertThat(result.collections().get(ON_NODE2).shards().values())
        .allSatisfy(
            shard ->
                assertThat(shard.replicas()).extracting(ReplicaState::node).containsOnly(NODE2));
  }

  @Test
  @Order(2)
  void stoppedNodeIsReportedWithEveryReplicaOnIt() {
    solr2.stop();

    // A killed node leaves live_nodes only when its ZooKeeper session expires
    // (30 s by default).
    ClusterStatusResult result =
        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofSeconds(2))
            .until(
                () -> clusterService.getClusterStatus(null),
                status -> !status.healthy() && status.liveNodes().equals(List.of(NODE1)));

    List<ReplicaIssue> replicaIssues =
        result.issues().stream()
            .filter(issue -> !ClusterStatusParser.NO_LEADER.equals(issue.reason()))
            .toList();
    assertThat(replicaIssues)
        .hasSize(2)
        .allSatisfy(
            issue -> {
              assertThat(issue.collection()).isEqualTo(ON_NODE2);
              assertThat(issue.node()).isEqualTo(NODE2);
              assertThat(issue.reason())
                  .isIn(ClusterStatusParser.NOT_ACTIVE, ClusterStatusParser.NODE_NOT_LIVE);
            });
    assertThat(result.issues())
        .filteredOn(issue -> ClusterStatusParser.NO_LEADER.equals(issue.reason()))
        .extracting(ReplicaIssue::collection, ReplicaIssue::shard)
        .containsExactly(tuple(ON_NODE2, "shard1"), tuple(ON_NODE2, "shard2"));
    assertThat(result.issues()).extracting(ReplicaIssue::collection).doesNotContain(ON_NODE1);
  }

  @Test
  @Order(3)
  void collectionOnSurvivingNodeIsHealthy() throws Exception {
    ClusterStatusResult result = clusterService.getClusterStatus(ON_NODE1);

    assertThat(result.healthy()).as("issues: %s", result.issues()).isTrue();
  }
}
