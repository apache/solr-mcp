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

import io.micrometer.observation.annotation.Observed;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.ConfigSetAdminResponse;
import org.apache.solr.mcp.server.util.McpResults;
import org.apache.solr.mcp.server.util.SolrCalls;
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
 *
 * <p>Every tool returns the server's own records ({@link ClusterStatus}, {@link ConfigSet}), mapped
 * from SolrJ's responses: names are camelCase, {@code leader} is a boolean, and every list is
 * sorted by name.
 */
@Service
@Observed
public class ClusterService {

  private static final Logger logger = LoggerFactory.getLogger(ClusterService.class);

  /** The fixed error text of a failed read of the {@code solr://cluster/status} resource. */
  static final String CLUSTER_STATUS_RESOURCE_ERROR =
      "Failed to read the Solr cluster status. Retry later; if it persists,"
          + " ask the operator to check Solr availability and the server logs.";

  /** Leads the error text of a failed {@code get-cluster-status}. */
  static final String CLUSTER_STATUS_FAILED = "Getting the cluster status failed";

  /** Leads the error text of a failed {@code list-configsets}. */
  static final String LIST_CONFIGSETS_FAILED = "Listing configsets failed";

  /** The section of a CLUSTERSTATUS response that holds the cluster's state. */
  private static final String CLUSTER_SECTION = "cluster";

  private final SolrClient solrClient;

  private final JsonMapper jsonMapper;

  /**
   * Constructs a new ClusterService with the required dependencies.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param jsonMapper the Jackson JsonMapper that writes the resource's JSON
   */
  public ClusterService(SolrClient solrClient, JsonMapper jsonMapper) {
    this.solrClient = solrClient;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Reports the cluster's live nodes and the state of every shard and replica, from Solr's
   * CLUSTERSTATUS.
   *
   * <p>The values are Solr's own: Solr already marks a replica on a node missing from the live
   * nodes as {@code down}. Before Solr 10.1 it computed {@code health} ahead of that correction
   * (SOLR-17820), which the tool description tells the model about.
   *
   * <p>Solr refuses CLUSTERSTATUS with a 400 for an unknown collection ({@code Collection: <name>
   * not found}) and on a Solr not running in SolrCloud mode; {@link SolrCalls} passes its reason
   * on, with the {@code list-collections} hint when the reason names the collection.
   *
   * @param collection a collection or alias to report on, or null for all collections
   * @return the live nodes and the collections, each list sorted
   * @throws IllegalArgumentException if Solr refused the request, for example for a collection that
   *     does not exist; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "get-cluster-status",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
      description =
          "Cluster-wide view from Solr's CLUSTERSTATUS: liveNodes, then each collection's shards"
              + " and replicas with their state, leader and nodeName. Call this first when"
              + " something is wrong. A replica is healthy only when its state is active, and a"
              + " shard needs an active replica with leader true; leader can stay true on a"
              + " replica that is down. Before Solr 10.1, health can read GREEN while a replica in"
              + " the same response is down (SOLR-17820), so judge by replica state, not health.",
      generateOutputSchema = true)
  public ClusterStatus getClusterStatus(
      @McpToolParam(
              description = "Collection or alias to report on. Omit for all collections.",
              required = false)
          @Nullable String collection) {
    CollectionAdminRequest.ClusterStatus request = CollectionAdminRequest.getClusterStatus();
    String notFound = SolrCalls.ADMIN_API_NOT_FOUND;
    SolrCalls.ReasonHint hint = SolrCalls.ReasonHint.NONE;
    if (collection != null && !collection.isBlank()) {
      String named = collection;
      request.setCollectionName(named);
      notFound = SolrCalls.collectionNotFound(named);
      hint = (statusCode, reason) -> reason.contains(named) ? SolrCalls.LIST_COLLECTIONS_HINT : "";
    }
    CollectionAdminResponse response =
        SolrCalls.call(CLUSTER_STATUS_FAILED, notFound, hint, () -> request.process(solrClient));
    return ClusterStatus.fromClusterStatus(
        response.getResponse().get(CLUSTER_SECTION) instanceof Map<?, ?> cluster
            ? cluster
            : Map.of());
  }

  /**
   * Lists the configsets in the cluster, sorted by name.
   *
   * @return the configsets, sorted by name
   * @throws IllegalStateException if Solr failed or could not be reached, as {@link SolrCalls}
   *     reports it
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "list-configsets",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
      description =
          "List the configsets in the cluster. Names ending in .AUTOCREATED are private copies,"
              + " each owned by one collection. Pass any other name as configSet to"
              + " create-collection to share it.")
  public List<ConfigSet> listConfigSets() {
    ConfigSetAdminResponse.List response =
        SolrCalls.call(
            LIST_CONFIGSETS_FAILED,
            SolrCalls.ADMIN_API_NOT_FOUND,
            () -> new ConfigSetAdminRequest.List().process(solrClient));
    List<String> names = response.getConfigSets();
    return names == null ? List.of() : names.stream().sorted().map(ConfigSet::new).toList();
  }

  /**
   * MCP Resource returning the status of the whole cluster, the same JSON as {@code
   * get-cluster-status} with no collection.
   *
   * @param request the read request, whose URI the result echoes
   * @return the cluster status as one {@code application/json} resource content
   * @throws IllegalStateException with {@link #CLUSTER_STATUS_RESOURCE_ERROR} if the status cannot
   *     be read or serialized
   */
  @PreAuthorize("isAuthenticated()")
  @McpResource(
      uri = "solr://cluster/status",
      name = "solr-cluster-status",
      description = "Live nodes and the state of every shard and replica in the Solr cluster",
      mimeType = "application/json")
  public ReadResourceResult getClusterStatusResource(ReadResourceRequest request) {
    try {
      return McpResults.jsonResource(
          request.uri(), jsonMapper.writeValueAsString(getClusterStatus(null)));
    } catch (IllegalArgumentException | IllegalStateException _) {
      // SolrCalls' translation of a failed request; SolrCalls has already logged the cause.
      throw new IllegalStateException(CLUSTER_STATUS_RESOURCE_ERROR);
    } catch (Exception e) {
      // Deliberately no cause: MCP reports the root cause's message to the client,
      // and Solr and SolrJ failure text can carry internal host names, URLs and
      // paths. The log is the sole record of the cause.
      logger.error("Failed to get the cluster status for the cluster status resource", e);
      throw new IllegalStateException(CLUSTER_STATUS_RESOURCE_ERROR);
    }
  }
}
