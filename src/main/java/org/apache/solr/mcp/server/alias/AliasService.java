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
package org.apache.solr.mcp.server.alias;

import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.mcp.server.security.McpAuthorities;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Spring Service providing Solr alias management capabilities for MCP clients.
 *
 * <p>Aliases are virtual collection names that point to one or more physical collections. They
 * enable zero-downtime reindexing, blue-green deployments, and read/write separation by allowing
 * applications to reference a stable name while the underlying collection is swapped transparently.
 *
 * <p>Every tool returns the server's own records ({@link Alias}, {@link AliasDeletion}), built from
 * SolrJ's typed accessors: an alias's targets are a list, not Solr's comma-joined string, and
 * failures are thrown rather than reported in the result.
 *
 * @see CollectionAdminRequest
 */
@Service
@Observed
public class AliasService {

  /** Error message for blank alias name validation */
  private static final String BLANK_ALIAS_NAME_ERROR = "Alias name must not be blank";

  /** Error message for an empty or blank list of target collections */
  private static final String BLANK_COLLECTIONS_ERROR =
      "Collections must name at least one collection, and no name may be blank";

  /** Leads the error text of a failed {@code list-aliases}. */
  static final String LIST_ALIASES_FAILED = "Listing aliases failed";

  /** Leads the error text of a failed {@code create-alias}. */
  static final String CREATE_ALIAS_FAILED = "Creating the alias failed";

  /** Leads the error text of a failed {@code delete-alias}. */
  static final String DELETE_ALIAS_FAILED = "Deleting the alias failed";

  /** SolrJ client for communicating with Solr server */
  private final SolrClient solrClient;

  /**
   * Constructs a new AliasService with the required dependencies.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   */
  public AliasService(SolrClient solrClient) {
    this.solrClient = solrClient;
  }

  /**
   * Lists all aliases defined in the Solr cluster.
   *
   * <p>Invoked by AI clients with natural language requests like "list all aliases", "what aliases
   * exist?", or "show me alias mappings".
   *
   * @return every alias with its target collections and properties, ordered by name
   * @throws IllegalStateException if Solr failed or could not be reached, as {@link SolrCalls}
   *     reports it
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpTool(
      name = "list-aliases",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true),
      description = "List all Solr aliases, the collections they point to, and their properties")
  public List<Alias> listAliases() {
    return aliases(LIST_ALIASES_FAILED);
  }

  /**
   * Reads every alias from Solr.
   *
   * @param operation leads the error text if the call fails
   * @return every alias with its target collections and properties, ordered by name
   */
  private List<Alias> aliases(String operation) {
    CollectionAdminResponse response =
        SolrCalls.call(
            operation,
            SolrCalls.ADMIN_API_NOT_FOUND,
            () -> new CollectionAdminRequest.ListAliases().process(solrClient));
    Map<String, Map<String, String>> properties = response.getAliasProperties();
    List<Alias> aliases = new ArrayList<>();
    for (Map.Entry<String, List<String>> alias :
        new TreeMap<>(response.getAliasesAsLists()).entrySet()) {
      aliases.add(
          new Alias(
              alias.getKey(), alias.getValue(), properties.getOrDefault(alias.getKey(), Map.of())));
    }
    return List.copyOf(aliases);
  }

  /**
   * Creates or updates a Solr alias pointing to one or more collections.
   *
   * <p>If the alias already exists, it is updated to point to the new collection(s) and keeps its
   * properties. This is the mechanism for zero-downtime collection swaps: reindex into a new
   * collection, then update the alias to point to it.
   *
   * <p>Invoked with requests like "create an alias ORDERS pointing to ORDERS_V2" or "swap the LIVE
   * alias to PRODUCTS_V3".
   *
   * @param name the name of the alias to create or update (must not be blank)
   * @param collections the collections the alias should point to (at least one, none blank)
   * @return the alias as Solr holds it after the call
   * @throws IllegalArgumentException if {@code name} is blank or {@code collections} is empty or
   *     holds a blank name, or if Solr refused the request, for example for a collection that does
   *     not exist; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.ADMIN)
  @McpTool(
      name = "create-alias",
      annotations = @McpTool.McpAnnotations(idempotentHint = true),
      description =
          "Create or update a Solr alias pointing to one or more collections. "
              + "If the alias already exists, it is updated to point to the new collection(s).",
      generateOutputSchema = true)
  public Alias createAlias(
      @McpToolParam(description = "Name of the alias to create or update") String name,
      @McpToolParam(description = "Names of the collections the alias should point to")
          List<String> collections) {

    if (name.isBlank()) {
      throw new IllegalArgumentException(BLANK_ALIAS_NAME_ERROR);
    }
    if (collections.isEmpty() || collections.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException(BLANK_COLLECTIONS_ERROR);
    }

    SolrCalls.call(
        CREATE_ALIAS_FAILED,
        SolrCalls.ADMIN_API_NOT_FOUND,
        () ->
            CollectionAdminRequest.createAlias(name, String.join(",", collections))
                .process(solrClient));
    return findAlias(name, CREATE_ALIAS_FAILED)
        .orElseThrow(
            () -> new IllegalStateException("Alias '" + name + "' is missing after creation"));
  }

  /**
   * Deletes an existing Solr alias.
   *
   * <p>Removes the alias definition only — the underlying collection(s) are not affected. The alias
   * is looked up first, so deleting one that does not exist fails on every Solr version.
   *
   * <p>Invoked with requests like "delete the stale alias ORDERS_TEST" or "remove alias
   * OLD_PRODUCTS".
   *
   * @param name the name of the alias to delete (must not be blank)
   * @return the name of the deleted alias
   * @throws IllegalArgumentException if {@code name} is blank or no such alias exists
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.ADMIN)
  @McpTool(
      name = "delete-alias",
      annotations = @McpTool.McpAnnotations(destructiveHint = true),
      description = "Delete a Solr alias. The underlying collection(s) are not affected.",
      generateOutputSchema = true)
  public AliasDeletion deleteAlias(
      @McpToolParam(description = "Name of the alias to delete") String name) {

    if (name.isBlank()) {
      throw new IllegalArgumentException(BLANK_ALIAS_NAME_ERROR);
    }
    if (findAlias(name, DELETE_ALIAS_FAILED).isEmpty()) {
      throw new IllegalArgumentException("Alias '" + name + "' does not exist");
    }

    SolrCalls.call(
        DELETE_ALIAS_FAILED,
        SolrCalls.ADMIN_API_NOT_FOUND,
        () -> CollectionAdminRequest.deleteAlias(name).process(solrClient));
    return new AliasDeletion(name);
  }

  private Optional<Alias> findAlias(String name, String operation) {
    return aliases(operation).stream().filter(alias -> alias.name().equals(name)).findFirst();
  }
}
