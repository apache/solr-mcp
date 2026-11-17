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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code cluster} section of Solr's CLUSTERSTATUS response, returned by the {@code
 * get-cluster-status} tool and the {@code solr://cluster/status} resource.
 *
 * <p>The records carry Solr's own values and compute nothing: a replica's {@code state}, {@code
 * leader} and a shard's {@code health} are as Solr reports them. Each record types the properties
 * the tool description tells the model to read and keeps every other property in {@code other},
 * collected by {@link JsonAnySetter} and written back at the same level by {@link JsonAnyGetter},
 * so a property a later Solr version adds is passed on rather than dropped.
 *
 * @param collections each collection by name
 * @param liveNodes the nodes currently live in the cluster, sent by Solr as {@code live_nodes}
 * @param other every other property, such as {@code aliases}, {@code roles} or {@code properties}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterStatus(
    Map<String, Collection> collections,
    @JsonProperty("live_nodes") List<String> liveNodes,
    @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

  /** Solr omits a section with nothing in it; report it as empty instead. */
  public ClusterStatus {
    collections = collections == null ? Map.of() : collections;
    liveNodes = liveNodes == null ? List.of() : liveNodes;
  }

  /**
   * One collection's status.
   *
   * @param health Solr's summary health; before Solr 10.1 it can read GREEN with a replica down
   *     (SOLR-17820), so judge by replica {@code state}
   * @param configName the configset the collection uses
   * @param shards each shard by name
   * @param other every other property, such as {@code router}, {@code replicationFactor} or {@code
   *     znodeVersion}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Collection(
      @Nullable String health,
      @Nullable String configName,
      Map<String, Shard> shards,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

    /** Reports a missing {@code shards} section as empty. */
    public Collection {
      shards = shards == null ? Map.of() : shards;
    }
  }

  /**
   * One shard's status.
   *
   * @param state the shard state, such as {@code active}
   * @param health Solr's summary health for the shard
   * @param range the hash range the shard covers, for the compositeId router
   * @param replicas each replica by core node name
   * @param other every other property
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Shard(
      @Nullable String state,
      @Nullable String health,
      @Nullable String range,
      Map<String, Replica> replicas,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

    /** Reports a missing {@code replicas} section as empty. */
    public Shard {
      replicas = replicas == null ? Map.of() : replicas;
    }
  }

  /**
   * One replica's status.
   *
   * @param core the core name
   * @param nodeName the node hosting the replica, sent by Solr as {@code node_name}
   * @param state the replica state, such as {@code active}, {@code recovering} or {@code down}
   * @param type the replica type, such as {@code NRT}, {@code TLOG} or {@code PULL}
   * @param leader whether the replica is its shard's leader; Solr sends it as the string {@code
   *     "true"} and only on the leader, and it can stay set on a replica that is down
   * @param other every other property, such as {@code base_url} or {@code force_set_state}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Replica(
      @Nullable String core,
      @JsonProperty("node_name") @Nullable String nodeName,
      @Nullable String state,
      @Nullable String type,
      @Nullable Boolean leader,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {}
}
