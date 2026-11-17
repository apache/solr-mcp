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
import java.util.List;
import java.util.Map;

/**
 * The collections in the cluster, as the Collections API {@code LIST} action reports them, returned
 * by the {@code list-collections} tool and the {@code solr://collections} resource.
 *
 * <p>Every property Solr sends besides {@code collections} and the {@code responseHeader} is kept
 * in {@code other} and written back at the same level, so a property a later Solr version adds is
 * passed on rather than dropped.
 *
 * @param collections the collection names
 * @param other every other property Solr sends
 */
@JsonIgnoreProperties("responseHeader")
public record CollectionList(
    List<String> collections, @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

  /**
   * A listing of these collections and nothing else.
   *
   * @param collections the collection names
   */
  public CollectionList(List<String> collections) {
    this(collections, Map.of());
  }

  /** Reports a missing {@code collections} section as an empty list. */
  public CollectionList {
    collections = collections == null ? List.of() : collections;
  }
}
