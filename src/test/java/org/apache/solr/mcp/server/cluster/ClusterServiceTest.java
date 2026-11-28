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

import static org.apache.solr.mcp.server.McpResultAssertions.resourceJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.json.JsonContent;
import tools.jackson.databind.json.JsonMapper;

@JsonTest
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class ClusterServiceTest {

  private static final String SOLR_URL = "http://solr-internal.corp:8983/solr";

  private static final String NODE = "solr1:8983_solr";

  private static final String OTHER_NODE = "solr2:8983_solr";

  private static final String CLUSTER_STATUS_URI = "solr://cluster/status";

  private static final String FILMS = "$.collections[?(@.name == 'films')]";

  private static final String REPLICAS = FILMS + ".shards[*].replicas[*]";

  @Mock private SolrClient solrClient;

  @Autowired private JsonMapper jsonMapper;

  private ClusterService clusterService;

  @BeforeEach
  void setUp() {
    clusterService = new ClusterService(solrClient, jsonMapper);
  }

  @Test
  void getClusterStatus_mapsSolrsShapeIntoTheRecords() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenReturn(solrMap("cluster", dummyCluster()));

    ClusterStatus status = clusterService.getClusterStatus("films");

    assertThat(status.liveNodes()).containsExactly(NODE, OTHER_NODE);
    ClusterStatus.Collection films = status.collections().getFirst();
    assertThat(films)
        .extracting(
            ClusterStatus.Collection::name,
            ClusterStatus.Collection::health,
            ClusterStatus.Collection::configName)
        .containsExactly("films", "GREEN", "films.AUTOCREATED");
    ClusterStatus.Shard shard = films.shards().getFirst();
    assertThat(shard)
        .isEqualTo(
            new ClusterStatus.Shard(
                "shard1",
                "active",
                "GREEN",
                "80000000-7fffffff",
                List.of(
                    new ClusterStatus.Replica(
                        "core_node2", "films_shard1_replica_n1", NODE, "active", "NRT", true),
                    new ClusterStatus.Replica(
                        "core_node4",
                        "films_shard1_replica_n3",
                        OTHER_NODE,
                        "down",
                        "NRT",
                        false))));
  }

  @Test
  void getClusterStatus_sortsEveryListByName() throws Exception {
    SimpleOrderedMap<Object> cluster =
        solrMap(
            "collections",
            solrMap(
                "zeta",
                solrMap("shards", solrMap("shard2", solrMap(), "shard1", solrMap())),
                "alpha",
                solrMap()),
            "live_nodes",
            List.of(OTHER_NODE, NODE));
    when(solrClient.request(any(), nullable(String.class))).thenReturn(solrMap("cluster", cluster));

    ClusterStatus status = clusterService.getClusterStatus(null);

    assertThat(status.liveNodes()).containsExactly(NODE, OTHER_NODE);
    assertThat(status.collections())
        .extracting(ClusterStatus.Collection::name)
        .containsExactly("alpha", "zeta");
    assertThat(status.collections().getLast().shards())
        .extracting(ClusterStatus.Shard::name)
        .containsExactly("shard1", "shard2");
    assertThat(status.collections().getFirst().shards()).isEmpty();
  }

  @Test
  void getClusterStatus_reportsAMissingSectionAsEmpty() throws Exception {
    when(solrClient.request(any(), nullable(String.class))).thenReturn(solrMap());

    ClusterStatus status = clusterService.getClusterStatus(null);

    assertThat(status.liveNodes()).isEmpty();
    assertThat(status.collections()).isEmpty();
  }

  @Test
  void getClusterStatus_writesServerOwnedNamesOnly() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenReturn(solrMap("cluster", dummyCluster()));

    JsonContent json =
        new JsonContent(jsonMapper.writeValueAsString(clusterService.getClusterStatus("films")));

    assertThat(json).extractingPath("$.liveNodes").asArray().containsExactly(NODE, OTHER_NODE);
    assertThat(json).extractingPath(REPLICAS + ".leader").asArray().containsExactly(true, false);
    assertThat(json)
        .extractingPath(REPLICAS + ".nodeName")
        .asArray()
        .containsExactly(NODE, OTHER_NODE);
    // Solr's own keys and the properties the records do not name are dropped. A deep scan never
    // throws PathNotFoundException, so doesNotHavePath would always fail here.
    for (String dropped : List.of("live_nodes", "node_name", "base_url", "router", "other")) {
      assertThat(json).extractingPath("$.." + dropped).asArray().as(dropped).isEmpty();
    }
  }

  @Test
  void getClusterStatus_leavesOutAbsentOptionalProperties() throws Exception {
    SimpleOrderedMap<Object> cluster =
        solrMap(
            "collections",
            solrMap(
                "films",
                solrMap(
                    "shards", solrMap("shard1", solrMap("replicas", solrMap("r1", solrMap()))))),
            "live_nodes",
            List.of(NODE));
    when(solrClient.request(any(), nullable(String.class))).thenReturn(solrMap("cluster", cluster));

    JsonContent json =
        new JsonContent(jsonMapper.writeValueAsString(clusterService.getClusterStatus(null)));

    assertThat(json)
        .extractingPath(FILMS)
        .asArray()
        .containsExactly(
            Map.of(
                "name",
                "films",
                "shards",
                List.of(
                    Map.of(
                        "name",
                        "shard1",
                        "replicas",
                        List.of(Map.of("name", "r1", "leader", false))))));
  }

  @Test
  void getClusterStatus_unknownCollection_carriesSolrsReasonAndTheListCollectionsHint()
      throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenThrow(
            new RemoteSolrException(
                SOLR_URL, 400, Map.of("msg", "Collection: nope not found", "code", 400)));

    assertThatThrownBy(() -> clusterService.getClusterStatus("nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            ClusterService.CLUSTER_STATUS_FAILED
                + ": "
                + SolrCalls.SOLR_REASON_LEAD
                + "Collection: nope not found"
                + SolrCalls.LIST_COLLECTIONS_HINT)
        .hasNoCause();
  }

  @Test
  void getClusterStatus_notSolrCloud_carriesSolrsReasonWithoutTheHint() throws Exception {
    String notCloud = "Solr instance is not running in SolrCloud mode.";
    when(solrClient.request(any(), nullable(String.class)))
        .thenThrow(new RemoteSolrException(SOLR_URL, 400, Map.of("msg", notCloud, "code", 400)));

    assertThatThrownBy(() -> clusterService.getClusterStatus("films"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            ClusterService.CLUSTER_STATUS_FAILED + ": " + SolrCalls.SOLR_REASON_LEAD + notCloud);
  }

  @Test
  void getClusterStatus_unreachable_isTranslated() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenThrow(new SolrServerException("Server refused connection at: " + SOLR_URL));

    assertThatThrownBy(() -> clusterService.getClusterStatus(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(ClusterService.CLUSTER_STATUS_FAILED + ": " + SolrCalls.UNREACHABLE)
        .hasNoCause();
  }

  @Test
  void getClusterStatusResource_writesTheToolsJson() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenReturn(solrMap("cluster", dummyCluster()));

    String json =
        resourceJson(
            clusterService.getClusterStatusResource(new ReadResourceRequest(CLUSTER_STATUS_URI)),
            CLUSTER_STATUS_URI);

    assertThat(new JsonContent(json))
        .isStrictlyEqualTo(jsonMapper.writeValueAsString(clusterService.getClusterStatus(null)));
  }

  @Test
  void getClusterStatusResource_SolrFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenThrow(new SolrServerException("Server refused connection at: " + SOLR_URL));
    ReadResourceRequest request = new ReadResourceRequest(CLUSTER_STATUS_URI);

    assertThatThrownBy(() -> clusterService.getClusterStatusResource(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR)
        .hasNoCause();
  }

  @Test
  void getClusterStatusResource_IoFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenThrow(new IOException("Connection reset by " + SOLR_URL));
    ReadResourceRequest request = new ReadResourceRequest(CLUSTER_STATUS_URI);

    assertThatThrownBy(() -> clusterService.getClusterStatusResource(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR)
        .hasNoCause();
  }

  @Test
  void listConfigSets_sortsTheNames() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenReturn(solrMap("configSets", List.of("zeta", "_default", "films.AUTOCREATED")));

    assertThat(clusterService.listConfigSets())
        .containsExactly(
            new ConfigSet("_default"), new ConfigSet("films.AUTOCREATED"), new ConfigSet("zeta"));
  }

  @Test
  void listConfigSets_reportsAMissingListAsEmpty() throws Exception {
    when(solrClient.request(any(), nullable(String.class))).thenReturn(solrMap());

    assertThat(clusterService.listConfigSets()).isEmpty();
  }

  @Test
  void listConfigSets_unreachable_isTranslated() throws Exception {
    when(solrClient.request(any(), nullable(String.class)))
        .thenThrow(new IOException("Connection reset by " + SOLR_URL));

    assertThatThrownBy(() -> clusterService.listConfigSets())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(ClusterService.LIST_CONFIGSETS_FAILED + ": " + SolrCalls.UNREACHABLE)
        .hasNoCause();
  }

  /**
   * The {@code cluster} section of a CLUSTERSTATUS response for one collection, {@code films}, with
   * one shard: an active NRT leader on {@link #NODE} and a down replica on {@link #OTHER_NODE}.
   * Values are written as Solr sends them, including {@code "leader": "true"} as a string, and
   * {@code live_nodes} out of order.
   */
  private static SimpleOrderedMap<Object> dummyCluster() {
    SimpleOrderedMap<Object> leader =
        solrMap(
            "core", "films_shard1_replica_n1",
            "node_name", NODE,
            "base_url", SOLR_URL,
            "state", "active",
            "type", "NRT",
            "leader", "true");
    SimpleOrderedMap<Object> down =
        solrMap(
            "core", "films_shard1_replica_n3",
            "node_name", OTHER_NODE,
            "base_url", "http://solr2:8983/solr",
            "state", "down",
            "type", "NRT");
    SimpleOrderedMap<Object> shard =
        solrMap(
            "range", "80000000-7fffffff",
            "state", "active",
            "replicas", solrMap("core_node4", down, "core_node2", leader),
            "health", "GREEN");
    SimpleOrderedMap<Object> films =
        solrMap(
            "router",
            solrMap("name", "compositeId"),
            "shards",
            solrMap("shard1", shard),
            "health",
            "GREEN",
            "configName",
            "films.AUTOCREATED");
    return solrMap("collections", solrMap("films", films), "live_nodes", List.of(OTHER_NODE, NODE));
  }

  /** An ordered Solr map from alternating keys and values, the shape SolrJ parses JSON into. */
  private static SimpleOrderedMap<Object> solrMap(Object... keysAndValues) {
    SimpleOrderedMap<Object> map = new SimpleOrderedMap<>();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      map.add((String) keysAndValues[index], keysAndValues[index + 1]);
    }
    return map;
  }
}
