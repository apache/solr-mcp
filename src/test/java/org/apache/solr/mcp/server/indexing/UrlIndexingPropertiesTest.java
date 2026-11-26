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
package org.apache.solr.mcp.server.indexing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class UrlIndexingPropertiesTest {

  private static final List<String> ANY_HOST = List.of("*");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(5);

  @Test
  void acceptsTheDefaults() {
    assertDoesNotThrow(
        () -> new UrlIndexingProperties(ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT, 4));
  }

  @Test
  void rejectsANonPositiveTotalTimeout() {
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UrlIndexingProperties(
                    ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, Duration.ZERO, 4));
    assertEquals("solr.index-url.total-timeout must be positive", e.getMessage());
  }

  @Test
  void rejectsFewerThanOneConcurrentFetch() {
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UrlIndexingProperties(
                    ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT, 0));
    assertEquals("solr.index-url.max-concurrent-fetches must be at least 1", e.getMessage());
  }
}
