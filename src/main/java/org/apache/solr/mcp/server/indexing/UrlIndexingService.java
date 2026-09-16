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

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Locale;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * URL ingestion for both transports. The server fetches an http(s) URL whose
 * host is on the operator's allow-list (GitHub raw content by default), never
 * sends credentials or caller headers, caps the body, and hands the decoded
 * payload to the same parse-then-index path the inline tools use. Because
 * parsing completes before indexing starts, every fetch or parse failure leaves
 * the collection untouched.
 */
@Service
@Observed
public class UrlIndexingService {

	private static final Logger logger = LoggerFactory.getLogger(UrlIndexingService.class);

	static final String UNREACHABLE = "Cannot reach the URL from the MCP server. The URL is fetched from the "
			+ "server's network, not the client's, so localhost and private addresses refer to the server's side. "
			+ "Check the address and try again.";
	static final String READ_TIMEOUT = "The URL did not respond within the read timeout; nothing was indexed. "
			+ "Try again or ask the operator to raise SOLR_INDEX_URL_READ_TIMEOUT.";
	static final String FORMAT_UNRESOLVED = "Cannot determine the format from the URL path or Content-Type. "
			+ "Supply format=json, csv, xml or markdown.";
	static final String HTML_NOT_SUPPORTED = " HTML pages are not supported.";
	static final String SOLR_FAILED = "Solr could not complete URL indexing. Check collection availability and "
			+ "field types with get-schema, then verify the indexed count before retrying; some documents may "
			+ "already be indexed.";

	private final IndexingService indexingService;
	private final UrlFetcher fetcher;

	/**
	 * Creates the URL-ingestion tool with a fetcher built from the configured
	 * limits.
	 *
	 * @param indexingService
	 *            the indexing pipeline
	 * @param properties
	 *            allow-list, timeouts and size cap
	 */
	@Autowired
	public UrlIndexingService(IndexingService indexingService, UrlIndexingProperties properties) {
		this(indexingService, new UrlFetcher(properties));
	}

	UrlIndexingService(IndexingService indexingService, UrlFetcher fetcher) {
		this.indexingService = indexingService;
		this.fetcher = fetcher;
	}

	/**
	 * Indexes a document set from a URL without returning its contents.
	 *
	 * @param collection
	 *            target collection with a prepared schema
	 * @param url
	 *            absolute http(s) URL on the allow-list, reachable from the server
	 * @param format
	 *            optional format override; otherwise inferred from the URL path
	 *            extension, then the Content-Type
	 * @return indexed document counts and field names
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-url",
			annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = true),
			description = "Index a UTF-8 JSON, CSV, XML or Markdown document set from an http(s) URL without "
					+ "sending its contents through the model. Available in both STDIO and HTTP mode. The URL is "
					+ "fetched by the MCP server with no credentials or custom headers; its host must be on the "
					+ "server's allow-list (GitHub raw content by default) and the body must be within the configured "
					+ "size limit (default 10 MB). For larger datasets, index directly with Solr (bin/solr post or the "
					+ "/update handler) instead. Redirects are followed. Non-2xx responses and HTML pages are errors; "
					+ "nothing is indexed unless the whole document parses. Reuse the URL for another collection. "
					+ IndexingService.SCHEMA_FIRST_GUIDANCE)
	public String indexUrl(
			@McpToolParam(description = "Solr collection to index into", required = true) String collection,
			@McpToolParam(
					description = "Absolute http or https URL of a UTF-8 JSON, CSV, XML or Markdown document, at most "
							+ "the configured size limit (default 10 MB). The host must be on the server's allow-list "
							+ "(GitHub raw content by default). Fetched from the MCP server's network, not the "
							+ "client's. No credentials or custom headers are sent.",
					required = true) String url,
			@McpToolParam(
					description = "Optional format: json, csv, xml, markdown or md; defaults to the URL path "
							+ "extension, then the Content-Type",
					required = false) @Nullable String format) {
		if (collection.isBlank()) {
			throw new IllegalArgumentException("Provide a non-empty collection name.");
		}
		URI uri = parse(url);
		@Nullable String explicit = format == null || format.isBlank() ? null : IndexFormats.normalize(format);
		UrlFetcher.FetchedBody fetched;
		try {
			fetched = fetcher.fetch(uri);
		} catch (IOException e) {
			logger.debug("Could not fetch URL for indexing", e);
			throw new IllegalStateException(causedByTimeout(e) ? READ_TIMEOUT : UNREACHABLE);
		}
		String selected = explicit != null ? explicit : resolveFormat(uri, fetched.finalUri(), fetched.mediaType());
		String payload = new String(fetched.body(), fetched.charset());
		try {
			return indexingService.indexPayload(collection, payload, selected);
		} catch (DocumentProcessingException e) {
			logger.debug("Could not parse URL content for indexing", e);
			throw new IllegalArgumentException("Cannot parse the URL content as " + selected
					+ ". Check its syntax and format. Nothing was indexed.");
		} catch (SolrServerException | SolrException | IOException e) {
			logger.warn("URL indexing failed for collection {}", collection, e);
			throw new IllegalStateException(SOLR_FAILED);
		}
	}

	private static URI parse(String url) {
		if (url.isBlank()) {
			throw new IllegalArgumentException(UrlTargetPolicy.INVALID_URL);
		}
		try {
			return URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(UrlTargetPolicy.INVALID_URL);
		}
	}

	private static boolean causedByTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SocketTimeoutException) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Requested URL's path extension, then the final URL's after redirects, then
	 * the media type. {@code text/plain} carries no information and never resolves;
	 * {@code text/html} is refused explicitly.
	 */
	private static String resolveFormat(URI requested, URI finalUri, String mediaType) {
		for (URI candidate : new URI[]{requested, finalUri}) {
			@Nullable String extension = extensionOf(candidate);
			if (extension != null) {
				try {
					return IndexFormats.normalize(extension);
				} catch (IllegalArgumentException ignored) {
					// not a supported extension; fall through to the next source
				}
			}
		}
		return switch (mediaType) {
			case "application/json" -> "json";
			case "text/csv" -> "csv";
			case "application/xml", "text/xml" -> "xml";
			case "text/markdown" -> "markdown";
			case "text/html" -> throw new IllegalArgumentException(FORMAT_UNRESOLVED + HTML_NOT_SUPPORTED);
			default -> throw new IllegalArgumentException(FORMAT_UNRESOLVED);
		};
	}

	private static @Nullable String extensionOf(URI uri) {
		String path = uri.getPath();
		if (path == null) {
			return null;
		}
		String last = path.substring(path.lastIndexOf('/') + 1);
		int dot = last.lastIndexOf('.');
		return dot < 0 || dot == last.length() - 1 ? null : last.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
