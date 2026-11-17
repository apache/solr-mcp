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
package org.apache.solr.mcp.server.collection;

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.Date;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Service providing Solr alias management capabilities for MCP clients.
 *
 * <p>Aliases are virtual collection names that point to one or more physical collections. They
 * enable zero-downtime reindexing, blue-green deployments, and read/write separation by allowing
 * applications to reference a stable name while the underlying collection is swapped transparently.
 *
 * <p><strong>Core Capabilities:</strong>
 *
 * <ul>
 *   <li><strong>List Aliases</strong>: Discover all aliases and their target collections
 *   <li><strong>Create/Update Alias</strong>: Point an alias to one or more collections (creates if
 *       new, updates if existing)
 *   <li><strong>Delete Alias</strong>: Remove an alias without affecting the underlying collections
 * </ul>
 *
 * @see CollectionAdminRequest
 */
@Service
@Observed
public class AliasService {

  /** Error message for blank alias name validation */
  private static final String BLANK_ALIAS_NAME_ERROR = "Alias name must not be blank";

  /** Error message for blank collections validation */
  private static final String BLANK_COLLECTIONS_ERROR = "Collections must not be blank";

  /** SolrJ client for communicating with Solr server */
  private final SolrClient solrClient;

  /** Converts Solr's {@code LISTALIASES} response into {@link AliasList} */
  private final JsonMapper objectMapper;

  /**
   * Constructs a new AliasService with the required dependencies.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param objectMapper the Jackson JsonMapper that converts Solr responses into records
   */
  public AliasService(SolrClient solrClient, JsonMapper objectMapper) {
    this.solrClient = solrClient;
    this.objectMapper = objectMapper;
  }

  /**
   * Lists all aliases defined in the Solr cluster.
   *
   * <p>Returns a map where each key is an alias name and the corresponding value is the
   * comma-separated list of collection names that the alias points to.
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>Invoked by AI clients with natural language requests like "list all aliases", "what aliases
   * exist?", or "show me alias mappings".
   *
   * @return the aliases and their properties, as the {@code LISTALIASES} action reports them; both
   *     maps are empty when no aliases are defined
   * @throws SolrServerException if there are errors communicating with Solr
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "list-aliases",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description = "List all Solr aliases and the collections they point to")
  public AliasList listAliases() throws SolrServerException, IOException {
    CollectionAdminRequest.ListAliases request = new CollectionAdminRequest.ListAliases();
    CollectionAdminResponse response = request.process(solrClient);
    // A JSON-parsed tree has no cycles, so asMap's depth guard never applies.
    return objectMapper.convertValue(
        response.getResponse().asMap(Integer.MAX_VALUE), AliasList.class);
  }

  /**
   * Creates or updates a Solr alias pointing to one or more collections.
   *
   * <p>If the alias already exists, it is updated to point to the new collection(s). This is the
   * mechanism for zero-downtime collection swaps: reindex into a new collection, then update the
   * alias to point to it.
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>Invoked with requests like "create an alias ORDERS pointing to ORDERS_V2" or "swap the LIVE
   * alias to PRODUCTS_V3".
   *
   * @param aliasName the name of the alias to create or update (must not be blank)
   * @param collections comma-separated list of target collection names (must not be blank)
   * @return result describing the outcome of the operation
   * @throws IllegalArgumentException if aliasName or collections is blank
   * @throws SolrServerException if Solr returns an error
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "create-alias",
      annotations = @McpTool.McpAnnotations(idempotentHint = true),
      description =
          "Create or update a Solr alias pointing to one or more collections. "
              + "If the alias already exists, it is updated to point to the new collection(s).")
  public AliasResult createAlias(
      @McpToolParam(description = "Name of the alias to create or update") String aliasName,
      @McpToolParam(
              description = "Comma-separated list of collection names the alias should point to")
          String collections)
      throws SolrServerException, IOException {

    if (aliasName.isBlank()) {
      throw new IllegalArgumentException(BLANK_ALIAS_NAME_ERROR);
    }
    if (collections.isBlank()) {
      throw new IllegalArgumentException(BLANK_COLLECTIONS_ERROR);
    }

    CollectionAdminRequest.CreateAlias request =
        CollectionAdminRequest.createAlias(aliasName, collections);
    CollectionAdminResponse response = request.process(solrClient);
    boolean success = response.getStatus() == 0;

    return new AliasResult(
        aliasName,
        collections,
        success,
        success ? "Alias created/updated successfully" : "Alias creation/update failed",
        new Date());
  }

  /**
   * Deletes an existing Solr alias.
   *
   * <p>Removes the alias definition only — the underlying collection(s) are not affected. After
   * deletion, the alias name is no longer resolvable.
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>Invoked with requests like "delete the stale alias ORDERS_TEST" or "remove alias
   * OLD_PRODUCTS".
   *
   * @param aliasName the name of the alias to delete (must not be blank)
   * @return result describing the outcome of the operation
   * @throws IllegalArgumentException if aliasName is blank
   * @throws SolrServerException if Solr returns an error
   * @throws IOException if there are I/O errors during communication
   */
  @PreAuthorize("isAuthenticated()")
  @McpTool(
      name = "delete-alias",
      annotations = @McpTool.McpAnnotations(destructiveHint = true),
      description = "Delete a Solr alias. The underlying collection(s) are not affected.")
  public AliasResult deleteAlias(
      @McpToolParam(description = "Name of the alias to delete") String aliasName)
      throws SolrServerException, IOException {

    if (aliasName.isBlank()) {
      throw new IllegalArgumentException(BLANK_ALIAS_NAME_ERROR);
    }

    CollectionAdminRequest.DeleteAlias request = CollectionAdminRequest.deleteAlias(aliasName);
    CollectionAdminResponse response = request.process(solrClient);
    boolean success = response.getStatus() == 0;

    return new AliasResult(
        aliasName,
        null,
        success,
        success ? "Alias deleted successfully" : "Alias deletion failed",
        new Date());
  }
}
