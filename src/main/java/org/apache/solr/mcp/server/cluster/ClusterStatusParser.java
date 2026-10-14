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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.util.NamedList;
import org.jspecify.annotations.Nullable;

/**
 * Turns a CLUSTERSTATUS response into a {@link ClusterStatusResult}.
 *
 * <p>Public so other packages can reuse the replica data (core name, base URL, leader, state)
 * without going through {@link ClusterService}'s security proxy.
 *
 * <p>{@code healthy} is computed from replica state and live nodes rather than read from the {@code
 * health} field Solr adds to each collection and shard. Solr computes that field before it
 * cross-checks replica state against live nodes, so a replica whose node died without publishing
 * {@code down} still counts as active there.
 */
public final class ClusterStatusParser {

  /** Issue reason: the replica's state is not {@code active}. */
  public static final String NOT_ACTIVE = "not-active";

  /** Issue reason: the replica's node is not in {@code live_nodes}. */
  public static final String NODE_NOT_LIVE = "node-not-live";

  /** Issue reason: an active shard has no replica that is active, leader and on a live node. */
  public static final String NO_LEADER = "no-leader";

  // ZkStateReader defines these, but it lives in solr-solrj-zookeeper, which is
  // not on the classpath.
  private static final String CLUSTER = "cluster";

  private static final String COLLECTIONS = "collections";

  private static final String LIVE_NODES = "live_nodes";

  private static final String SHARDS = "shards";

  private static final String CONFIG_NAME = "configName";

  private static final String ACTIVE_SHARD = Slice.State.ACTIVE.toString();

  private static final String ACTIVE_REPLICA = Replica.State.ACTIVE.toString();

  private ClusterStatusParser() {}

  /**
   * Parses a CLUSTERSTATUS response.
   *
   * @param response the full response, holding a {@code cluster} entry
   * @return the parsed status; {@code healthy} is true when no issue was found
   */
  public static ClusterStatusResult parse(NamedList<Object> response) {
    NamedList<Object> cluster = namedList(response.get(CLUSTER));

    List<String> liveNodes = new ArrayList<>();
    if (cluster.get(LIVE_NODES) instanceof List<?> nodes) {
      for (Object node : nodes) {
        liveNodes.add(String.valueOf(node));
      }
    }
    liveNodes.sort(Comparator.naturalOrder());

    Map<String, CollectionState> collections = new TreeMap<>();
    for (Map.Entry<String, Object> collection : namedList(cluster.get(COLLECTIONS))) {
      collections.put(collection.getKey(), parseCollection(namedList(collection.getValue())));
    }

    List<ReplicaIssue> issues = findIssues(collections, Set.copyOf(liveNodes));
    return new ClusterStatusResult(issues.isEmpty(), issues, liveNodes, collections);
  }

  private static CollectionState parseCollection(NamedList<Object> collection) {
    Map<String, ShardState> shards = new TreeMap<>();
    for (Map.Entry<String, Object> shard : namedList(collection.get(SHARDS))) {
      shards.put(shard.getKey(), parseShard(namedList(shard.getValue())));
    }
    return new CollectionState(text(collection.get(CONFIG_NAME)), shards);
  }

  private static ShardState parseShard(NamedList<Object> shard) {
    List<ReplicaState> replicas = new ArrayList<>();
    for (Map.Entry<String, Object> replica : namedList(shard.get(Slice.SliceStateProps.REPLICAS))) {
      replicas.add(parseReplica(replica.getKey(), namedList(replica.getValue())));
    }
    replicas.sort(Comparator.comparing(ReplicaState::name));

    String leader = null;
    for (ReplicaState replica : replicas) {
      if (replica.leader()) {
        leader = replica.name();
      }
    }
    return new ShardState(
        textOrEmpty(shard.get(Slice.SliceStateProps.STATE_PROP)), leader, replicas);
  }

  private static ReplicaState parseReplica(String name, NamedList<Object> replica) {
    // Solr writes the leader flag as the string "true"; accept a boolean too.
    boolean leader =
        Boolean.parseBoolean(String.valueOf(replica.get(Replica.ReplicaStateProps.LEADER)));
    return new ReplicaState(
        name,
        textOrEmpty(replica.get(Replica.ReplicaStateProps.CORE_NAME)),
        textOrEmpty(replica.get(Replica.ReplicaStateProps.NODE_NAME)),
        textOrEmpty(replica.get(Replica.ReplicaStateProps.STATE)),
        textOrEmpty(replica.get(Replica.ReplicaStateProps.TYPE)),
        leader,
        text(replica.get(Replica.ReplicaStateProps.BASE_URL)));
  }

  /**
   * Applies the issue rules to active shards only: an inactive parent shard left behind by
   * SPLITSHARD is expected, not a fault. Each replica produces at most one issue, and {@code
   * not-active} wins over {@code node-not-live}.
   */
  private static List<ReplicaIssue> findIssues(
      Map<String, CollectionState> collections, Set<String> liveNodes) {
    List<ReplicaIssue> issues = new ArrayList<>();
    for (Map.Entry<String, CollectionState> collection : collections.entrySet()) {
      for (Map.Entry<String, ShardState> shardEntry : collection.getValue().shards().entrySet()) {
        ShardState shard = shardEntry.getValue();
        if (!ACTIVE_SHARD.equals(shard.state())) {
          continue;
        }
        boolean hasActiveLeader = false;
        for (ReplicaState replica : shard.replicas()) {
          boolean active = ACTIVE_REPLICA.equals(replica.state());
          boolean live = liveNodes.contains(replica.node());
          // A leader on a node that is not live cannot serve, even if Solr
          // has not yet rewritten its state to down.
          hasActiveLeader |= active && live && replica.leader();
          if (!active) {
            issues.add(issue(collection.getKey(), shardEntry.getKey(), replica, NOT_ACTIVE));
          } else if (!live) {
            issues.add(issue(collection.getKey(), shardEntry.getKey(), replica, NODE_NOT_LIVE));
          }
        }
        if (!hasActiveLeader) {
          issues.add(
              new ReplicaIssue(
                  collection.getKey(), shardEntry.getKey(), null, null, null, NO_LEADER));
        }
      }
    }
    return issues;
  }

  private static ReplicaIssue issue(
      String collection, String shard, ReplicaState replica, String reason) {
    return new ReplicaIssue(
        collection, shard, replica.name(), replica.node(), replica.state(), reason);
  }

  @SuppressWarnings("unchecked")
  private static NamedList<Object> namedList(@Nullable Object value) {
    return value instanceof NamedList<?> list ? (NamedList<Object>) list : new NamedList<>();
  }

  private static @Nullable String text(@Nullable Object value) {
    return value == null ? null : value.toString();
  }

  private static String textOrEmpty(@Nullable Object value) {
    return value == null ? "" : value.toString();
  }
}
