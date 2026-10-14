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

import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.jupiter.api.Test;

/**
 * Applies the issue rules to hand-built CLUSTERSTATUS responses shaped the way {@code
 * JsonResponseParser} produces them: objects as {@link NamedList}, string arrays as {@link List}.
 */
class ClusterStatusParserTest {

  private static final String NODE1 = "solr1:8983_solr";

  private static final String NODE2 = "solr2:8983_solr";

  @Test
  void allActiveWithLeaderIsHealthy() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE1),
                collection(
                    "films",
                    "films_conf",
                    shard(
                        "shard1",
                        "active",
                        replica("core_node1", NODE1, "active", true),
                        replica("core_node2", NODE1, "active", false)))));

    assertThat(result.healthy()).isTrue();
    assertThat(result.issues()).isEmpty();
    assertThat(result.liveNodes()).containsExactly(NODE1);

    CollectionState films = result.collections().get("films");
    assertThat(films.configName()).isEqualTo("films_conf");
    ShardState shard = films.shards().get("shard1");
    assertThat(shard.leader()).isEqualTo("core_node1");
    assertThat(shard.replicas())
        .extracting(ReplicaState::baseUrl)
        .containsOnly("http://solr1:8983/solr");
    assertThat(shard.replicas())
        .extracting(ReplicaState::core)
        .containsExactly("films_core_node1", "films_core_node2");
  }

  @Test
  void recoveringReplicaIsNotActive() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE1, NODE2),
                collection(
                    "films",
                    "films_conf",
                    shard(
                        "shard1",
                        "active",
                        replica("core_node1", NODE1, "active", true),
                        replica("core_node2", NODE2, "recovering", false)))));

    assertThat(result.healthy()).isFalse();
    assertThat(result.issues())
        .containsExactly(
            new ReplicaIssue(
                "films",
                "shard1",
                "core_node2",
                NODE2,
                "recovering",
                ClusterStatusParser.NOT_ACTIVE));
  }

  @Test
  void activeReplicaOnDeadNodeIsNodeNotLive() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE1),
                collection(
                    "films",
                    "films_conf",
                    shard(
                        "shard1",
                        "active",
                        replica("core_node1", NODE1, "active", true),
                        replica("core_node2", NODE2, "active", false)))));

    assertThat(result.issues())
        .containsExactly(
            new ReplicaIssue(
                "films",
                "shard1",
                "core_node2",
                NODE2,
                "active",
                ClusterStatusParser.NODE_NOT_LIVE));
  }

  @Test
  void downLeaderIsNotActiveAndLeavesShardWithoutLeader() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE1),
                collection(
                    "films",
                    "films_conf",
                    shard(
                        "shard1",
                        "active",
                        replica("core_node1", NODE2, "down", true),
                        replica("core_node2", NODE1, "active", false)))));

    assertThat(result.issues())
        .containsExactly(
            new ReplicaIssue(
                "films", "shard1", "core_node1", NODE2, "down", ClusterStatusParser.NOT_ACTIVE),
            new ReplicaIssue("films", "shard1", null, null, null, ClusterStatusParser.NO_LEADER));
  }

  @Test
  void activeLeaderOnDeadNodeLeavesShardWithoutLeader() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE1),
                collection(
                    "films",
                    "films_conf",
                    shard(
                        "shard1",
                        "active",
                        replica("core_node1", NODE2, "active", true),
                        replica("core_node2", NODE1, "active", false)))));

    assertThat(result.issues())
        .containsExactly(
            new ReplicaIssue(
                "films",
                "shard1",
                "core_node1",
                NODE2,
                "active",
                ClusterStatusParser.NODE_NOT_LIVE),
            new ReplicaIssue("films", "shard1", null, null, null, ClusterStatusParser.NO_LEADER));
  }

  @Test
  void inactiveShardIsIgnored() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE1),
                collection(
                    "films",
                    "films_conf",
                    shard("shard1", "inactive", replica("core_node1", NODE1, "down", true)),
                    shard("shard1_0", "active", replica("core_node3", NODE1, "active", true)))));

    assertThat(result.healthy()).isTrue();
    assertThat(result.collections().get("films").shards()).containsOnlyKeys("shard1", "shard1_0");
  }

  @Test
  void collectionsAndLiveNodesAreSorted() {
    ClusterStatusResult result =
        ClusterStatusParser.parse(
            response(
                List.of(NODE2, NODE1),
                collection(
                    "zebra",
                    "_default",
                    shard("shard1", "active", replica("core_node1", NODE1, "active", true))),
                collection(
                    "alpha",
                    "_default",
                    shard("shard1", "active", replica("core_node1", NODE2, "active", true)))));

    assertThat(result.liveNodes()).containsExactly(NODE1, NODE2);
    assertThat(result.collections().keySet()).containsExactly("alpha", "zebra");
  }

  @Test
  void emptyResponseIsHealthyWithNothingReported() {
    ClusterStatusResult result = ClusterStatusParser.parse(new NamedList<>());

    assertThat(result.healthy()).isTrue();
    assertThat(result.liveNodes()).isEmpty();
    assertThat(result.collections()).isEmpty();
  }

  @SafeVarargs
  private static NamedList<Object> response(
      List<String> liveNodes, NamedList<Object>... collections) {
    SimpleOrderedMap<Object> collectionMap = new SimpleOrderedMap<>();
    for (NamedList<Object> collection : collections) {
      collectionMap.add(collection.getName(0), collection.getVal(0));
    }
    SimpleOrderedMap<Object> cluster = new SimpleOrderedMap<>();
    cluster.add("collections", collectionMap);
    cluster.add("live_nodes", liveNodes);
    SimpleOrderedMap<Object> response = new SimpleOrderedMap<>();
    response.add("cluster", cluster);
    return response;
  }

  /** One-entry list of name to collection, unpacked by {@link #response}. */
  @SafeVarargs
  private static NamedList<Object> collection(
      String name, String configName, NamedList<Object>... shards) {
    SimpleOrderedMap<Object> shardMap = new SimpleOrderedMap<>();
    for (NamedList<Object> shard : shards) {
      shardMap.add(shard.getName(0), shard.getVal(0));
    }
    SimpleOrderedMap<Object> collection = new SimpleOrderedMap<>();
    collection.add("configName", configName);
    collection.add("health", "GREEN");
    collection.add("shards", shardMap);
    SimpleOrderedMap<Object> entry = new SimpleOrderedMap<>();
    entry.add(name, collection);
    return entry;
  }

  @SafeVarargs
  private static NamedList<Object> shard(String name, String state, NamedList<Object>... replicas) {
    SimpleOrderedMap<Object> replicaMap = new SimpleOrderedMap<>();
    for (NamedList<Object> replica : replicas) {
      replicaMap.add(replica.getName(0), replica.getVal(0));
    }
    SimpleOrderedMap<Object> shard = new SimpleOrderedMap<>();
    shard.add("range", "80000000-7fffffff");
    shard.add("state", state);
    shard.add("replicas", replicaMap);
    shard.add("health", "GREEN");
    SimpleOrderedMap<Object> entry = new SimpleOrderedMap<>();
    entry.add(name, shard);
    return entry;
  }

  private static NamedList<Object> replica(String name, String node, String state, boolean leader) {
    SimpleOrderedMap<Object> replica = new SimpleOrderedMap<>();
    replica.add("core", "films_" + name);
    replica.add("node_name", node);
    replica.add("base_url", "http://" + node.replace("_solr", "") + "/solr");
    replica.add("state", state);
    replica.add("type", "NRT");
    if (leader) {
      // Solr writes the flag as a string, and omits it on non-leaders.
      replica.add("leader", "true");
    }
    SimpleOrderedMap<Object> entry = new SimpleOrderedMap<>();
    entry.add(name, replica);
    return entry;
  }
}
