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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure checks on the URL, the allow-list and the resolved addresses. Every address is built from a
 * literal, so no DNS is involved.
 */
class UrlTargetPolicyTest {

  private static final List<String> ANY = List.of("*");
  private static final List<String> GITHUB =
      List.of("raw.githubusercontent.com", "*.githubusercontent.com", "github.com");

  /** The {@code solr.index-url.blocked-addresses} default from application.properties. */
  private static final Set<InetAddress> BLOCKED =
      Set.copyOf(addresses("fd00:ec2::254", "100.100.100.200", "168.63.129.16"));

  private static final List<InetAddress> PUBLIC = addresses("93.184.216.34");
  private static final URI EXAMPLE = uri("http://example.invalid/x.json");

  @Test
  void exactHostMatchesCaseInsensitivelyAndIgnoresATrailingDot() {
    assertDoesNotThrow(
        () ->
            UrlTargetPolicy.check(
                uri("http://RAW.githubusercontent.com./x.json"), GITHUB, BLOCKED, PUBLIC));
  }

  @Test
  void wildcardMatchesSubdomainsButNotTheBareSuffix() {
    assertDoesNotThrow(
        () ->
            UrlTargetPolicy.check(
                uri("https://gist.githubusercontent.com/x.csv"), GITHUB, BLOCKED, PUBLIC));
    URI bareSuffix = uri("https://githubusercontent.com/x.csv");
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(bareSuffix, GITHUB, BLOCKED, PUBLIC));
    assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
  }

  @Test
  void starAllowsAnyHostIncludingLoopbackAndPrivateAddresses() {
    assertDoesNotThrow(
        () ->
            UrlTargetPolicy.check(
                uri("http://10.0.0.1/x.json"), ANY, BLOCKED, addresses("10.0.0.1")));
    assertDoesNotThrow(
        () ->
            UrlTargetPolicy.check(
                uri("http://localhost/x.json"), ANY, BLOCKED, addresses("127.0.0.1")));
    assertDoesNotThrow(
        () -> UrlTargetPolicy.check(uri("http://[::1]/x.json"), ANY, BLOCKED, addresses("::1")));
  }

  @Test
  void anEmptyAllowListRejectsEveryHost() {
    URI github = uri("https://raw.githubusercontent.com/x.json");
    List<String> noHosts = List.of();
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(github, noHosts, BLOCKED, PUBLIC));
    assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
  }

  @Test
  void aHostNotOnTheListIsRejectedBeforeAddressesAreConsidered() {
    URI offList = uri("https://example.invalid/x.json");
    List<InetAddress> unresolved = List.of();
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(offList, GITHUB, BLOCKED, unresolved));
    assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "169.254.169.254",
        "fe80::1",
        "fd00:ec2::254",
        "::ffff:169.254.169.254",
        "100.100.100.200",
        "168.63.129.16"
      })
  void refusesLinkLocalAndCloudMetadataAddressesEvenWithStar(String literal) {
    List<InetAddress> refused = addresses(literal);
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(EXAMPLE, ANY, BLOCKED, refused));
    assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"169.254.169.254", "fe80::1", "::ffff:169.254.169.254"})
  void refusesLinkLocalAddressesWithNothingBlocked(String literal) {
    List<InetAddress> linkLocal = addresses(literal);
    Set<InetAddress> nothingBlocked = Set.of();
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(EXAMPLE, ANY, nothingBlocked, linkLocal));
    assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
  }

  @Test
  void aMetadataAddressIsRefusedOnlyWhileItIsBlocked() {
    List<InetAddress> azure = addresses("168.63.129.16");
    Set<InetAddress> nothingBlocked = Set.of();
    assertDoesNotThrow(() -> UrlTargetPolicy.check(EXAMPLE, ANY, nothingBlocked, azure));
  }

  @Test
  void refusesWhenAnyOfSeveralAddressesIsLinkLocal() {
    List<InetAddress> oneLinkLocal = addresses("93.184.216.34", "169.254.169.254");
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(EXAMPLE, ANY, BLOCKED, oneLinkLocal));
    assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.invalid/data.json",
        "file:///etc/passwd",
        "data.json",
        "/data.json",
        "http:///data.json",
        "http://user@/data.json"
      })
  void rejectsNonHttpSchemesRelativeUrlsAndMissingHosts(String url) {
    URI rejected = uri(url);
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(rejected, ANY, BLOCKED, PUBLIC));
    assertEquals(UrlTargetPolicy.INVALID_URL, e.getMessage());
  }

  @Test
  void rejectsAPortOutOfRangeBeforeConnecting() {
    URI portOutOfRange = uri("https://raw.githubusercontent.com:99999/x.json");
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(portOutOfRange, GITHUB, BLOCKED, PUBLIC));
    assertEquals(UrlTargetPolicy.INVALID_URL, e.getMessage());
  }

  @Test
  void anExactIpv6EntryMatchesTheBracketedHost() {
    assertDoesNotThrow(
        () ->
            UrlTargetPolicy.check(
                uri("http://[::1]:8000/x.json"), List.of("::1"), BLOCKED, addresses("::1")));
  }

  @Test
  void rejectsEmbeddedCredentialsBeforeTheAllowList() {
    URI credentialed = uri("http://user:pw@raw.githubusercontent.com/x.json");
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> UrlTargetPolicy.check(credentialed, GITHUB, BLOCKED, PUBLIC));
    assertEquals(UrlTargetPolicy.EMBEDDED_CREDENTIALS, e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.1", "93.184.216.34"})
  void allowsOrdinaryAddressesWithStar(String literal) {
    assertDoesNotThrow(
        () ->
            UrlTargetPolicy.check(
                uri("http://example.invalid/x.json"), ANY, BLOCKED, addresses(literal)));
  }

  private static URI uri(String url) {
    return URI.create(url);
  }

  private static List<InetAddress> addresses(String... literals) {
    try {
      var list = new java.util.ArrayList<InetAddress>();
      for (String literal : literals) {
        list.add(InetAddress.getByName(literal)); // no DNS for a literal
      }
      return List.copyOf(list);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }
}
