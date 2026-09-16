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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Drives the fetcher against a JDK HTTP server on an ephemeral loopback port:
 * no mocks, no Solr, runs natively. Read timeout 1 s and cap 1 KB keep the
 * failure cases fast.
 */
class UrlFetcherTest {

	private static final Duration CONNECT = Duration.ofSeconds(5);
	private static final Duration READ = Duration.ofSeconds(1);
	private static final DataSize CAP = DataSize.ofKilobytes(1);
	private static final String SIZE_MESSAGE = "The document is larger than this server's limit of 1024 bytes; "
			+ "nothing was indexed. Index datasets this large directly with Solr (bin/solr post or the /update "
			+ "handler); the index-data prompt shows the command.";

	private HttpServer server;
	private ExecutorService handlers;
	private String base;
	private UrlFetcher open;
	private UrlFetcher restricted;
	private final Map<String, List<String>> lastRequestHeaders = new ConcurrentHashMap<>();
	private final AtomicInteger requests = new AtomicInteger();

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		handlers = Executors.newCachedThreadPool();
		server.setExecutor(handlers);
		server.createContext("/shows.json",
				ex -> respond(ex, 200, "application/json; charset=utf-8", "[{\"id\":\"1\"}]"));
		server.createContext("/latin1.csv", ex -> respond(ex, 200, "text/csv; charset=iso-8859-1", "id\n1\n"));
		server.createContext("/bad-charset", ex -> respond(ex, 200, "text/csv; charset=no-such-charset", "id\n"));
		server.createContext("/untyped", ex -> respond(ex, 200, null, "id\n1\n"));
		server.createContext("/missing", ex -> respond(ex, 404, "text/plain", "404: Not Found"));
		server.createContext("/moved", ex -> redirect(ex, base + "/shows.json"));
		server.createContext("/relative", ex -> redirect(ex, "/shows.json"));
		server.createContext("/loop", ex -> redirect(ex, base + "/loop"));
		server.createContext("/to-metadata", ex -> redirect(ex, "http://169.254.169.254/latest/meta-data/"));
		server.createContext("/to-invalid", ex -> redirect(ex, "http://example.invalid/x.json"));
		server.createContext("/bad-location", ex -> redirect(ex, "http://[not-an-address/x.json"));
		for (int i = 1; i <= 6; i++) {
			int hop = i;
			server.createContext("/chain" + hop,
					ex -> redirect(ex, base + (hop == 1 ? "/shows.json" : "/chain" + (hop - 1))));
		}
		server.createContext("/declared-big", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 2048);
			try (OutputStream out = ex.getResponseBody()) {
				out.write(1); // the JDK server flushes the headers with the first body byte
				out.flush();
				sleep(3000); // a client that read on would hit the 1 s read timeout instead
				out.write(new byte[2047]);
			} catch (IOException ignored) {
				// the client went away, as expected
			}
		});
		server.createContext("/chunked-big", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				out.write(new byte[1025]);
			} catch (IOException ignored) {
				// the client may abort mid-body
			}
		});
		server.createContext("/stall", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				out.write("id,n\n1,1\n\n".getBytes(StandardCharsets.UTF_8));
				out.flush();
				sleep(3000);
			} catch (IOException ignored) {
				// the client aborted, as expected
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		open = new UrlFetcher(new UrlIndexingProperties(List.of("*"), CONNECT, READ, CAP));
		restricted = new UrlFetcher(new UrlIndexingProperties(List.of("127.0.0.1"), CONNECT, READ, CAP));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
		handlers.shutdownNow();
	}

	@Test
	void returnsBodyMediaTypeAndCharsetOfATwoHundredResponse() throws Exception {
		var fetched = open.fetch(URI.create(base + "/shows.json"));
		assertEquals("application/json", fetched.mediaType());
		assertEquals(StandardCharsets.UTF_8, fetched.charset());
		assertEquals("[{\"id\":\"1\"}]", new String(fetched.body(), StandardCharsets.UTF_8));
		assertEquals(URI.create(base + "/shows.json"), fetched.finalUri());
	}

	@Test
	void honoursADeclaredCharset() throws Exception {
		assertEquals(StandardCharsets.ISO_8859_1, open.fetch(URI.create(base + "/latin1.csv")).charset());
	}

	@Test
	void defaultsToUtf8AndEmptyMediaTypeWithoutContentType() throws Exception {
		var fetched = open.fetch(URI.create(base + "/untyped"));
		assertEquals("", fetched.mediaType());
		assertEquals(StandardCharsets.UTF_8, fetched.charset());
	}

	@Test
	void rejectsAnUnsupportedCharset() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/bad-charset")));
		assertEquals(UrlFetcher.UNSUPPORTED_CHARSET, e.getMessage());
	}

	@Test
	void nonTwoHundredIsAnErrorBeforeAnyBodyIsExposed() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/missing")));
		assertEquals("The URL returned HTTP 404; nothing was indexed. "
				+ "Check that it is public and points at a raw document, not a web page.", e.getMessage());
	}

	@Test
	void followsAbsoluteAndRelativeRedirectsToTheFinalUrl() throws Exception {
		assertEquals(URI.create(base + "/shows.json"), open.fetch(URI.create(base + "/moved")).finalUri());
		assertEquals(URI.create(base + "/shows.json"), open.fetch(URI.create(base + "/relative")).finalUri());
	}

	@Test
	void followsFiveRedirectsButNotSix() throws Exception {
		assertEquals(URI.create(base + "/shows.json"), open.fetch(URI.create(base + "/chain5")).finalUri());
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/chain6")));
		assertEquals(UrlFetcher.TOO_MANY_REDIRECTS, e.getMessage());
	}

	@Test
	void aRedirectLoopIsAnError() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/loop")));
		assertEquals(UrlFetcher.TOO_MANY_REDIRECTS, e.getMessage());
	}

	@Test
	void refusesAnHttpsToHttpDowngradeOnRedirect() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlFetcher.redirectTarget(URI.create("https://a.invalid/x"), "http://a.invalid/y"));
		assertEquals(UrlFetcher.DOWNGRADE, e.getMessage());
		assertEquals(URI.create("https://a.invalid/y"),
				UrlFetcher.redirectTarget(URI.create("http://a.invalid/x"), "https://a.invalid/y"));
	}

	@Test
	void aRedirectToAMetadataAddressIsRefused() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/to-metadata")));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
	}

	/**
	 * The allow-list message proves the ordering: had DNS been consulted first,
	 * {@code example.invalid} would have surfaced as an UnknownHostException.
	 */
	@Test
	void aRedirectToAHostOffTheAllowListIsRefusedBeforeDns() {
		var e = assertThrows(IllegalArgumentException.class, () -> restricted.fetch(URI.create(base + "/to-invalid")));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
	}

	@Test
	void aMalformedRedirectLocationIsAnError() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/bad-location")));
		assertEquals(UrlFetcher.INVALID_REDIRECT, e.getMessage());
	}

	@Test
	void aMetadataAddressIsRefusedWithoutSendingARequest() {
		int before = requests.get();
		var e = assertThrows(IllegalArgumentException.class,
				() -> open.fetch(URI.create("http://169.254.169.254/latest/meta-data/")));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
		assertEquals(before, requests.get());
	}

	@Test
	void anUnresolvableHostIsAnUnknownHostException() {
		assertThrows(UnknownHostException.class, () -> open.fetch(URI.create("http://nonexistent.invalid/x.json")));
	}

	@Test
	void aDeclaredContentLengthOverTheCapFailsBeforeTheBodyIsRead() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/declared-big")));
		assertEquals(SIZE_MESSAGE, e.getMessage());
	}

	@Test
	void aChunkedBodyOverTheCapFails() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/chunked-big")));
		assertEquals(SIZE_MESSAGE, e.getMessage());
	}

	@Test
	void aStalledBodyThrowsASocketTimeout() {
		long start = System.nanoTime();
		assertThrows(SocketTimeoutException.class, () -> open.fetch(URI.create(base + "/stall")));
		assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(), "read timeout did not fire");
	}

	@Test
	void sendsOnlyAcceptAndUserAgentAndNeverCredentials() throws Exception {
		open.fetch(URI.create(base + "/shows.json"));
		assertEquals(List.of(UrlFetcher.USER_AGENT), lastRequestHeaders.get("User-agent"));
		assertEquals(List.of(UrlFetcher.ACCEPT), lastRequestHeaders.get("Accept"));
		assertNull(lastRequestHeaders.get("Authorization"));
		assertNull(lastRequestHeaders.get("Cookie"));
	}

	private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		record(exchange);
		byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
		if (contentType != null) {
			exchange.getResponseHeaders().add("Content-Type", contentType);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private void redirect(HttpExchange exchange, String location) throws IOException {
		record(exchange);
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private void record(HttpExchange exchange) {
		requests.incrementAndGet();
		lastRequestHeaders.clear();
		exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k, List.copyOf(v)));
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
