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
package org.apache.solr.mcp.server.search;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.springframework.util.StringUtils;

/**
 * Looks up each collection's uniqueKey field with the Schema API and remembers it, so searches on
 * the same collection cost one Schema API call rather than one each.
 *
 * <p>A collection's uniqueKey cannot change while the collection exists. A collection can, however,
 * be deleted and created again under the same name with a different schema, so an entry is trusted
 * only for {@link #TIME_TO_LIVE}. The cache also holds at most {@link #MAX_ENTRIES} collections and
 * drops the least recently used one beyond that, so a server that sees many short-lived collections
 * does not grow without bound.
 */
final class UniqueKeyCache {

  /** How long a looked-up uniqueKey is used before it is looked up again. */
  static final Duration TIME_TO_LIVE = Duration.ofMinutes(5);

  /** The most collections remembered at once. */
  static final int MAX_ENTRIES = 256;

  /** Failure for a schema that declares no uniqueKey, which leaves a hit without an {@code id}. */
  static final String NO_UNIQUE_KEY_ERROR = "The collection's schema declares no uniqueKey field";

  /** A remembered uniqueKey and the {@link #nanoClock} reading after which it is stale. */
  private record Entry(String field, long expiresAtNanos) {}

  private final SolrClient solrClient;

  private final LongSupplier nanoClock;

  /** Access-ordered, so the eldest entry is the least recently used. Guarded by itself. */
  private final Map<String, Entry> entries =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  UniqueKeyCache(SolrClient solrClient) {
    this(solrClient, System::nanoTime);
  }

  /**
   * @param solrClient the client the Schema API requests go through
   * @param nanoClock the time source for expiry, in nanoseconds; tests pass a fake one
   */
  UniqueKeyCache(SolrClient solrClient, LongSupplier nanoClock) {
    this.solrClient = solrClient;
    this.nanoClock = nanoClock;
  }

  /**
   * Returns the name of the collection's uniqueKey field, asking Solr only when no fresh entry is
   * remembered.
   *
   * @param collection the collection to look up
   * @return the uniqueKey field name
   * @throws IllegalStateException if the schema declares no uniqueKey
   * @throws SolrServerException if Solr cannot be reached
   * @throws IOException if the request fails on the wire
   */
  String get(String collection) throws SolrServerException, IOException {
    final long now = nanoClock.getAsLong();
    synchronized (entries) {
      final Entry entry = entries.get(collection);
      if (entry != null && now - entry.expiresAtNanos() < 0) {
        return entry.field();
      }
    }
    // Outside the lock: two searches that miss at once both ask Solr, which is harmless.
    final String field =
        new SchemaRequest.UniqueKey().process(solrClient, collection).getUniqueKey();
    if (!StringUtils.hasText(field)) {
      throw new IllegalStateException(NO_UNIQUE_KEY_ERROR);
    }
    synchronized (entries) {
      entries.put(collection, new Entry(field, now + TIME_TO_LIVE.toNanos()));
    }
    return field;
  }

  /**
   * Forgets the collection's uniqueKey, so the next {@link #get} asks Solr again.
   *
   * @param collection the collection to forget
   */
  void evict(String collection) {
    synchronized (entries) {
      entries.remove(collection);
    }
  }
}
