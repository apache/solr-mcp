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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Pure checks that decide whether {@code index-url} may fetch a URL: absolute
 * http(s) with a host and no embedded credentials, a host on the operator's
 * allow-list, and no link-local or cloud-metadata address even when the
 * allow-list is {@code *}. The caller resolves the host, so this class never
 * performs I/O and the same checks run on every redirect hop.
 */
final class UrlTargetPolicy {

	static final String INVALID_URL = "Provide an absolute http or https URL.";
	static final String EMBEDDED_CREDENTIALS = "Remove the credentials from the URL; this server never sends credentials.";
	static final String HOST_NOT_ALLOWED = "The URL's host is not on this server's allow-list. Allowed by default: "
			+ "raw.githubusercontent.com, *.githubusercontent.com, github.com. The operator can change "
			+ "SOLR_INDEX_URL_ALLOWED_HOSTS (use * to allow any host). Nothing was indexed.";
	static final String REFUSED_ADDRESS = "This server does not fetch link-local or cloud-metadata addresses.";

	/**
	 * The EC2 IPv6 instance-metadata address; not link-local, so listed explicitly.
	 */
	private static final InetAddress EC2_IPV6_METADATA = literal("fd00:ec2::254");

	private UrlTargetPolicy() {
	}

	/**
	 * Rejects URLs this server will not fetch.
	 *
	 * @param uri
	 *            the URL as supplied, or the target of a redirect
	 * @param allowedHosts
	 *            exact hosts, {@code *.suffix} patterns, or {@code *}; an empty
	 *            list allows nothing
	 * @param resolved
	 *            every address the host resolves to; empty when only the syntactic
	 *            and allow-list checks are wanted
	 * @throws IllegalArgumentException
	 *             with the message the tool returns verbatim
	 */
	static void check(URI uri, List<String> allowedHosts, List<InetAddress> resolved) {
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null) {
			throw new IllegalArgumentException(INVALID_URL);
		}
		String lowered = scheme.toLowerCase(Locale.ROOT);
		if (!lowered.equals("http") && !lowered.equals("https")) {
			throw new IllegalArgumentException(INVALID_URL);
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException(EMBEDDED_CREDENTIALS);
		}
		if (!isAllowed(host, allowedHosts)) {
			throw new IllegalArgumentException(HOST_NOT_ALLOWED);
		}
		for (InetAddress address : resolved) {
			if (address.isLinkLocalAddress() || address.equals(EC2_IPV6_METADATA)) {
				throw new IllegalArgumentException(REFUSED_ADDRESS);
			}
		}
	}

	/**
	 * Allow-list matching: {@code *} matches everything; {@code *.suffix} matches
	 * any host that ends in {@code .suffix} and is longer than it; any other entry
	 * must equal the host. Comparison is case-insensitive and ignores a trailing
	 * dot on the host.
	 */
	static boolean isAllowed(String host, List<String> allowedHosts) {
		String normalized = normalizeHost(host);
		for (String entry : allowedHosts) {
			String pattern = entry.trim().toLowerCase(Locale.ROOT);
			if (pattern.equals("*")) {
				return true;
			}
			if (pattern.startsWith("*.")) {
				String suffix = pattern.substring(1); // ".example.com"
				if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
					return true;
				}
			} else if (pattern.equals(normalized)) {
				return true;
			}
		}
		return false;
	}

	private static String normalizeHost(String host) {
		String lowered = host.toLowerCase(Locale.ROOT);
		if (lowered.startsWith("[") && lowered.endsWith("]")) {
			lowered = lowered.substring(1, lowered.length() - 1); // IPv6 literal
		}
		return lowered.endsWith(".") ? lowered.substring(0, lowered.length() - 1) : lowered;
	}

	private static InetAddress literal(String address) {
		try {
			return InetAddress.getByName(address);
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Not a literal address: " + address, e);
		}
	}
}
