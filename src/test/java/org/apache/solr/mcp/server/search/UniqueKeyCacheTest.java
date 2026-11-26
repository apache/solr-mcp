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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

/** Unit tests for {@link UniqueKeyCache} with a mocked SolrClient and a fake clock. */
@DisabledInNativeImage
class UniqueKeyCacheTest {

  private static final String COLLECTION = "books";

  private final AtomicLong nanos = new AtomicLong();

  private SolrClient solrClient;

  private UniqueKeyCache cache;

  @BeforeEach
  void setUp() throws Exception {
    solrClient = mock(SolrClient.class);
    NamedList<Object> response = new NamedList<>();
    response.add("uniqueKey", "sku");
    when(solrClient.request(any(SchemaRequest.UniqueKey.class), any())).thenReturn(response);
    cache = new UniqueKeyCache(solrClient, nanos::get);
  }

  @Test
  void repeatedLookupsWithinTheTimeToLiveAskSolrOnce() throws Exception {
    assertEquals(Optional.of("sku"), cache.get(COLLECTION));
    nanos.addAndGet(UniqueKeyCache.TIME_TO_LIVE.toNanos() - 1);
    assertEquals(Optional.of("sku"), cache.get(COLLECTION));
    verify(solrClient, times(1)).request(any(SchemaRequest.UniqueKey.class), eq(COLLECTION));
  }

  @Test
  void anExpiredEntryIsLookedUpAgain() throws Exception {
    cache.get(COLLECTION);
    nanos.addAndGet(UniqueKeyCache.TIME_TO_LIVE.toNanos());
    cache.get(COLLECTION);
    verify(solrClient, times(2)).request(any(SchemaRequest.UniqueKey.class), eq(COLLECTION));
  }

  @Test
  void anEvictedEntryIsLookedUpAgain() throws Exception {
    cache.get(COLLECTION);
    cache.evict(COLLECTION);
    cache.get(COLLECTION);
    verify(solrClient, times(2)).request(any(SchemaRequest.UniqueKey.class), eq(COLLECTION));
  }

  @Test
  void theLeastRecentlyUsedEntryIsDroppedBeyondTheBound() throws Exception {
    for (int index = 0; index <= UniqueKeyCache.MAX_ENTRIES; index++) {
      cache.get("collection_" + index);
    }
    // collection_0 was the eldest of MAX_ENTRIES + 1 and has been dropped; the newest stays.
    cache.get("collection_0");
    cache.get("collection_" + UniqueKeyCache.MAX_ENTRIES);
    verify(solrClient, times(2)).request(any(SchemaRequest.UniqueKey.class), eq("collection_0"));
    verify(solrClient, times(1))
        .request(
            any(SchemaRequest.UniqueKey.class), eq("collection_" + UniqueKeyCache.MAX_ENTRIES));
  }

  @Test
  void aSchemaWithoutUniqueKeyIsEmptyAndNotRemembered() throws Exception {
    when(solrClient.request(any(SchemaRequest.UniqueKey.class), eq("keyless")))
        .thenReturn(new NamedList<>());
    assertEquals(Optional.empty(), cache.get("keyless"));
    assertEquals(Optional.empty(), cache.get("keyless"));
    verify(solrClient, times(2)).request(any(SchemaRequest.UniqueKey.class), eq("keyless"));
  }
}
