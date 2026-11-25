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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@code check-health}. An unhealthy collection is an answer, not a tool error.
 *
 * @param collection the collection that was checked
 * @param healthy whether the collection answered both a ping and a query
 * @param pingMillis how long the ping took, when it succeeded
 * @param numDocs the collection's document count, when the query succeeded
 * @param reason why the collection is unhealthy; absent when it is healthy
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthStatus(
    String collection,
    boolean healthy,
    @Nullable Long pingMillis,
    @Nullable Long numDocs,
    @Nullable Reason reason) {

  /** A healthy collection. */
  static HealthStatus healthy(String collection, long pingMillis, long numDocs) {
    return new HealthStatus(collection, true, pingMillis, numDocs, null);
  }

  /** An unhealthy collection. */
  static HealthStatus unhealthy(String collection, Reason reason) {
    return new HealthStatus(collection, false, null, null, reason);
  }

  /**
   * Why a collection is unhealthy. These are the server's fixed values: Solr's exception text can
   * carry host names, URLs and credentials, so it goes to the log only.
   */
  public enum Reason {
    /** Solr reports no collection of that name. */
    COLLECTION_NOT_FOUND,
    /** Solr could not be reached at all. */
    SOLR_UNREACHABLE,
    /** Solr was reached, but the ping failed. */
    PING_FAILED,
    /** The ping succeeded, but a match-all query failed. */
    QUERY_FAILED
  }
}
