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
import java.util.Map;

/**
 * Result of the {@code get-cluster-status} tool, with the answer first.
 *
 * @param healthy true when {@code issues} is empty
 * @param issues every replica that is not active or sits on a node that is not live, and every
 *     active shard without an active leader
 * @param liveNodes the nodes registered as live in ZooKeeper, sorted
 * @param collections the reported collections by name, sorted
 */
public record ClusterStatusResult(
    boolean healthy,
    List<ReplicaIssue> issues,
    List<String> liveNodes,
    Map<String, CollectionState> collections) {}
