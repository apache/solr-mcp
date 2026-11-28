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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica.ReplicaStateProps;
import org.apache.solr.common.cloud.Slice.SliceStateProps;
import org.jspecify.annotations.Nullable;

/**
 * The state of the cluster, as {@code get-cluster-status} and the {@code solr://cluster/status}
 * resource report it from the {@code cluster} section of Solr's CLUSTERSTATUS response.
 *
 * <p>The records carry Solr's own values and compute nothing: a replica's {@code state} and a
 * shard's {@code health} are as Solr reports them. The one value that changes form is {@code
 * leader}, which Solr sends as the string {@code "true"} and only on the leader. Every list is
 * sorted by name, so the same cluster gives the same JSON. Properties the records do not name, such
 * as a collection's {@code router} or a replica's {@code base_url}, are dropped.
 *
 * @param liveNodes the nodes currently live in the cluster, sorted
 * @param collections the collections reported on, sorted by name
 */
public record ClusterStatus(List<String> liveNodes, List<Collection> collections) {

  /** The section of CLUSTERSTATUS that lists the live nodes. */
  private static final String LIVE_NODES = "live_nodes";

  /** The section of CLUSTERSTATUS that lists the collections. */
  private static final String COLLECTIONS_SECTION = "collections";

  /** Solr's summary health of a collection or a shard. */
  private static final String HEALTH = "health";

  /** Copies and sorts both lists, so the record cannot change after it is built. */
  public ClusterStatus {
    liveNodes = liveNodes.stream().sorted().toList();
    collections = collections.stream().sorted(Comparator.comparing(Collection::name)).toList();
  }

  /**
   * One collection's status.
   *
   * @param name the collection name
   * @param health Solr's summary health, such as {@code GREEN}; before Solr 10.1 it can read GREEN
   *     with a replica down (SOLR-17820), so judge by replica {@code state}
   * @param configName the configset the collection uses
   * @param shards the collection's shards, sorted by name
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Collection(
      String name, @Nullable String health, @Nullable String configName, List<Shard> shards) {

    /** Copies and sorts the shards, so the record cannot change after it is built. */
    public Collection {
      shards = shards.stream().sorted(Comparator.comparing(Shard::name)).toList();
    }
  }

  /**
   * One shard's status.
   *
   * @param name the shard name, such as {@code shard1}
   * @param state the shard state, such as {@code active}
   * @param health Solr's summary health for the shard
   * @param range the hash range the shard covers, for the compositeId router
   * @param replicas the shard's replicas, sorted by name
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Shard(
      String name,
      @Nullable String state,
      @Nullable String health,
      @Nullable String range,
      List<Replica> replicas) {

    /** Copies and sorts the replicas, so the record cannot change after it is built. */
    public Shard {
      replicas = replicas.stream().sorted(Comparator.comparing(Replica::name)).toList();
    }
  }

  /**
   * One replica's status.
   *
   * @param name the replica's core node name, such as {@code core_node2}
   * @param core the core name
   * @param nodeName the node hosting the replica
   * @param state the replica state, such as {@code active}, {@code recovering} or {@code down}
   * @param type the replica type, such as {@code NRT}, {@code TLOG} or {@code PULL}
   * @param leader whether the replica is its shard's leader; it can stay true on a replica that is
   *     down
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Replica(
      String name,
      @Nullable String core,
      @Nullable String nodeName,
      @Nullable String state,
      @Nullable String type,
      boolean leader) {}

  /**
   * Builds the status from the {@code cluster} section of a CLUSTERSTATUS response.
   *
   * <p>Solr leaves out a section with nothing in it, such as {@code live_nodes} on a cluster with
   * no live node; it is reported as empty.
   *
   * @param cluster the section, as SolrJ parses it
   * @return the status
   */
  static ClusterStatus fromClusterStatus(Map<?, ?> cluster) {
    List<String> liveNodes = new ArrayList<>();
    if (cluster.get(LIVE_NODES) instanceof List<?> nodes) {
      for (Object node : nodes) {
        if (node instanceof String nodeName) {
          liveNodes.add(nodeName);
        }
      }
    }
    return new ClusterStatus(
        liveNodes, entries(cluster.get(COLLECTIONS_SECTION), ClusterStatus::collectionFrom));
  }

  private static Collection collectionFrom(String name, Map<?, ?> collection) {
    return new Collection(
        name,
        text(collection, HEALTH),
        text(collection, DocCollection.CollectionStateProps.CONFIGNAME),
        entries(
            collection.get(DocCollection.CollectionStateProps.SHARDS), ClusterStatus::shardFrom));
  }

  private static Shard shardFrom(String name, Map<?, ?> shard) {
    return new Shard(
        name,
        text(shard, SliceStateProps.STATE_PROP),
        text(shard, HEALTH),
        text(shard, SliceStateProps.RANGE),
        entries(shard.get(SliceStateProps.REPLICAS), ClusterStatus::replicaFrom));
  }

  private static Replica replicaFrom(String name, Map<?, ?> replica) {
    return new Replica(
        name,
        text(replica, ReplicaStateProps.CORE_NAME),
        text(replica, ReplicaStateProps.NODE_NAME),
        text(replica, ReplicaStateProps.STATE),
        text(replica, ReplicaStateProps.TYPE),
        isLeader(replica.get(ReplicaStateProps.LEADER)));
  }

  /**
   * Whether Solr marked a replica as its shard's leader: Solr sends the string {@code "true"}, and
   * leaves the property out on every other replica.
   */
  private static boolean isLeader(@Nullable Object leader) {
    return Boolean.TRUE.equals(leader)
        || (leader instanceof String flag && Boolean.parseBoolean(flag));
  }

  /**
   * Maps each entry of a Solr object whose values are objects, such as a collection's {@code
   * shards}, keyed by name. Anything that is not an object is skipped.
   */
  private static <T> List<T> entries(
      @Nullable Object section, BiFunction<String, Map<?, ?>, T> mapper) {
    List<T> mapped = new ArrayList<>();
    if (section instanceof Map<?, ?> entries) {
      for (Map.Entry<?, ?> entry : entries.entrySet()) {
        if (entry.getKey() instanceof String name && entry.getValue() instanceof Map<?, ?> value) {
          mapped.add(mapper.apply(name, value));
        }
      }
    }
    return mapped;
  }

  private static @Nullable String text(Map<?, ?> object, String key) {
    return object.get(key) instanceof String value ? value : null;
  }
}
