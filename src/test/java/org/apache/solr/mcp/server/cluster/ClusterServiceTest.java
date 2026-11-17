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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class ClusterServiceTest {

  private static final String SOLR_URL = "http://solr-internal.corp:8983/solr";

  @Mock private SolrClient solrClient;

  private ClusterService clusterService;

  @BeforeEach
  void setUp() {
    clusterService = new ClusterService(solrClient, JsonMapper.shared());
  }

  @Test
  void getClusterStatus_coercesLeaderAndKeepsUntypedProperties() throws Exception {
    SimpleOrderedMap<Object> replica = new SimpleOrderedMap<>();
    replica.add("core", "films_shard1_replica_n1");
    replica.add("node_name", "solr1:8983_solr");
    replica.add("base_url", SOLR_URL);
    replica.add("state", "active");
    replica.add("type", "NRT");
    replica.add("leader", "true");
    SimpleOrderedMap<Object> replicas = new SimpleOrderedMap<>();
    replicas.add("core_node2", replica);
    SimpleOrderedMap<Object> shard = new SimpleOrderedMap<>();
    shard.add("range", "80000000-7fffffff");
    shard.add("state", "active");
    shard.add("replicas", replicas);
    shard.add("health", "GREEN");
    SimpleOrderedMap<Object> shards = new SimpleOrderedMap<>();
    shards.add("shard1", shard);
    SimpleOrderedMap<Object> films = new SimpleOrderedMap<>();
    films.add("router", Map.of("name", "compositeId"));
    films.add("shards", shards);
    films.add("health", "GREEN");
    films.add("configName", "films.AUTOCREATED");
    SimpleOrderedMap<Object> collections = new SimpleOrderedMap<>();
    collections.add("films", films);
    SimpleOrderedMap<Object> cluster = new SimpleOrderedMap<>();
    cluster.add("collections", collections);
    cluster.add("live_nodes", List.of("solr1:8983_solr"));
    cluster.add("futureSection", "x");
    NamedList<Object> response = new NamedList<>();
    response.add("cluster", cluster);
    when(solrClient.request(any(), any())).thenReturn(response);

    ClusterStatus status = clusterService.getClusterStatus("films");

    ClusterStatus.Replica parsed =
        status.collections().get("films").shards().get("shard1").replicas().get("core_node2");
    assertEquals(Boolean.TRUE, parsed.leader());
    assertEquals("solr1:8983_solr", parsed.nodeName());
    assertEquals(SOLR_URL, parsed.other().get("base_url"));
    assertEquals(List.of("solr1:8983_solr"), status.liveNodes());
    assertEquals("x", status.other().get("futureSection"));
    String json = JsonMapper.shared().writeValueAsString(status);
    assertTrue(json.contains("\"leader\":true"), json);
    assertTrue(json.contains("\"router\":{\"name\":\"compositeId\"}"), json);
    assertFalse(json.contains("\"other\""), json);
  }

  @Test
  void listConfigSets_sortsTheNames() throws Exception {
    NamedList<Object> response = new NamedList<>();
    response.add("configSets", List.of("zeta", "_default", "films.AUTOCREATED"));
    when(solrClient.request(any(), any())).thenReturn(response);

    assertEquals(
        List.of("_default", "films.AUTOCREATED", "zeta"),
        clusterService.listConfigSets().configSets());
  }

  @Test
  void getClusterStatusResource_SolrFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(new SolrServerException("Server refused connection at: " + SOLR_URL));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> clusterService.getClusterStatusResource());

    assertEquals(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
    assertFalse(e.getMessage().contains(SOLR_URL));
  }

  @Test
  void getClusterStatusResource_IoFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(new IOException("Connection reset by " + SOLR_URL));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> clusterService.getClusterStatusResource());

    assertEquals(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
    assertFalse(e.getMessage().contains(SOLR_URL));
  }
}
