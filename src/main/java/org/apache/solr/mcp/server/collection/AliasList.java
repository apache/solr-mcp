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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * The aliases in the cluster, as the Collections API {@code LISTALIASES} action reports them,
 * returned by the {@code list-aliases} tool.
 *
 * <p>Every property Solr sends besides these and the {@code responseHeader} is kept in {@code
 * other} and written back at the same level, so a property a later Solr version adds is passed on
 * rather than dropped.
 *
 * @param aliases each alias and the comma-separated collections it points to, as Solr sends them
 * @param properties each alias's properties, for the aliases that have any
 * @param other every other property Solr sends
 */
@JsonIgnoreProperties("responseHeader")
public record AliasList(
    Map<String, String> aliases,
    Map<String, Map<String, String>> properties,
    @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

  /**
   * A listing of these aliases and nothing else.
   *
   * @param aliases each alias and the comma-separated collections it points to
   */
  public AliasList(Map<String, String> aliases) {
    this(aliases, Map.of(), Map.of());
  }

  /** Solr omits a section with nothing in it; report it as empty instead. */
  public AliasList {
    aliases = aliases == null ? Map.of() : aliases;
    properties = properties == null ? Map.of() : properties;
  }
}
