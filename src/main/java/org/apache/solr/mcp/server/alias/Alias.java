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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A collection alias, as returned by {@code list-aliases} and {@code create-alias}.
 *
 * @param name the alias name
 * @param collections the collections the alias points to, in Solr's order
 * @param properties the alias's properties, ordered by key; empty when it has none
 */
public record Alias(String name, List<String> collections, Map<String, String> properties) {

  /** Copies both collections so the record cannot change after it is built. */
  public Alias {
    collections = List.copyOf(collections);
    properties = Collections.unmodifiableMap(new TreeMap<>(properties));
  }
}
