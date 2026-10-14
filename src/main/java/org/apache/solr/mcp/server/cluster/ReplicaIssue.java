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

import org.jspecify.annotations.Nullable;

/**
 * One problem found in an active shard.
 *
 * @param collection the collection holding the shard
 * @param shard the shard name
 * @param replica the replica name; null for a {@code no-leader} issue
 * @param node the node the replica is on; null for a {@code no-leader} issue
 * @param state Solr's replica state; null for a {@code no-leader} issue
 * @param reason {@code not-active}, {@code node-not-live} or {@code no-leader}
 */
public record ReplicaIssue(
    String collection,
    String shard,
    @Nullable String replica,
    @Nullable String node,
    @Nullable String state,
    String reason) {}
