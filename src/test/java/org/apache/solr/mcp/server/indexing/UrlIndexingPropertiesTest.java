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

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.support.ResourcePropertySource;

class UrlIndexingPropertiesTest {

  private static final List<String> ANY_HOST = List.of("*");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(5);
  private static final List<String> BLOCKED =
      List.of("fd00:ec2::254", "100.100.100.200", "168.63.129.16");
  private static final String PREFIX = "solr.index-url";

  @Test
  void acceptsTheDefaults() {
    assertDoesNotThrow(
        () ->
            new UrlIndexingProperties(
                ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT, 4, BLOCKED));
  }

  @Test
  void rejectsANonPositiveTotalTimeout() {
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UrlIndexingProperties(
                    ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, Duration.ZERO, 4, BLOCKED));
    assertEquals("solr.index-url.total-timeout must be positive", e.getMessage());
  }

  @Test
  void rejectsFewerThanOneConcurrentFetch() {
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UrlIndexingProperties(
                    ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT, 0, BLOCKED));
    assertEquals("solr.index-url.max-concurrent-fetches must be at least 1", e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"metadata.google.internal", "localhost", "169.254.169.254/32"})
  void rejectsABlockedEntryThatIsNotAnIpLiteral(String entry) {
    List<String> blocked = List.of("168.63.129.16", entry);
    var e = assertThrows(IllegalArgumentException.class, () -> properties(blocked));
    assertEquals(
        "solr.index-url.blocked-addresses must list IP address literals, not host names or ranges: "
            + entry,
        e.getMessage());
  }

  @Test
  void parsesTheBlockedLiteralsAndSkipsBlankEntries() throws Exception {
    var parsed = properties(List.of(" 168.63.129.16 ", "", "fd00:ec2::254")).blockedInetAddresses();
    assertEquals(
        Set.of(InetAddress.ofLiteral("168.63.129.16"), InetAddress.ofLiteral("fd00:ec2::254")),
        parsed);
  }

  @Test
  void bindsTheShippedDefaultsFromApplicationProperties() throws IOException {
    var shipped = new ResourcePropertySource("classpath:application.properties");
    var bound =
        new Binder(ConfigurationPropertySources.from(shipped))
            .bindOrCreate(PREFIX, UrlIndexingProperties.class);
    assertEquals(BLOCKED, bound.blockedAddresses());
    assertEquals(
        Set.of(
            InetAddress.ofLiteral("fd00:ec2::254"),
            InetAddress.ofLiteral("100.100.100.200"),
            InetAddress.ofLiteral("168.63.129.16")),
        bound.blockedInetAddresses());
  }

  @Test
  void bindsAnEmptyValueAsNothingBlocked() {
    var source = new MapConfigurationPropertySource(Map.of(PREFIX + ".blocked-addresses", ""));
    var bound = new Binder(source).bindOrCreate(PREFIX, UrlIndexingProperties.class);
    assertEquals(Set.of(), bound.blockedInetAddresses());
  }

  @Test
  void failsToBindWhenBlockedAddressesIsMissing() {
    var source = new MapConfigurationPropertySource(Map.of(PREFIX + ".allowed-hosts", "*"));
    var binder = new Binder(source);
    var e =
        assertThrows(
            RuntimeException.class, () -> binder.bindOrCreate(PREFIX, UrlIndexingProperties.class));
    assertEquals(
        UrlIndexingProperties.BLOCKED_ADDRESSES_MISSING,
        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
  }

  private static UrlIndexingProperties properties(List<String> blocked) {
    return new UrlIndexingProperties(
        ANY_HOST, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT, 4, blocked);
  }
}
