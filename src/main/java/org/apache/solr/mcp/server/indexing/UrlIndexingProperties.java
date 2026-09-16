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

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limits on a single {@code index-url} fetch. Hosts are allow-listed (GitHub
 * raw content by default; {@code *} allows any host); link-local addresses and
 * the known cloud-metadata addresses are refused regardless. The read timeout
 * applies to every socket read, so it bounds a body that stops arriving; the
 * size cap bounds memory, because the body is parsed in memory.
 *
 * @param allowedHosts
 *            exact hosts, {@code *.suffix} patterns, or {@code *}
 *            ({@code SOLR_INDEX_URL_ALLOWED_HOSTS}); an empty list allows
 *            nothing
 * @param connectTimeout
 *            TCP/TLS connect timeout ({@code SOLR_INDEX_URL_CONNECT_TIMEOUT})
 * @param readTimeout
 *            longest wait for any single read, headers or body
 *            ({@code SOLR_INDEX_URL_READ_TIMEOUT})
 * @param maxBytes
 *            body size cap, between 1 byte and 2 GB
 *            ({@code SOLR_INDEX_URL_MAX_BYTES})
 */
@ConfigurationProperties(prefix = "solr.index-url")
public record UrlIndexingProperties(@DefaultValue( {
		"raw.githubusercontent.com", "*.githubusercontent.com", "github.com"}) List<String> allowedHosts,
		@DefaultValue("10s") Duration connectTimeout, @DefaultValue("30s") Duration readTimeout,
		@DefaultValue("10MB") DataSize maxBytes){

	/** Fails at startup rather than on the first tool call. */
	public UrlIndexingProperties {
		if (maxBytes.toBytes() < 1 || maxBytes.toBytes() > Integer.MAX_VALUE - 1) {
			throw new IllegalArgumentException("solr.index-url.max-bytes must be between 1 byte and 2 GB");
		}
	}
}
