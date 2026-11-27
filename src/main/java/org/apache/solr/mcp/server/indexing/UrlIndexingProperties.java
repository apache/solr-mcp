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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on {@code index-url} fetches. Hosts are allow-listed (GitHub raw content by default;
 * {@code *} allows any host); link-local addresses and the blocked addresses (the known
 * cloud-metadata addresses, as set in {@code application.properties}) are refused regardless. The
 * read timeout applies to every socket read, so it bounds a body that stops arriving; the total
 * timeout bounds a whole fetch including redirects, so a host that drips bytes cannot hold a call
 * open indefinitely; and the concurrency limit bounds how many such calls run at once. There is no
 * size cap: JSON, CSV and XML bodies stream into Solr, and Markdown is read in full.
 *
 * @param allowedHosts exact hosts, {@code *.suffix} patterns, or {@code *} ({@code
 *     SOLR_INDEX_URL_ALLOWED_HOSTS}); an empty list allows nothing
 * @param connectTimeout TCP/TLS connect timeout ({@code SOLR_INDEX_URL_CONNECT_TIMEOUT})
 * @param readTimeout longest wait for any single read, headers or body ({@code
 *     SOLR_INDEX_URL_READ_TIMEOUT})
 * @param totalTimeout deadline for one whole fetch, redirects included ({@code
 *     SOLR_INDEX_URL_TOTAL_TIMEOUT})
 * @param maxConcurrentFetches how many {@code index-url} calls may run at once; further calls fail
 *     immediately ({@code SOLR_INDEX_URL_MAX_CONCURRENT_FETCHES})
 * @param blockedAddresses IP address literals refused on every hop, in addition to every link-local
 *     address ({@code SOLR_INDEX_URL_BLOCKED_ADDRESSES}). It has no default here: {@code
 *     application.properties} lists the AWS IPv6, Alibaba Cloud and Azure metadata addresses, and
 *     startup fails if the property is missing, so a configuration that skips that file cannot
 *     silently drop them. An empty value blocks only link-local addresses.
 */
@ConfigurationProperties(prefix = "solr.index-url")
public record UrlIndexingProperties(
    @DefaultValue({"raw.githubusercontent.com", "*.githubusercontent.com", "github.com"})
        List<String> allowedHosts,
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("30s") Duration readTimeout,
    @DefaultValue("5m") Duration totalTimeout,
    @DefaultValue("4") int maxConcurrentFetches,
    List<String> blockedAddresses) {

  static final String BLOCKED_ADDRESSES_MISSING =
      "solr.index-url.blocked-addresses must be set; application.properties sets the "
          + "cloud-metadata addresses, and an empty value blocks only link-local addresses";

  /** Fails at startup rather than on the first tool call. */
  public UrlIndexingProperties {
    if (totalTimeout.isZero() || totalTimeout.isNegative()) {
      throw new IllegalArgumentException("solr.index-url.total-timeout must be positive");
    }
    if (maxConcurrentFetches < 1) {
      throw new IllegalArgumentException(
          "solr.index-url.max-concurrent-fetches must be at least 1");
    }
    if (blockedAddresses == null) { // Boot binds a missing property without a default as null
      throw new IllegalArgumentException(BLOCKED_ADDRESSES_MISSING);
    }
    blockedAddresses = List.copyOf(blockedAddresses);
    parseLiterals(blockedAddresses); // a host name fails here, at startup
  }

  /** The blocked addresses, parsed from their literals without any DNS lookup. */
  Set<InetAddress> blockedInetAddresses() {
    return parseLiterals(blockedAddresses);
  }

  private static Set<InetAddress> parseLiterals(List<String> literals) {
    Set<InetAddress> parsed = new LinkedHashSet<>();
    for (String literal : literals) {
      String trimmed = literal.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        parsed.add(InetAddress.ofLiteral(trimmed)); // never resolves a name
      } catch (IllegalArgumentException _) {
        throw new IllegalArgumentException(
            "solr.index-url.blocked-addresses must list IP address literals, not host names or ranges: "
                + literal);
      }
    }
    return Set.copyOf(parsed);
  }
}
