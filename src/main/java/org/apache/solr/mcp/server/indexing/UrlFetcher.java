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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Performs the {@code index-url} GET with Spring's {@link RestClient} over
 * {@code HttpURLConnection}: resolves and policy-checks the host on every hop,
 * follows up to {@value #MAX_REDIRECTS} redirects without downgrading to plain
 * http, sends only {@code Accept} and {@code User-Agent}, and returns the 2xx
 * response still open, as a {@link TransferStream} with its media type and
 * charset, for the caller to stream and close. Nothing is read into memory
 * here. Caller-fixable problems surface as {@link IllegalArgumentException}
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
	static final String INVALID_REDIRECT = "The URL redirected to an invalid location. Use the final URL directly.";
	static final String UNSUPPORTED_CHARSET = "The URL declares an unsupported charset. Supply a UTF-8 document.";

	private final RestClient restClient;
	private final List<String> allowedHosts;
	private final long totalTimeoutNanos;

	/**
	 * A 2xx response whose body has not been read yet. Closing it releases the
	 * connection.
	 *
	 * @param finalUri
	 *            the URL that answered, after redirects
	 * @param mediaType
	 *            lower-cased media type without parameters, or empty if the
	 *            response carried no {@code Content-Type}
	 * @param charset
	 *            the declared charset, or UTF-8 when none was declared
	 * @param body
	 *            the body, to be read once; it enforces the whole-fetch deadline
	 *            and records whether it was read to its end
	 */
	record FetchedBody(URI finalUri, String mediaType, Charset charset, TransferStream body) implements AutoCloseable {

		@Override
		public void close() {
			body.close();
		}
	}

	/** Outcome of one request; only a 2xx carries a body. */
	private sealed interface Exchange {
	}

	private record Redirect(String location) implements Exchange {
	}

	private record Status(int code) implements Exchange {
	}

	private record Body(String mediaType, Charset charset, TransferStream stream) implements Exchange {
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
		this.totalTimeoutNanos = properties.totalTimeout().toNanos();
	}

	/**
	 * Fetches a URL, following redirects.
	 *
	 * @param uri
	 *            the caller-supplied URL
	 * @return the 2xx response, open; the caller must close it
	 * @throws IllegalArgumentException
	 *             for a refused URL or host, too many redirects, an https to http
	 *             downgrade, a non-2xx status, or an unsupported charset
	 * @throws IOException
	 *             if the host does not resolve, the connection fails, or a read
	 *             times out ({@link java.net.SocketTimeoutException})
	 */
	FetchedBody fetch(URI uri) throws IOException {
		URI current = uri;
		int redirects = 0;
		long deadline = System.nanoTime() + totalTimeoutNanos;
		while (true) {
			if (System.nanoTime() - deadline > 0) {
				throw new SocketTimeoutException("total timeout exceeded before hop " + redirects);
			}
			try {
				UrlTargetPolicy.check(current, allowedHosts, List.of()); // syntax and allow-list before any DNS
			} catch (IllegalArgumentException e) {
				boolean syntactic = UrlTargetPolicy.INVALID_URL.equals(e.getMessage())
						|| UrlTargetPolicy.EMBEDDED_CREDENTIALS.equals(e.getMessage());
				if (redirects > 0 && syntactic) {
					throw new IllegalArgumentException(INVALID_REDIRECT); // the caller never supplied this URL
				}
				throw e;
			}
			UrlTargetPolicy.check(current, allowedHosts, List.of(InetAddress.getAllByName(current.getHost())));
			switch (send(current, deadline)) {
				case Redirect redirect -> {
					if (++redirects > MAX_REDIRECTS) {
						throw new IllegalArgumentException(TOO_MANY_REDIRECTS);
					}
					current = redirectTarget(current, redirect.location());
				}
				case Status status -> throw new IllegalArgumentException("The URL returned HTTP " + status.code()
						+ "; nothing was indexed. Check that it is public and points at a raw document, not a web page.");
				case Body body -> {
					return new FetchedBody(current, body.mediaType(), body.charset(), body.stream());
				}
			}
		}
	}

	/**
	 * Resolves a {@code Location} header against the current URL, refusing an https
	 * to http downgrade.
	 */
	static URI redirectTarget(URI current, String location) {
		URI target;
		try {
			target = current.resolve(location);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(INVALID_REDIRECT); // never the JDK's parse message
		}
		if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(target.getScheme())) {
			throw new IllegalArgumentException(DOWNGRADE);
		}
		return target;
	}

	private Exchange send(URI uri, long deadline) throws IOException {
		try {
			// close=false: a 2xx response stays open for the caller to stream; every
			// other outcome is released here before returning or throwing.
			return restClient.get().uri(uri).header("Accept", ACCEPT).header("User-Agent", USER_AGENT)
					.exchange((request, response) -> {
						try {
							int status = response.getStatusCode().value();
							if (isRedirect(status)) {
								String location = response.getHeaders().getFirst("Location");
								if (location != null) {
									release(response);
									return new Redirect(location);
								}
							}
							if (status / 100 != 2) {
								release(response);
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
							var body = new TransferStream(response.getBody(), contentLengthOf(response.getHeaders()),
									deadline, () -> release(response));
							return new Body(mediaTypeOf(contentType), charset, body);
						} catch (IOException | RuntimeException e) {
							release(response);
							throw e;
						}
					}, false);
		} catch (ResourceAccessException e) {
			if (e.getCause() instanceof IOException io) {
				throw io;
			}
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Closes the body stream, then the response. Spring's {@code close()} drains an
	 * unread body to keep the connection reusable, which would download a refused
	 * or abandoned response in full; closing the stream first makes the JDK drop
	 * the connection (or hand at most a small remainder to its keep-alive cleaner)
	 * and turns Spring's drain into a no-op on a closed stream. Safe to call twice.
	 */
	private static void release(org.springframework.http.client.ClientHttpResponse response) {
		try {
			response.getBody().close();
		} catch (IOException ignored) {
			// nothing to abandon
		}
		response.close();
	}

	/** {@code Content-Length} as a long, or -1 when absent or not a number. */
	static long contentLengthOf(HttpHeaders headers) {
		String value = headers.getFirst(HttpHeaders.CONTENT_LENGTH);
		if (value == null) {
			return -1;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return -1;
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
