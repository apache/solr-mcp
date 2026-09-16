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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Performs the {@code index-url} GET with Spring's {@link RestClient} over
 * {@code HttpURLConnection}: resolves and policy-checks the host on every hop,
 * follows up to {@value #MAX_REDIRECTS} redirects without downgrading to plain
 * http, sends only {@code Accept} and {@code User-Agent}, refuses bodies over
 * the configured cap, and returns the whole 2xx body with its media type and
 * charset. Caller-fixable problems surface as {@link IllegalArgumentException}
 * with the messages the tool returns verbatim; network failures surface as
 * {@link IOException}, which the read timeout turns into a
 * {@link java.net.SocketTimeoutException} when a body stops arriving.
 */
final class UrlFetcher {

	static final int MAX_REDIRECTS = 5;
	static final String ACCEPT = "application/json, text/csv, application/xml, text/xml, text/markdown, "
			+ "text/plain;q=0.5, */*;q=0.1";
	static final String USER_AGENT = "solr-mcp";
	static final String TOO_MANY_REDIRECTS = "The URL redirected more than " + MAX_REDIRECTS
			+ " times. Use the final URL directly.";
	static final String DOWNGRADE = "The URL redirects from https to http, which is refused. "
			+ "Use the final https URL directly.";
	static final String UNSUPPORTED_CHARSET = "The URL declares an unsupported charset. Supply a UTF-8 document.";

	private final RestClient restClient;
	private final List<String> allowedHosts;
	private final int maxBytes;
	private final String tooLarge;

	/**
	 * A 2xx response, read in full.
	 *
	 * @param finalUri
	 *            the URL that answered, after redirects
	 * @param mediaType
	 *            lower-cased media type without parameters, or empty if the
	 *            response carried no {@code Content-Type}
	 * @param charset
	 *            the declared charset, or UTF-8 when none was declared
	 * @param body
	 *            the raw body bytes, at most the configured cap
	 */
	record FetchedBody(URI finalUri, String mediaType, Charset charset, byte[] body) {
	}

	/** Outcome of one request; only a 2xx carries a body. */
	private sealed interface Exchange {
	}

	private record Redirect(String location) implements Exchange {
	}

	private record Status(int code) implements Exchange {
	}

	private record Body(String mediaType, Charset charset, byte[] bytes) implements Exchange {
	}

	UrlFetcher(UrlIndexingProperties properties) {
		var factory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false); // the policy must see every hop
			}
		};
		factory.setConnectTimeout(properties.connectTimeout());
		factory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder().requestFactory(factory).build();
		this.allowedHosts = properties.allowedHosts();
		this.maxBytes = (int) properties.maxBytes().toBytes(); // the record bounds it below Integer.MAX_VALUE
		long bytes = properties.maxBytes().toBytes();
		String limit = bytes % 1048576 == 0 ? properties.maxBytes().toMegabytes() + " MB" : bytes + " bytes";
		this.tooLarge = "The document is larger than this server's limit of " + limit + "; nothing was indexed. "
				+ "Index datasets this large directly with Solr (bin/solr post or the /update handler); "
				+ "the index-data prompt shows the command.";
	}

	/**
	 * Fetches a URL, following redirects.
	 *
	 * @param uri
	 *            the caller-supplied URL
	 * @return the 2xx response, read in full
	 * @throws IllegalArgumentException
	 *             for a refused URL or host, too many redirects, an https to http
	 *             downgrade, a non-2xx status, an unsupported charset, or a body
	 *             over the cap
	 * @throws IOException
	 *             if the host does not resolve, the connection fails, or a read
	 *             times out ({@link java.net.SocketTimeoutException})
	 */
	FetchedBody fetch(URI uri) throws IOException {
		URI current = uri;
		int redirects = 0;
		while (true) {
			UrlTargetPolicy.check(current, allowedHosts, List.of()); // syntax and allow-list before any DNS
			UrlTargetPolicy.check(current, allowedHosts, List.of(InetAddress.getAllByName(current.getHost())));
			switch (send(current)) {
				case Redirect redirect -> {
					if (++redirects > MAX_REDIRECTS) {
						throw new IllegalArgumentException(TOO_MANY_REDIRECTS);
					}
					current = redirectTarget(current, redirect.location());
				}
				case Status status -> throw new IllegalArgumentException("The URL returned HTTP " + status.code()
						+ "; nothing was indexed. Check that it is public and points at a raw document, not a web page.");
				case Body body -> {
					return new FetchedBody(current, body.mediaType(), body.charset(), body.bytes());
				}
			}
		}
	}

	/**
	 * Resolves a {@code Location} header against the current URL, refusing an https
	 * to http downgrade.
	 */
	static URI redirectTarget(URI current, String location) {
		URI target = current.resolve(location);
		if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(target.getScheme())) {
			throw new IllegalArgumentException(DOWNGRADE);
		}
		return target;
	}

	private Exchange send(URI uri) throws IOException {
		try {
			return restClient.get().uri(uri).header("Accept", ACCEPT).header("User-Agent", USER_AGENT)
					.exchange((request, response) -> {
						int status = response.getStatusCode().value();
						if (isRedirect(status)) {
							String location = response.getHeaders().getFirst("Location");
							if (location != null) {
								return new Redirect(location);
							}
						}
						if (status / 100 != 2) {
							return new Status(status);
						}
						String contentType = response.getHeaders().getFirst("Content-Type");
						if (contentType == null) {
							contentType = "";
						}
						Charset charset;
						try {
							charset = charsetOf(contentType);
						} catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
							throw new IllegalArgumentException(UNSUPPORTED_CHARSET);
						}
						if (response.getHeaders().getContentLength() > maxBytes) {
							throw new IllegalArgumentException(tooLarge); // before reading a byte
						}
						byte[] bytes = response.getBody().readNBytes(maxBytes + 1);
						if (bytes.length > maxBytes) {
							throw new IllegalArgumentException(tooLarge);
						}
						return new Body(mediaTypeOf(contentType), charset, bytes);
					});
		} catch (ResourceAccessException e) {
			if (e.getCause() instanceof IOException io) {
				throw io;
			}
			throw new IOException(e.getMessage(), e);
		}
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static String mediaTypeOf(String contentType) {
		int semicolon = contentType.indexOf(';');
		String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
		return type.trim().toLowerCase(Locale.ROOT);
	}

	private static Charset charsetOf(String contentType) {
		for (String parameter : contentType.split(";")) {
			String trimmed = parameter.trim();
			if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
				String name = trimmed.substring(8).trim();
				if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
					name = name.substring(1, name.length() - 1);
				}
				return Charset.forName(name);
			}
		}
		return StandardCharsets.UTF_8;
	}
}
