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
import java.util.List;
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
 * tests (allow-list {@code *}) rather than here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "solr.index-url.allowed-hosts=127.0.0.1")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class UrlIndexingIntegrationTest {

	private static final int BIG_ROWS = 250_000;
	private static final String PADDING = "x".repeat(20);

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
		server.createContext("/plain.xml", ex -> respond(ex, 200, "application/xml",
				"<shows><show><id>1</id></show></shows>".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/big.csv", ex -> {
			// ~11 MB, more than the old 10 MB cap, written as it goes rather than held
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				out.write("id,title\n".getBytes(StandardCharsets.UTF_8));
				for (int i = 0; i < BIG_ROWS; i++) {
					out.write(("big-" + i + ",Title " + i + " " + PADDING + "\n").getBytes(StandardCharsets.UTF_8));
				}
			}
		});
		server.createContext("/cut.csv", ex -> {
			// declares far more than it sends, then hangs up: a transfer that fails partway
			ex.getResponseHeaders().add("Content-Type", "text/csv");
			ex.sendResponseHeaders(200, 1_000_000);
			ex.getResponseBody().write(csv("cut", 20));
			ex.getResponseBody().flush();
			ex.close();
		});
		server.createContext("/nested.json",
				ex -> respond(ex, 200, "application/json",
						"[{\"id\":\"n-1\",\"title\":\"One\",\"studio\":{\"name\":\"Acme\",\"country\":\"US\"}}]"
								.getBytes(StandardCharsets.UTF_8)));
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
		assertTrue(summary.startsWith("Solr accepted the JSON document"), summary);
		assertFalse(summary.contains("Stranger Things"), "payload leaked into the summary");
		assertEquals(61, count(collection));
	}

	@Test
	void indexesCsvXmlAndMarkdown() throws Exception {
		String csv = newCollection("csv");
		// CSV and XML go to Solr's own update handler, as with the inline tools, so
		// the summary is Solr's acceptance rather than a count.
		String csvSummary = service.indexUrl(csv, base + "/shows.csv", null);
		assertTrue(csvSummary.contains("Solr accepted the CSV document"), csvSummary);
		assertEquals(50, count(csv));

		String xml = newCollection("xml");
		String xmlSummary = service.indexUrl(xml, base + "/shows.xml", null);
		assertTrue(xmlSummary.contains("Solr accepted the XML document"), xmlSummary);
		assertEquals(40, count(xml));

		String md = newCollection("md");
		String mdSummary = service.indexUrl(md, base + "/shows.md", null);
		assertTrue(mdSummary.contains("1 of 1"), mdSummary);
		assertEquals(1, count(md));
	}

	@Test
	void resolvesFormatFromExtensionBeforeQueryStringAndFromMediaTypeWithoutExtension() throws Exception {
		String byExtension = newCollection("query");
		assertTrue(service.indexUrl(byExtension, base + "/export.csv?token=abc", null).contains("Solr accepted"));
		assertEquals(30, count(byExtension));

		String byMediaType = newCollection("mediatype");
		assertTrue(service.indexUrl(byMediaType, base + "/data", null).contains("Solr accepted"));
		assertEquals(61, count(byMediaType));
	}

	@Test
	void followsARedirect() throws Exception {
		String collection = newCollection("redirect");
		assertTrue(service.indexUrl(collection, base + "/moved", null).contains("Solr accepted"));
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

		// Only a Solr <add> block is accepted, as for index-xml-documents.
		var plainXml = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, base + "/plain.xml", null));
		assertTrue(plainXml.getMessage().startsWith("Cannot parse the URL content as xml"), plainXml.getMessage());

		var offList = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl(collection, "http://example.invalid/x.json", null));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, offList.getMessage());

		solrClient.commit(collection);
		assertEquals(0, count(collection));
	}

	@Test
	void streamsADocumentLargerThanTheOldCap() throws Exception {
		String collection = newCollection("big");
		String summary = service.indexUrl(collection, base + "/big.csv", null);
		assertTrue(summary.startsWith("Solr accepted the CSV document"), summary);
		assertEquals(BIG_ROWS, count(collection));
	}

	/**
	 * The source hangs up after 20 rows. Solr may accept the rows it got, but the
	 * tool must report the failure and must not commit, so nothing is visible.
	 */
	@Test
	void aTransferThatFailsPartwayIsReportedAndNotCommitted() throws Exception {
		String collection = newCollection("cut");
		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl(collection, base + "/cut.csv", null));
		assertTrue(e.getMessage().startsWith("The URL stopped delivering the document after"), e.getMessage());
		assertEquals(0, count(collection), "a partial transfer must not be committed");
	}

	/**
	 * The server no longer flattens JSON for {@code index-url}; Solr's
	 * {@code /update/json/docs} does, joining the path with dots. Observed the same
	 * on Solr 8.11, 9.9 and 10.
	 */
	@Test
	void jsonWithANestedObjectIsIndexedAsOneDocument() throws Exception {
		String collection = newCollection("nested");
		assertTrue(service.indexUrl(collection, base + "/nested.json", null).startsWith("Solr accepted"));
		assertEquals(1, count(collection));
		var doc = solrClient.query(collection, new SolrQuery("id:n-1")).getResults().get(0);
		assertEquals(List.of("One"), doc.getFieldValues("title"));
		assertEquals(List.of("Acme"), doc.getFieldValues("studio.name"));
		assertEquals(List.of("US"), doc.getFieldValues("studio.country"));
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
			// the client may stop reading early
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
		var sb = new StringBuilder("<add>");
		for (int i = 0; i < rows; i++) {
			sb.append("<doc><field name=\"id\">xml-").append(i).append("</field><field name=\"title\">Title ").append(i)
					.append("</field></doc>");
		}
		return sb.append("</add>").toString().getBytes(StandardCharsets.UTF_8);
	}
}
