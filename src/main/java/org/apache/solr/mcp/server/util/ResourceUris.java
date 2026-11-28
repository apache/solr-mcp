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
 * The URIs of the server's MCP resources, shared by their {@code @McpResource} declarations and the
 * {@code @McpComplete} handlers that complete their templates, so the two cannot drift apart.
 */
public final class ResourceUris {

  /** The {@code solr://collections} resource: every collection in the cluster. */
  public static final String COLLECTIONS = "solr://collections";

  /** The schema of one collection; {@code {collection}} is completed by the server. */
  public static final String COLLECTION_SCHEMA = "solr://{collection}/schema";

  /** The fields that hold data in one collection; {@code {collection}} is completed. */
  public static final String COLLECTION_FIELDS = "solr://{collection}/fields";

  /** The {@code solr://cluster/status} resource: live nodes and every shard and replica. */
  public static final String CLUSTER_STATUS = "solr://cluster/status";

  private ResourceUris() {
    // Constants holder - prevent instantiation
  }
}
