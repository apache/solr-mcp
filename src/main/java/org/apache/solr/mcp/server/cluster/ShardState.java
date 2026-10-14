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

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * State of one shard.
 *
 * @param state Solr's shard state, such as {@code active} or {@code inactive}
 * @param leader the name of the replica marked leader, or null when none is
 * @param replicas the shard's replicas, sorted by name
 */
public record ShardState(String state, @Nullable String leader, List<ReplicaState> replicas) {}
