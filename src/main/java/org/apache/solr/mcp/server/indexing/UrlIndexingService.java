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
import java.util.Optional;
import java.util.concurrent.Semaphore;
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
 * host is on the operator's allow-list (GitHub raw content by default) and
 * never sends credentials or caller headers. JSON, CSV and XML stream straight
 * into Solr's update handlers, so a document of any size passes through a small
 * buffer; the commit follows only once the whole body has arrived. Markdown,
 * which Solr cannot parse, is read in full and parsed by the server.
 */
@Service
@Observed
public class UrlIndexingService {

	private static final Logger logger = LoggerFactory.getLogger(UrlIndexingService.class);

	static final String UNREACHABLE = "Cannot reach the URL from the MCP server. The URL is fetched from the "
			+ "server's network, not the client's, so localhost and private addresses refer to the server's side. "
			+ "Check the address and try again.";
	static final String READ_TIMEOUT = "The URL did not deliver the document within the read or total timeout; "
			+ "nothing was indexed. Try again or ask the operator to raise SOLR_INDEX_URL_READ_TIMEOUT or "
			+ "SOLR_INDEX_URL_TOTAL_TIMEOUT.";
	static final String FORMAT_UNRESOLVED = "Cannot determine the format from the URL path or Content-Type. "
			+ "Supply format=json, csv, xml or markdown.";
	static final String HTML_NOT_SUPPORTED = " HTML pages are not supported.";
	static final String SOLR_REJECTED = "Solr rejected the URL content as %s: %s";
	static final String PARTIAL_TRANSFER = "The URL %s after %d bytes, so what Solr received was not committed. "
			+ "Documents Solr had already read may still appear in the collection. Re-run index-url to index the "
			+ "whole document (documents with the same id are replaced), and check the count before relying on it.";
	static final String STOPPED = "stopped delivering the document";
	static final String TIMED_OUT = "did not deliver the whole document within the read or total timeout";
	static final String SOLR_FAILED = "Solr could not complete URL indexing. Check collection availability and "
			+ "field types with get-schema, then verify the indexed count before retrying; some documents may "
			+ "already be indexed.";

	private final IndexingService indexingService;
	private final UrlFetcher fetcher;
	private final Semaphore permits;
	private final String busy;

	/**
	 * Creates the URL-ingestion tool with a fetcher built from the configured
	 * limits.
	 *
	 * @param indexingService
	 *            the indexing pipeline
	 * @param properties
	 *            allow-list, timeouts and concurrency limit
	 */
	@Autowired
	public UrlIndexingService(IndexingService indexingService, UrlIndexingProperties properties) {
		this(indexingService, new UrlFetcher(properties), properties.maxConcurrentFetches());
	}

	UrlIndexingService(IndexingService indexingService, UrlFetcher fetcher, int maxConcurrentFetches) {
		this.indexingService = indexingService;
		this.fetcher = fetcher;
		this.permits = new Semaphore(maxConcurrentFetches);
		this.busy = busyMessage(maxConcurrentFetches);
	}

