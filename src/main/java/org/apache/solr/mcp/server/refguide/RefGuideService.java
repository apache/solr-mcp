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
package org.apache.solr.mcp.server.refguide;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Service for searching the Solr Reference Guide.
 */
@Service
public class RefGuideService {

	private static final String SITEMAP_URL = "https://solr.apache.org/guide/sitemap.xml";
	private static final Pattern LOC_PATTERN = Pattern.compile("<loc>(.*?)</loc>");

	private final RestClient restClient;

	public RefGuideService(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * Searches the Solr Reference Guide for the specified query and version.
	 *
	 * @param query
	 *            The search query
	 * @param version
	 *            The Solr version (e.g., "9.10", "8.11", or "latest"). Defaults to
	 *            "latest" if not specified.
	 * @return A list of relevant URLs from the Solr Reference Guide
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "searchRefGuide", description = "Search the Solr Reference Guide for information about a specific version or the latest version.")
	public List<String> searchRefGuide(
			@McpToolParam(description = "The search query (e.g., 'circuit breakers', 'indexing')") String query,
			@McpToolParam(description = "The Solr version (e.g., '9.10', '8.11', 'latest'). Defaults to 'latest'", required = false) String version) {

		if (isVersionLessThan9(version)) {
			return List.of(
					"https://archive.apache.org/dist/lucene/solr/ref-guide/apache-solr-ref-guide-" + version + ".pdf");
		}

		final String targetVersion = (version == null || version.isBlank()) ? "latest" : version.replace('.', '_');
		final String sitemapContent = restClient.get().uri(SITEMAP_URL).retrieve().body(String.class);

		if (sitemapContent == null) {
			return List.of();
		}

		final List<String> urls = new ArrayList<>();
		final Matcher matcher = LOC_PATTERN.matcher(sitemapContent);
		final String lowerQuery = query.toLowerCase();

		while (matcher.find()) {
			String url = matcher.group(1);
			// Filter by version and query
			if (url.contains("/" + targetVersion + "/")
					|| (targetVersion.equals("latest") && url.contains("/latest/"))) {
				if (url.toLowerCase().contains(lowerQuery.replace(' ', '-'))
						|| url.toLowerCase().contains(lowerQuery.replace(' ', '_'))) {
					urls.add(url);
				}
			}
		}

		// Fallback: if no direct match, try matching individual words
		if (urls.isEmpty()) {
			String[] words = lowerQuery.split("\\s+");
			matcher.reset();
			while (matcher.find()) {
				String url = matcher.group(1);
				if (url.contains("/" + targetVersion + "/") || (targetVersion.equals("latest") && url.contains("/latest/"))) {
					boolean allMatch = true;
					for (String word : words) {
						if (!url.toLowerCase().contains(word)) {
							allMatch = false;
							break;
						}
					}
					if (allMatch) {
						urls.add(url);
					}
				}
			}
		}

		return urls.stream().limit(10).toList();

	}

	private boolean isVersionLessThan9(final String version) {

		if (version == null || version.isBlank() || "latest".equalsIgnoreCase(version)) {
			return false;
		}

		try {
			String[] parts = version.split("\\.");
			if (parts.length > 0) {
				int major = Integer.parseInt(parts[0]);
				return major < 9;
			}
		} catch (NumberFormatException e) {
			// ignore and say not less than 9
		}

		return false;

	}

}
