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

import static org.apache.solr.mcp.server.util.JsonUtils.toJson;

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.common.util.NamedList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Service exposing cluster-wide Solr state to MCP clients.
 *
 * <p>The collection tools each report on one collection. This service answers the questions that
 * span the cluster: which nodes are live, which replicas are down or recovering and on which node,
 * and which configsets exist.
 */
@Service
@Observed
public class ClusterService {

  private static final Logger logger = LoggerFactory.getLogger(ClusterService.class);

  private final SolrClient solrClient;

  private final JsonMapper jsonMapper;

  /**
   * Constructs a new ClusterService with the required dependencies.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param jsonMapper the Jackson JsonMapper for the resource's JSON
   */
  public ClusterService(SolrClient solrClient, JsonMapper jsonMapper) {
    this.solrClient = solrClient;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Returns the {@code cluster} section of Solr's CLUSTERSTATUS response as Solr reports it: {@code
   * live_nodes}, then each collection's shards and replicas.
   *
   * <p>The response shape is the same on every supported Solr version, so it is passed through
   * rather than mapped to records. Solr already marks a replica on a node missing from {@code
   * live_nodes} as {@code down}; before Solr 10.1 it computed {@code health} ahead of that
   * correction (SOLR-17820), which the tool description tells the model about.
   *
   * <p>An unknown collection fails in Solr with {@code Collection: <name> not found}, and a Solr
   * not running in SolrCloud mode rejects CLUSTERSTATUS. Both propagate unchanged.
   *
   * @param collection a collection or alias to report on, or null for all collections
   * @return the {@code cluster} section as nested maps and lists
   * @throws SolrServerException if Solr returns an error
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "get-cluster-status",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
      description =
          "Cluster-wide view from Solr's CLUSTERSTATUS: `live_nodes`, then each collection's shards"
              + " and replicas with their `state`, `leader` and `node_name`. Call this first when something is"
              + " wrong. A replica is healthy only when its `state` is `active`, and a shard needs an active"
              + " replica with `leader` true; `leader` can stay true on a replica that is down. Before Solr"
              + " 10.1, `health` can read GREEN while a replica in the same response is down (SOLR-17820),"
              + " so judge by replica `state`, not `health`.")
  @SuppressWarnings("unchecked")
  public Map<String, Object> getClusterStatus(
      @McpToolParam(
              description = "Collection or alias to report on. Omit for all collections.",
              required = false)
          @Nullable String collection)
      throws SolrServerException, IOException {
    CollectionAdminRequest.ClusterStatus request = CollectionAdminRequest.getClusterStatus();
    if (collection != null && !collection.isBlank()) {
      request.setCollectionName(collection);
    }
    NamedList<?> cluster = (NamedList<?>) request.process(solrClient).getResponse().get("cluster");
    // A JSON-parsed tree has no cycles, so asMap's depth guard never applies.
    return cluster.asMap(Integer.MAX_VALUE);
  }

  /**
   * Lists the configsets in the cluster, sorted by name.
   *
   * @return the configset names; never null
   * @throws SolrServerException if Solr returns an error
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "list-configsets",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
      description =
          "List the configsets in the cluster. Names ending in `.AUTOCREATED` are private copies,"
              + " each owned by one collection. Pass any other name as `configSet` to `create-collection`"
              + " to share it.")
  public List<String> listConfigSets() throws SolrServerException, IOException {
    List<String> configSets =
        new ArrayList<>(new ConfigSetAdminRequest.List().process(solrClient).getConfigSets());
    configSets.sort(Comparator.naturalOrder());
    return configSets;
  }

  /**
   * MCP Resource returning the status of the whole cluster as JSON, the same data as {@code
   * get-cluster-status} with no collection.
   *
   * @return the cluster status as JSON, or {@code {"error": ...}} on failure
   */
  @PreAuthorize("isAuthenticated()")
  @McpResource(
      uri = "solr://cluster/status",
      name = "solr-cluster-status",
      description = "Live nodes and the state of every shard and replica in the Solr cluster",
      mimeType = "application/json")
  public String getClusterStatusResource() {
    try {
      return toJson(jsonMapper, getClusterStatus(null));
    } catch (Exception e) {
      logger.error("Failed to get cluster status", e);
      return toJson(jsonMapper, Map.of("error", String.valueOf(e.getMessage())));
    }
  }
}
