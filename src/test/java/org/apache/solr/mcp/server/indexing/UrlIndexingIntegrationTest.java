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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives {@code index-url} end to end: a JDK HTTP server on an ephemeral
 * loopback port serves the documents, the real service indexes them into a real
 * Solr. The allow-list is bound from configuration to {@code 127.0.0.1} only,
 * which is why the metadata-address refusal is exercised by the MCP client
 * tests (allow-list {@code *}) rather than here. The cap is lowered to 64 KB so
 * the over-cap case is small.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"solr.index-url.allowed-hosts=127.0.0.1", "solr.index-url.max-bytes=64KB"})
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class UrlIndexingIntegrationTest {

	private static HttpServer server;
	private static String base;

	@Autowired
	private UrlIndexingService service;

	@Autowired
	private SolrClient solrClient;

	@BeforeAll
	static void startServer() throws IOException {
		byte[] showsJson = Files.readAllBytes(Path.of("src/test/resources/shows.json"));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/shows.json", ex -> respond(ex, 200, "text/plain; charset=utf-8", showsJson));
		server.createContext("/shows.csv", ex -> respond(ex, 200, "text/plain", csv("csv", 50)));
		server.createContext("/shows.xml", ex -> respond(ex, 200, "text/plain", xml(40)));
		server.createContext("/shows.md",
				ex -> respond(ex, 200, "text/plain", "# One show\n\nA body.\n".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/export.csv", ex -> respond(ex, 200, "text/plain", csv("export", 30)));
		server.createContext("/data", ex -> respond(ex, 200, "application/json", showsJson));
		server.createContext("/data.txt", ex -> respond(ex, 200, "text/plain", csv("txt", 5)));
		server.createContext("/page",
				ex -> respond(ex, 200, "text/html", "<html><body>id,x</body></html>".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/missing",
				ex -> respond(ex, 404, "text/plain", "404: Not Found".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/moved", ex -> {
			ex.getResponseHeaders().add("Location", base + "/shows.json");
			ex.sendResponseHeaders(302, -1);
			ex.close();
		});
		server.createContext("/big.csv", ex -> respond(ex, 200, "text/csv", csv("big", 6_000))); // ~100 KB
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@Test
	void indexesJsonServedAsTextPlainByExtension() throws Exception {
		String collection = newCollection("json");
		String summary = service.indexUrl(collection, base + "/shows.json", null);
		assertTrue(summary.contains("61 of 61"), summary);
		assertTrue(summary.contains("Indexed field names"), "structured formats list their fields: " + summary);
		assertFalse(summary.contains("Stranger Things"), "payload leaked into the summary");
		assertEquals(61, count(collection));
	}

	@Test
	void indexesCsvXmlAndMarkdown() throws Exception {
		String csv = newCollection("csv");
		String csvSummary = service.indexUrl(csv, base + "/shows.csv", null);
		assertTrue(csvSummary.contains("50 of 50"), csvSummary);
		assertTrue(csvSummary.contains("Indexed field names"), csvSummary);
		assertEquals(50, count(csv));

		String xml = newCollection("xml");
		String xmlSummary = service.indexUrl(xml, base + "/shows.xml", null);
		assertTrue(xmlSummary.contains("40 of 40"), xmlSummary);
		assertTrue(xmlSummary.contains("Indexed field names"), xmlSummary);
		assertEquals(40, count(xml));

		String md = newCollection("md");
		String mdSummary = service.indexUrl(md, base + "/shows.md", null);
		assertTrue(mdSummary.contains("1 of 1"), mdSummary);
		assertFalse(mdSummary.contains("Indexed field names"), "Markdown is one document, no field list: " + mdSummary);
		assertEquals(1, count(md));
	}

	@Test
	void resolvesFormatFromExtensionBeforeQueryStringAndFromMediaTypeWithoutExtension() throws Exception {
		String byExtension = newCollection("query");
		assertTrue(service.indexUrl(byExtension, base + "/export.csv?token=abc", null).contains("30 of 30"));
		assertEquals(30, count(byExtension));

		String byMediaType = newCollection("mediatype");
		assertTrue(service.indexUrl(byMediaType, base + "/data", null).contains("61 of 61"));
		assertEquals(61, count(byMediaType));
	}

	@Test
	void followsARedirect() throws Exception {
		String collection = newCollection("redirect");
		assertTrue(service.indexUrl(collection, base + "/moved", null).contains("61 of 61"));
		assertEquals(61, count(collection));
	}

	@Test
	void refusedOrUnparsableSourcesIndexNothing() throws Exception {
		String collection = newCollection("errors");

		var plain = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, base + "/data.txt", null));
		assertEquals(UrlIndexingService.FORMAT_UNRESOLVED, plain.getMessage());

		var html = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, base + "/page", null));
		assertEquals(UrlIndexingService.FORMAT_UNRESOLVED + UrlIndexingService.HTML_NOT_SUPPORTED, html.getMessage());

		var missing = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, base + "/missing", null));
		assertEquals(
				"The URL returned HTTP 404; nothing was indexed. "
						+ "Check that it is public and points at a raw document, not a web page.",
				missing.getMessage());

		var big = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, base + "/big.csv", null));
		// 64 KB is not a whole number of megabytes, so the limit renders in bytes
		assertEquals("The document is larger than this server's limit of 65536 bytes; nothing was indexed. "
				+ "Index datasets this large directly with Solr (bin/solr post or the /update handler); "
				+ "the index-data prompt shows the command.", big.getMessage());

		var offList = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, "http://example.invalid/x.json", null));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, offList.getMessage());

		solrClient.commit(collection);
		assertEquals(0, count(collection));
	}

	private String newCollection(String suffix) throws Exception {
		String name = "url_" + suffix + "_" + System.nanoTime();
		CollectionAdminRequest.createCollection(name, "_default", 1, 1).process(solrClient);
		return name;
	}

	private long count(String collection) throws Exception {
		return solrClient.query(collection, new SolrQuery("*:*").setRows(0)).getResults().getNumFound();
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		} catch (IOException ignored) {
			// the client stops reading an over-cap body early
		}
	}

	private static byte[] csv(String prefix, int rows) {
		var sb = new StringBuilder("id,title\n");
		for (int i = 0; i < rows; i++) {
			sb.append(prefix).append('-').append(i).append(",Title ").append(i).append('\n');
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] xml(int rows) {
		var sb = new StringBuilder("<shows>");
		for (int i = 0; i < rows; i++) {
			sb.append("<show><id>xml-").append(i).append("</id><title>Title ").append(i).append("</title></show>");
		}
		return sb.append("</shows>").toString().getBytes(StandardCharsets.UTF_8);
	}
}
