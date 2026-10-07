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
import java.io.EOFException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

/**
 * Drives the fetcher against a JDK HTTP server on an ephemeral loopback port:
 * no mocks, no Solr, runs natively. A 1 s read timeout keeps the failure cases
 * fast.
 */
class UrlFetcherTest {

	private static final Duration CONNECT = Duration.ofSeconds(5);
	private static final Duration READ = Duration.ofSeconds(1);
	private static final Duration TOTAL = Duration.ofMinutes(5);

	private HttpServer server;
	private ExecutorService handlers;
	private String base;
	private UrlFetcher open;
	private UrlFetcher restricted;
	private UrlFetcher dripping;
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
		server.createContext("/to-mailto", ex -> redirect(ex, "mailto:someone@example.invalid"));
		server.createContext("/to-userinfo", ex -> redirect(ex, "http://u:p@" + base.substring(7) + "/shows.json"));
		server.createContext("/no-location", ex -> {
			record(ex);
			ex.sendResponseHeaders(302, -1);
			ex.close();
		});
		server.createContext("/quoted-charset", ex -> respond(ex, 200, "text/csv; charset=\"iso-8859-1\"", "id\n1\n"));
		for (int status : new int[]{301, 303, 307, 308}) {
			server.createContext("/r" + status, ex -> redirect(ex, status, base + "/shows.json"));
		}
		server.createContext("/drip", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				for (int i = 0; i < 100; i++) {
					out.write('x'); // one byte every 200 ms: never idle, never finishing soon
					out.flush();
					sleep(200);
				}
			} catch (IOException ignored) {
				// the client gave up, as expected
			}
		});
		server.createContext("/slow-big", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 2 * 1024 * 1024);
			try (OutputStream out = ex.getResponseBody()) {
				for (int i = 0; i < 2048; i++) {
					out.write(new byte[1024]); // steady 10 KB/s: never idle, so a drain would run for minutes
					out.flush();
					sleep(100);
				}
			} catch (IOException ignored) {
				// the client dropped the connection, as expected
			}
		});
		for (int i = 1; i <= 6; i++) {
			int hop = i;
			server.createContext("/chain" + hop,
					ex -> redirect(ex, base + (hop == 1 ? "/shows.json" : "/chain" + (hop - 1))));
		}
		server.createContext("/short", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 2048); // declares 2048 bytes, then hangs up after 10
			ex.getResponseBody().write(new byte[10]);
			ex.getResponseBody().flush();
			ex.close();
		});
		server.createContext("/chunked-big", ex -> {
			record(ex);
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				for (int i = 0; i < 16; i++) {
					out.write(new byte[1024 * 1024]); // 16 MB, larger than the old 10 MB cap
				}
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
		open = new UrlFetcher(new UrlIndexingProperties(List.of("*"), CONNECT, READ, TOTAL, 4));
		restricted = new UrlFetcher(new UrlIndexingProperties(List.of("127.0.0.1"), CONNECT, READ, TOTAL, 4));
		// a read timeout longer than the total deadline, so only the deadline can fire
		dripping = new UrlFetcher(
				new UrlIndexingProperties(List.of("*"), CONNECT, Duration.ofSeconds(5), Duration.ofSeconds(1), 4));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
		handlers.shutdownNow();
	}

	@Test
	void returnsBodyMediaTypeAndCharsetOfATwoHundredResponse() throws Exception {
		try (var fetched = open.fetch(URI.create(base + "/shows.json"))) {
			assertEquals("application/json", fetched.mediaType());
			assertEquals(StandardCharsets.UTF_8, fetched.charset());
			assertEquals("[{\"id\":\"1\"}]", new String(fetched.body().readAllBytes(), StandardCharsets.UTF_8));
			assertEquals(URI.create(base + "/shows.json"), fetched.finalUri());
			assertDoesNotThrow(fetched.body()::requireComplete);
			assertEquals(12, fetched.body().bytesRead());
		}
	}

	@Test
	void aBodyNotReadToItsEndIsNotComplete() throws Exception {
		try (var fetched = open.fetch(URI.create(base + "/shows.json"))) {
			assertEquals('[', fetched.body().read());
			assertThrows(EOFException.class, fetched.body()::requireComplete);
		}
	}

	@Test
	void honoursAQuotedCharset() throws Exception {
		assertEquals(StandardCharsets.ISO_8859_1, fetched(open, base + "/quoted-charset").charset());
	}

	@ParameterizedTest
	@ValueSource(ints = {301, 303, 307, 308})
	void followsEveryRedirectStatus(int status) throws Exception {
		assertEquals(URI.create(base + "/shows.json"), fetched(open, base + "/r" + status).finalUri());
	}

	@Test
	void aRedirectStatusWithoutLocationIsAStatusError() {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + "/no-location")));
		assertTrue(e.getMessage().startsWith("The URL returned HTTP 302;"), e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/to-mailto", "/to-userinfo"})
	void aRedirectToANonHttpOrCredentialedLocationIsARedirectError(String path) {
		var e = assertThrows(IllegalArgumentException.class, () -> open.fetch(URI.create(base + path)));
		assertEquals(UrlFetcher.INVALID_REDIRECT, e.getMessage());
	}

	/**
	 * Spring's response close() drains the body to keep the connection alive; the
	 * fetcher must drop the connection instead, or an abandoned 2 MB body streamed
	 * at 10 KB/s would hold the call open for minutes.
	 */
	@Test
	void closingAnUnreadBodyDoesNotDrainIt() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			var fetched = open.fetch(URI.create(base + "/slow-big"));
			fetched.close();
		});
	}

	/**
	 * The read timeout is per read, so a host that drips one byte at a time never
	 * trips it; the total deadline must.
	 */
	@Test
	void aDrippingBodyFailsAtTheTotalDeadline() {
		assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
			try (var fetched = dripping.fetch(URI.create(base + "/drip"))) {
				assertThrows(SocketTimeoutException.class, fetched.body()::readAllBytes);
				assertInstanceOf(SocketTimeoutException.class, fetched.body().failure());
			}
		});
	}

	@Test
	void aNonNumericContentLengthCountsAsUnknown() {
		var headers = new HttpHeaders();
		headers.add("Content-Length", "banana");
		assertEquals(-1L, UrlFetcher.contentLengthOf(headers));
		headers.set("Content-Length", "42");
		assertEquals(42L, UrlFetcher.contentLengthOf(headers));
		assertEquals(-1L, UrlFetcher.contentLengthOf(new HttpHeaders()));
	}

	@Test
	void honoursADeclaredCharset() throws Exception {
		assertEquals(StandardCharsets.ISO_8859_1, fetched(open, base + "/latin1.csv").charset());
	}

	@Test
	void defaultsToUtf8AndEmptyMediaTypeWithoutContentType() throws Exception {
		var fetched = fetched(open, base + "/untyped");
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
		assertEquals(URI.create(base + "/shows.json"), fetched(open, base + "/moved").finalUri());
		assertEquals(URI.create(base + "/shows.json"), fetched(open, base + "/relative").finalUri());
	}

	@Test
	void followsFiveRedirectsButNotSix() throws Exception {
		assertEquals(URI.create(base + "/shows.json"), fetched(open, base + "/chain5").finalUri());
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
	void aBodyCutShortOfItsContentLengthIsRecordedAsAFailure() throws Exception {
		try (var fetched = open.fetch(URI.create(base + "/short"))) {
			assertThrows(IOException.class, fetched.body()::readAllBytes);
			assertNotNull(fetched.body().failure());
			assertThrows(IOException.class, fetched.body()::requireComplete);
		}
	}

	@Test
	void aBodyOfAnySizeStreamsThrough() throws Exception {
		try (var fetched = open.fetch(URI.create(base + "/chunked-big"))) {
			assertEquals(16L * 1024 * 1024, fetched.body().transferTo(OutputStream.nullOutputStream()));
			assertDoesNotThrow(fetched.body()::requireComplete);
		}
	}

	@Test
	void aStalledBodyThrowsASocketTimeout() throws Exception {
		long start = System.nanoTime();
		try (var fetched = open.fetch(URI.create(base + "/stall"))) {
			assertThrows(SocketTimeoutException.class, fetched.body()::readAllBytes);
		}
		assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(), "read timeout did not fire");
	}

	@Test
	void sendsOnlyAcceptAndUserAgentAndNeverCredentials() throws Exception {
		open.fetch(URI.create(base + "/shows.json")).close();
		assertEquals(List.of(UrlFetcher.USER_AGENT), lastRequestHeaders.get("User-agent"));
		assertEquals(List.of(UrlFetcher.ACCEPT), lastRequestHeaders.get("Accept"));
		assertNull(lastRequestHeaders.get("Authorization"));
		assertNull(lastRequestHeaders.get("Cookie"));
	}

	/** Fetches, reads the body to its end, and releases the connection. */
	private static UrlFetcher.FetchedBody fetched(UrlFetcher fetcher, String url) throws IOException {
		try (var fetched = fetcher.fetch(URI.create(url))) {
			fetched.body().transferTo(OutputStream.nullOutputStream());
			return fetched;
		}
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
		redirect(exchange, 302, location);
	}

	private void redirect(HttpExchange exchange, int status, String location) throws IOException {
		record(exchange);
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(status, -1);
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
