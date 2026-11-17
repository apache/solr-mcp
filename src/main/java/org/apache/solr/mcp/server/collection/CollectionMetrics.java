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
 * The result of {@code get-collection-stats}: index, cache and handler statistics.
 *
 * @param index document and segment counts
 * @param caches cache statistics, when this Solr version reports them (Solr 10 does not)
 * @param handlers request-handler statistics, when this Solr version reports them
 * @param metricsCore the core registry the cache and handler statistics were read from, such as
 *     {@code solr.core.films.shard1.replica_n1}; they describe that one core, not the whole
 *     collection (absent when Solr reported no metrics for the collection)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionMetrics(
    IndexStats index,
    @Nullable CacheStats caches,
    @Nullable HandlerStats handlers,
    @Nullable String metricsCore) {

  /**
   * Document and segment counts from Luke.
   *
   * @param numDocs the number of live documents
   * @param segmentCount the number of index segments
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record IndexStats(@Nullable Integer numDocs, @Nullable Integer segmentCount) {}

  /**
   * Statistics of the three standard searcher caches.
   *
   * @param queryResultCache the query result cache
   * @param documentCache the document cache
   * @param filterCache the filter cache
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CacheStats(
      @Nullable CacheInfo queryResultCache,
      @Nullable CacheInfo documentCache,
      @Nullable CacheInfo filterCache) {}

  /**
   * Statistics of one cache.
   *
   * @param lookups the number of lookups
   * @param hits the number of lookups that hit
   * @param hitRatio hits divided by lookups; 1.0 for a cache that has had no lookups
   * @param inserts the number of inserts
   * @param evictions the number of evictions
   * @param size the number of entries
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CacheInfo(
      @Nullable Long lookups,
      @Nullable Long hits,
      @Nullable Float hitRatio,
      @Nullable Long inserts,
      @Nullable Long evictions,
      @Nullable Long size) {}

  /**
   * Statistics of the select and update request handlers.
   *
   * @param selectHandler the {@code /select} handler
   * @param updateHandler the {@code /update} handler
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HandlerStats(
      @Nullable HandlerInfo selectHandler, @Nullable HandlerInfo updateHandler) {}

  /**
   * Statistics of one request handler.
   *
   * @param requests the number of requests
   * @param errors the number of requests that failed
   * @param timeouts the number of requests that timed out
   * @param totalTime the total time spent, in nanoseconds
   * @param avgTimePerRequest the mean time per request, in nanoseconds
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HandlerInfo(
      @Nullable Long requests,
      @Nullable Long errors,
      @Nullable Long timeouts,
      @Nullable Long totalTime,
      @Nullable Float avgTimePerRequest) {}
}
