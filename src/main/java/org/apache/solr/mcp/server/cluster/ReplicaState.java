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

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.Nullable;

/**
 * State of one replica.
 *
 * @param name the replica name, such as {@code core_node2}
 * @param core the core backing the replica
 * @param node the node the replica is on
 * @param state Solr's replica state: {@code active}, {@code down}, {@code recovering} or {@code
 *     recovery_failed}
 * @param type the replica type: {@code NRT}, {@code TLOG} or {@code PULL}
 * @param leader whether the replica is its shard's leader
 * @param baseUrl the node's base URL, for callers that address the core directly; left out of the
 *     tool output because it repeats {@code node}
 */
public record ReplicaState(
    String name,
    String core,
    String node,
    String state,
    String type,
    boolean leader,
    @JsonIgnore @Nullable String baseUrl) {}