	/** The error returned when {@code max} fetches are already running. */
	static String busyMessage(int max) {
		return "The server is already running " + max + " index-url call" + (max == 1 ? "" : "s")
				+ ", the configured maximum; try again in a moment. Nothing was indexed.";
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
			description = "Index a UTF-8 JSON, CSV, Solr update XML or Markdown document set from an http(s) URL without "
					+ "sending its contents through the model. Available in both STDIO and HTTP mode. The URL is "
					+ "fetched by the MCP server with no credentials or custom headers; its host must be on the "
					+ "server's allow-list (GitHub raw content by default). There is no size limit: JSON, CSV and XML "
					+ "stream straight into Solr, and XML must be a Solr <add> block as for index-xml-documents; "
					+ "Markdown is parsed by the server. Redirects are followed. Non-2xx responses and HTML pages are "
					+ "errors. " + "Reuse the URL for another collection. " + IndexingService.SCHEMA_FIRST_GUIDANCE)
	public String indexUrl(
			@McpToolParam(description = "Solr collection to index into", required = true) String collection,
			@McpToolParam(
					description = "Absolute http or https URL of a UTF-8 JSON, CSV, Solr update XML or Markdown document, "
							+ "of any size. The host must be on the server's allow-list "
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
		if (!permits.tryAcquire()) {
			throw new IllegalStateException(busy);
		}
		try {
			return fetchAndIndex(collection, uri, explicit);
		} finally {
			permits.release();
		}
	}

	private String fetchAndIndex(String collection, URI uri, @Nullable String explicit) {
		UrlFetcher.FetchedBody fetched;
		try {
			fetched = fetcher.fetch(uri);
		} catch (IOException e) {
			logger.debug("Could not fetch URL for indexing", e);
			throw new IllegalStateException(causedByTimeout(e) ? READ_TIMEOUT : UNREACHABLE);
		}
		try (fetched) {
			String selected = explicit != null ? explicit : resolveFormat(uri, fetched.finalUri(), fetched.mediaType());
			return "markdown".equals(selected)
					? indexMarkdown(collection, fetched)
					: stream(collection, selected, fetched);
		}
	}

	/** Reads the whole Markdown document, which the server parses itself. */
	private String indexMarkdown(String collection, UrlFetcher.FetchedBody fetched) {
		String markdown;
		try {
			markdown = new String(fetched.body().readAllBytes(), fetched.charset());
		} catch (IOException e) {
			logger.debug("Could not read URL content for indexing", e);
			throw new IllegalStateException(causedByTimeout(e) ? READ_TIMEOUT : UNREACHABLE);
		}
		try {
			return indexingService.indexMarkdown(collection, markdown);
		} catch (DocumentProcessingException e) {
			logger.debug("Could not parse URL content for indexing", e);
			throw new IllegalArgumentException(
					"Cannot parse the URL content as markdown. Check its syntax and format. Nothing was indexed.");
		} catch (SolrServerException | SolrException | IOException e) {
			logger.warn("URL indexing failed for collection {}", collection, e);
			throw new IllegalStateException(SOLR_FAILED);
		}
	}

	/**
	 * Streams JSON, CSV or XML into Solr, then commits only if the whole body
	 * arrived. A failed transfer is checked first on every path, because Solr's
	 * answer to a cut-off upload (success for a shorter CSV, a parse error for
	 * truncated JSON or XML) says nothing about why it was cut off.
	 */
	private String stream(String collection, String format, UrlFetcher.FetchedBody fetched) {
		TransferStream body = fetched.body();
		try {
			indexingService.sendUncommitted(collection, format, body, fetched.charset());
		} catch (DocumentProcessingException e) {
			throw failedTransfer(body).orElseGet(() -> {
				logger.debug("Could not parse URL content for indexing", e);
				return new IllegalArgumentException(
						"Cannot parse the URL content as " + format + ". " + e.getMessage() + ". Nothing was indexed.");
			});
		} catch (SolrException e) {
			throw failedTransfer(body).orElseGet(() -> {
				if (e.code() == SolrException.ErrorCode.BAD_REQUEST.code) {
					// Solr parses JSON, CSV and XML, so their syntax errors arrive as a 400.
					return new IllegalArgumentException(SOLR_REJECTED.formatted(format, e.getMessage()));
				}
				logger.warn("URL indexing failed for collection {}", collection, e);
				return new IllegalStateException(SOLR_FAILED);
			});
		} catch (SolrServerException | IOException e) {
			throw failedTransfer(body).orElseGet(() -> {
				logger.warn("URL indexing failed for collection {}", collection, e);
				return new IllegalStateException(SOLR_FAILED);
			});
		}
		try {
			body.requireComplete();
		} catch (IOException e) {
			throw failedTransfer(body, e).orElseThrow();
		}
		try {
			return indexingService.commitStreamed(collection, format.toUpperCase(Locale.ROOT) + " document",
					body.bytesRead());
		} catch (SolrServerException | SolrException | IOException e) {
			logger.warn("URL indexing commit failed for collection {}", collection, e);
			throw new IllegalStateException(SOLR_FAILED);
		}
	}

	private static Optional<RuntimeException> failedTransfer(TransferStream body) {
		return failedTransfer(body, body.failure());
	}

	/**
	 * The error for a body that did not arrive whole: nothing reached Solr if no
	 * byte was read, otherwise a partial transfer that was not committed. Empty if
	 * there is no failure.
	 */
	private static Optional<RuntimeException> failedTransfer(TransferStream body, @Nullable IOException failure) {
		if (failure == null) {
			return Optional.empty();
		}
		logger.debug("URL body did not arrive whole", failure);
		boolean timeout = causedByTimeout(failure);
		if (body.bytesRead() == 0) {
			return Optional.of(new IllegalStateException(timeout ? READ_TIMEOUT : UNREACHABLE));
		}
		return Optional.of(
				new IllegalStateException(PARTIAL_TRANSFER.formatted(timeout ? TIMED_OUT : STOPPED, body.bytesRead())));
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
