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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The configsets in the cluster, as the Configsets API {@code LIST} action reports them, returned
 * by the {@code list-configsets} tool.
 *
 * <p>Every property Solr sends besides {@code configSets} and the {@code responseHeader} is kept in
 * {@code other} and written back at the same level, so a property a later Solr version adds is
 * passed on rather than dropped.
 *
 * @param configSets the configset names, sorted
 * @param other every other property Solr sends
 */
@JsonIgnoreProperties("responseHeader")
public record ConfigSetList(
    List<String> configSets, @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

  /** Sorts the names, so clients see a stable order, and reports a missing list as empty. */
  public ConfigSetList {
    configSets = configSets == null ? List.of() : configSets.stream().sorted().toList();
  }
}
