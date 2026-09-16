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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure checks on the URL, the allow-list and the resolved addresses. Every
 * address is built from a literal, so no DNS is involved.
 */
class UrlTargetPolicyTest {

	private static final List<String> ANY = List.of("*");
	private static final List<String> GITHUB = List.of("raw.githubusercontent.com", "*.githubusercontent.com",
			"github.com");
	private static final List<InetAddress> PUBLIC = addresses("93.184.216.34");

	@Test
	void exactHostMatchesCaseInsensitivelyAndIgnoresATrailingDot() {
		assertDoesNotThrow(
				() -> UrlTargetPolicy.check(uri("http://RAW.githubusercontent.com./x.json"), GITHUB, PUBLIC));
	}

	@Test
	void wildcardMatchesSubdomainsButNotTheBareSuffix() {
		assertDoesNotThrow(
				() -> UrlTargetPolicy.check(uri("https://gist.githubusercontent.com/x.csv"), GITHUB, PUBLIC));
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(uri("https://githubusercontent.com/x.csv"), GITHUB, PUBLIC));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
	}

	@Test
	void starAllowsAnyHostIncludingLoopbackAndPrivateAddresses() {
		assertDoesNotThrow(() -> UrlTargetPolicy.check(uri("http://10.0.0.1/x.json"), ANY, addresses("10.0.0.1")));
		assertDoesNotThrow(() -> UrlTargetPolicy.check(uri("http://localhost/x.json"), ANY, addresses("127.0.0.1")));
		assertDoesNotThrow(() -> UrlTargetPolicy.check(uri("http://[::1]/x.json"), ANY, addresses("::1")));
	}

	@Test
	void anEmptyAllowListRejectsEveryHost() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(uri("https://raw.githubusercontent.com/x.json"), List.of(), PUBLIC));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
	}

	@Test
	void aHostNotOnTheListIsRejectedBeforeAddressesAreConsidered() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(uri("https://example.invalid/x.json"), GITHUB, List.of()));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = {"169.254.169.254", "fe80::1", "fd00:ec2::254", "::ffff:169.254.169.254"})
	void refusesLinkLocalAndCloudMetadataAddressesEvenWithStar(String literal) {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(uri("http://example.invalid/x.json"), ANY, addresses(literal)));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
	}

	@Test
	void refusesWhenAnyOfSeveralAddressesIsLinkLocal() {
		var e = assertThrows(IllegalArgumentException.class, () -> UrlTargetPolicy
				.check(uri("http://example.invalid/x.json"), ANY, addresses("93.184.216.34", "169.254.169.254")));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(
			strings = {"ftp://example.invalid/data.json", "file:///etc/passwd", "data.json", "/data.json",
					"http:///data.json", "http://user@/data.json"})
	void rejectsNonHttpSchemesRelativeUrlsAndMissingHosts(String url) {
		var e = assertThrows(IllegalArgumentException.class, () -> UrlTargetPolicy.check(uri(url), ANY, PUBLIC));
		assertEquals(UrlTargetPolicy.INVALID_URL, e.getMessage());
	}

	@Test
	void rejectsEmbeddedCredentialsBeforeTheAllowList() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(uri("http://user:pw@raw.githubusercontent.com/x.json"), GITHUB, PUBLIC));
		assertEquals(UrlTargetPolicy.EMBEDDED_CREDENTIALS, e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.1", "93.184.216.34"})
	void allowsOrdinaryAddressesWithStar(String literal) {
		assertDoesNotThrow(() -> UrlTargetPolicy.check(uri("http://example.invalid/x.json"), ANY, addresses(literal)));
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
