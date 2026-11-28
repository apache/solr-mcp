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

/**
 * The result of {@code create-collection}: the settings the collection was created with, including
 * the defaults the server applied for anything the client left out.
 *
 * @param name the collection name
 * @param configSet the configset the collection uses
 * @param numShards the number of shards
 * @param replicationFactor the number of replicas per shard
 */
public record CollectionCreationResult(
    String name, String configSet, int numShards, int replicationFactor) {}
