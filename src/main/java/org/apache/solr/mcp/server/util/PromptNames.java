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
package org.apache.solr.mcp.server.util;

/**
 * Canonical names of every {@code @McpPrompt} exposed by this server.
 *
 * <p>MCP's {@code completion/complete} protocol matches a {@code PromptReference} by string name
 * against the registered completion handlers, so the strings in {@code @McpPrompt(name = ...)} and
 * {@code @McpComplete(prompt = ...)} must be byte-identical. The Spring AI registry does no
 * cross-checking — a typo on either side compiles cleanly and only surfaces at runtime as {@code
 * -32602: AsyncCompletionSpecification not found}.
 *
 * <p>Routing both annotations through a single constant turns that class of bug into a compile
 * error: deleting or renaming a prompt forces the corresponding {@code @McpComplete} site to update
 * or fail to compile.
 */
public final class PromptNames {

  /** {@code @McpPrompt} on {@code CollectionService#exploreCollectionsPrompt}. */
  public static final String EXPLORE_COLLECTIONS = "explore-collections";

  /**
   * Description of {@link #EXPLORE_COLLECTIONS}, shared by its {@code @McpPrompt} and its prompt
   * result.
   */
  public static final String EXPLORE_COLLECTIONS_DESCRIPTION =
      "Read-only walkthrough: list collections and characterise each by stats and health.";

  /** {@code @McpPrompt} on {@code CollectionService#setupCollectionPrompt}. */
  public static final String SETUP_COLLECTION = "setup-collection";

  /**
   * Description of {@link #SETUP_COLLECTION}, shared by its {@code @McpPrompt} and its prompt
   * result.
   */
  public static final String SETUP_COLLECTION_DESCRIPTION =
      "Guided workflow: validate a name, pick configset / shards / replication factor, create the collection, and verify it.";

  /** {@code @McpPrompt} on {@code SearchService#searchCollectionPrompt}. */
  public static final String SEARCH_COLLECTION = "search-collection";

  /**
   * Description of {@link #SEARCH_COLLECTION}, shared by its {@code @McpPrompt} and its prompt
   * result.
   */
  public static final String SEARCH_COLLECTION_DESCRIPTION =
      "Guides the assistant through inspecting the schema, translating a user question into a Solr query, running the search, and refining the result.";

  /** {@code @McpPrompt} on {@code IndexingService#indexDataPrompt}. */
  public static final String INDEX_DATA = "index-data";

  /** Description of {@link #INDEX_DATA}, shared by its {@code @McpPrompt} and its prompt result. */
  public static final String INDEX_DATA_DESCRIPTION =
      "Guides the assistant through verifying the target schema, picking the right indexing tool for the input format, and confirming the result.";

  /** {@code @McpPrompt} on {@code SchemaService#viewSchemaPrompt}. */
  public static final String VIEW_SCHEMA = "view-schema";

  /**
   * Description of {@link #VIEW_SCHEMA}, shared by its {@code @McpPrompt} and its prompt result.
   */
  public static final String VIEW_SCHEMA_DESCRIPTION =
      "Read-only walkthrough: fetch the schema and summarize fields, types, dynamic fields, copy fields, and the unique key.";

  /** {@code @McpPrompt} on {@code SchemaService#designSchemaPrompt}. */
  public static final String DESIGN_SCHEMA = "design-schema";

  /**
   * Description of {@link #DESIGN_SCHEMA}, shared by its {@code @McpPrompt} and its prompt result.
   */
  public static final String DESIGN_SCHEMA_DESCRIPTION =
      "Guides the assistant through inspecting an existing Solr schema, choosing appropriate field types, and applying additive schema changes via the Schema API.";

  private PromptNames() {
    // Constants holder - prevent instantiation
  }
}
