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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Error mapping, format resolution and the commit-only-when-complete rule of
 * the {@code index-url} tool, with the fetcher and the indexing path mocked;
 * the real fetch is covered by {@link UrlFetcherTest} and the integration test.
 * The URL constant ends in {@code .json}, so cases that rely on the media type
 * use their own URLs. A mocked {@code sendUncommitted} reads the body the way
 * SolrJ does: to its end, swallowing a read failure.
 */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class UrlIndexingServiceTest {

	private static final String URL = "https://raw.githubusercontent.com/apache/solr-mcp/main/shows.json";
	private static final String SUMMARY = "Solr accepted the document for collection 'shows' and committed it";

	@Mock
	IndexingService indexingService;

	@Mock
	UrlFetcher fetcher;

	private UrlIndexingService service;

	@BeforeEach
	void setUp() {
		service = new UrlIndexingService(indexingService, fetcher, 4);
	}

	@Test
	void refusesACallBeyondTheConfiguredConcurrentFetches() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		when(fetcher.fetch(any())).thenAnswer(invocation -> {
			entered.countDown();
			release.await(10, TimeUnit.SECONDS);
			return fetched(URL, "application/json", "[]");
		});
		streamsAndCommits("json");
		var single = new UrlIndexingService(indexingService, fetcher, 1);
		var executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> first = executor.submit(() -> single.indexUrl("shows", URL, null));
			assertTrue(entered.await(10, TimeUnit.SECONDS), "first fetch never started");

			var e = assertThrows(IllegalStateException.class, () -> single.indexUrl("shows", URL, null));
			assertEquals(UrlIndexingService.busyMessage(1), e.getMessage());

			release.countDown();
			assertEquals(SUMMARY, first.get(10, TimeUnit.SECONDS));
			// the permit is returned: a third call goes through
			assertEquals(SUMMARY, single.indexUrl("shows", URL, null));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void streamsTheBodyWithItsCharsetThenCommits() throws Exception {
		byte[] latin1 = "[{\"id\":\"café\"}]".getBytes(StandardCharsets.ISO_8859_1);
		when(fetcher.fetch(URI.create(URL)))
				.thenAnswer(i -> fetched(URL, "text/plain", StandardCharsets.ISO_8859_1, latin1));
		var sent = new AtomicReference<byte[]>();
		doAnswer(inv -> {
			sent.set(inv.getArgument(2, InputStream.class).readAllBytes());
			return null;
		}).when(indexingService).sendUncommitted(eq("shows"), eq("json"), any(), eq(StandardCharsets.ISO_8859_1));
		when(indexingService.commitStreamed("shows", "JSON document", latin1.length)).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", URL, null));
		assertArrayEquals(latin1, sent.get());
	}

	@Test
	void explicitFormatOverridesExtensionAndMediaType() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(URL, "application/json", "id\n1\n"));
		streamsAndCommits("csv");

		assertEquals(SUMMARY, service.indexUrl("shows", URL, " CSV "));
	}

	@Test
	void extensionOverridesMediaTypeAndIgnoresTheQueryString() throws Exception {
		String url = "https://example.invalid/export.csv?token=abc";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "text/plain", "id\n1\n"));
		streamsAndCommits("csv");

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
	}

	@Test
	void mediaTypeResolvesTheFormatWhenNeitherUrlHasAnExtension() throws Exception {
		String url = "https://example.invalid/data";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "text/xml", "<add/>"));
		streamsAndCommits("xml");

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
	}

	@Test
	void csvAndMarkdownMediaTypesResolveTheirFormats() throws Exception {
		when(fetcher.fetch(URI.create("https://example.invalid/a")))
				.thenAnswer(i -> fetched("https://example.invalid/a", "text/csv", "id\n1\n"));
		when(fetcher.fetch(URI.create("https://example.invalid/b")))
				.thenAnswer(i -> fetched("https://example.invalid/b", "text/markdown", "# T\n"));
		streamsAndCommits("csv");
		when(indexingService.indexMarkdown("shows", "# T\n")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", "https://example.invalid/a", null));
		assertEquals(SUMMARY, service.indexUrl("shows", "https://example.invalid/b", null));
	}

	@Test
	void markdownIsReadWholeAndDecodedWithItsCharset() throws Exception {
		String url = "https://example.invalid/notes.md";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "text/plain", StandardCharsets.ISO_8859_1,
				"# Café\n".getBytes(StandardCharsets.ISO_8859_1)));
		when(indexingService.indexMarkdown("shows", "# Café\n")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
		verify(indexingService, never()).sendUncommitted(any(), any(), any(), any());
	}

	@Test
	void aBlankExplicitFormatIsTreatedAsAbsent() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(URL, "text/plain", "[]"));
		streamsAndCommits("json");

		assertEquals(SUMMARY, service.indexUrl("shows", URL, "   "));
	}

	@Test
	void theFinalUrlsExtensionIsUsedWhenTheRequestedUrlHasNone() throws Exception {
		String requested = "https://example.invalid/latest";
		when(fetcher.fetch(URI.create(requested)))
				.thenAnswer(i -> fetched("https://example.invalid/releases/shows.json", "text/plain", "[]"));
		streamsAndCommits("json");

		assertEquals(SUMMARY, service.indexUrl("shows", requested, null));
	}

	@Test
	void textPlainWithoutAKnownExtensionIsAFormatErrorAndIndexesNothing() throws Exception {
		String url = "https://example.invalid/data.txt";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "text/plain", "id\n1\n"));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals(UrlIndexingService.FORMAT_UNRESOLVED, e.getMessage());
		verifyNoInteractions(indexingService);
	}

	@Test
	void htmlIsAFormatErrorThatSaysSo() throws Exception {
		String url = "https://example.invalid/page";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "text/html", "<html/>"));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals(UrlIndexingService.FORMAT_UNRESOLVED + UrlIndexingService.HTML_NOT_SUPPORTED, e.getMessage());
		verifyNoInteractions(indexingService);
	}

	@Test
	void anUnknownExplicitFormatIsRejectedBeforeFetching() {
		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", URL, "yaml"));
		assertEquals(IndexFormats.UNKNOWN_FORMAT, e.getMessage());
		verifyNoInteractions(fetcher, indexingService);
	}

	@Test
	void blankCollectionAndInvalidUrlsAreRejectedBeforeFetching() {
		var c = assertThrows(IllegalArgumentException.class, () -> service.indexUrl(" ", URL, null));
		assertEquals("Provide a non-empty collection name.", c.getMessage());
		var u = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", " ", null));
		assertEquals(UrlTargetPolicy.INVALID_URL, u.getMessage());
		var p = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", "http://[bad", null));
		assertEquals(UrlTargetPolicy.INVALID_URL, p.getMessage());
		verifyNoInteractions(fetcher, indexingService);
	}

	@Test
	void fetcherArgumentErrorsPassThroughUnchanged() throws Exception {
		when(fetcher.fetch(any())).thenThrow(new IllegalArgumentException(UrlTargetPolicy.HOST_NOT_ALLOWED));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlTargetPolicy.HOST_NOT_ALLOWED, e.getMessage());
		verifyNoInteractions(indexingService);
	}

	@Test
	void aReadTimeoutIsReportedAsSuchEvenWhenWrapped() throws Exception {
		when(fetcher.fetch(any())).thenThrow(new SocketTimeoutException("Read timed out"))
				.thenThrow(new IOException("wrapped", new SocketTimeoutException("Read timed out")));

		for (int i = 0; i < 2; i++) {
			var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
			assertEquals(UrlIndexingService.READ_TIMEOUT, e.getMessage());
		}
	}

	@Test
	void otherNetworkFailuresAreReportedFromTheServersPointOfView() throws Exception {
		when(fetcher.fetch(any())).thenThrow(new UnknownHostException("example.invalid"));

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlIndexingService.UNREACHABLE, e.getMessage());
	}

	/**
	 * SolrJ swallows a failed read and ends the upload early; Solr may accept what
	 * it got. The tool must still see the failure, and must not commit.
	 */
	@Test
	void aBodyThatFailsPartwayIsNotCommittedAndSaysSomeDocumentsMayAppear() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> failing(URL, "id\n1\n2\n", new IOException("connection reset")));
		streamsSwallowingFailure("json");

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlIndexingService.PARTIAL_TRANSFER.formatted(UrlIndexingService.STOPPED, 7), e.getMessage());
		verify(indexingService, never()).commitStreamed(any(), any(), anyLong());
	}

	@Test
	void aBodyThatTimesOutPartwaySaysSo() throws Exception {
		when(fetcher.fetch(any()))
				.thenAnswer(i -> failing(URL, "[{\"id\":1}", new SocketTimeoutException("total timeout")));
		streamsSwallowingFailure("json");

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlIndexingService.PARTIAL_TRANSFER.formatted(UrlIndexingService.TIMED_OUT, 9), e.getMessage());
		verify(indexingService, never()).commitStreamed(any(), any(), anyLong());
	}

	@Test
	void aBodyThatFailsBeforeItsFirstByteIsUnreachableNotPartial() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> failing(URL, "", new IOException("connection reset")));
		streamsSwallowingFailure("json");

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlIndexingService.UNREACHABLE, e.getMessage());
	}

	/** Solr's 400 for a cut-off JSON body says nothing about why it was cut off. */
	@Test
	void aFailedTransferWinsOverSolrsVerdictOnWhatItReceived() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> failing(URL, "[{\"id\":1}", new IOException("connection reset")));
		doAnswer(inv -> {
			try {
				inv.getArgument(2, InputStream.class).transferTo(OutputStream.nullOutputStream());
			} catch (IOException swallowed) {
				// as SolrJ does
			}
			throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unexpected EOF in JSON");
		}).when(indexingService).sendUncommitted(any(), any(), any(), any());

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlIndexingService.PARTIAL_TRANSFER.formatted(UrlIndexingService.STOPPED, 9), e.getMessage());
	}

	@Test
	void anXmlBodyThatIsNotAnAddBlockNamesTheFormatAndSaysNothingWasIndexed() throws Exception {
		String url = "https://example.invalid/shows.xml";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "application/xml", "<shows/>"));
		doThrow(new DocumentProcessingException("XML input must be a Solr <add> block")).when(indexingService)
				.sendUncommitted(any(), eq("xml"), any(), any());

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals("Cannot parse the URL content as xml. XML input must be a Solr <add> block. Nothing was indexed.",
				e.getMessage());
	}

	@Test
	void markdownParseFailuresSayNothingWasIndexed() throws Exception {
		String url = "https://example.invalid/notes.md";
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(url, "text/markdown", "---\n: bad\n"));
		when(indexingService.indexMarkdown(any(), any())).thenThrow(new DocumentProcessingException("bad"));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals("Cannot parse the URL content as markdown. Check its syntax and format. Nothing was indexed.",
				e.getMessage());
	}

	@Test
	void solrFailuresAreStateErrors() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(URL, "application/json", "[]"));
		doThrow(new SolrServerException("down")).doThrow(new IOException("connection reset"))
				.doThrow(new SolrException(SolrException.ErrorCode.SERVER_ERROR, "update failed")).when(indexingService)
				.sendUncommitted(any(), any(), any(), any());

		for (int i = 0; i < 3; i++) {
			var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
			assertEquals(UrlIndexingService.SOLR_FAILED, e.getMessage());
		}
	}

	@Test
	void aFailedCommitIsAStateError() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> fetched(URL, "application/json", "[]"));
		streamsSwallowingFailure("json");
		when(indexingService.commitStreamed(any(), any(), anyLong()))
				.thenThrow(new SolrException(SolrException.ErrorCode.SERVER_ERROR, "commit failed"));

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlIndexingService.SOLR_FAILED, e.getMessage());
	}

	@Test
	void aSolrBadRequestIsReportedAsRejectedContentWithSolrsReason() throws Exception {
		when(fetcher.fetch(any())).thenAnswer(i -> fetched("https://example.invalid/x.csv", "text/csv", "id\n1\n"));
		doThrow(new SolrException(SolrException.ErrorCode.BAD_REQUEST, "CSV parse error")).when(indexingService)
				.sendUncommitted(any(), eq("csv"), any(), any());

		var e = assertThrows(IllegalArgumentException.class,
				() -> service.indexUrl("shows", "https://example.invalid/x.csv", null));
		assertEquals(UrlIndexingService.SOLR_REJECTED.formatted("csv", "CSV parse error"), e.getMessage());
	}

	/**
	 * A mocked Solr that reads the whole body, and a commit that returns SUMMARY.
	 */
	private void streamsAndCommits(String format) throws Exception {
		streamsSwallowingFailure(format);
		when(indexingService.commitStreamed(eq("shows"), eq(format.toUpperCase(Locale.ROOT) + " document"), anyLong()))
				.thenReturn(SUMMARY);
	}

	/**
	 * Reads the body as SolrJ's content writer does: to its end, logging a failure.
	 */
	private void streamsSwallowingFailure(String format) throws Exception {
		doAnswer(inv -> {
			try {
				inv.getArgument(2, InputStream.class).transferTo(OutputStream.nullOutputStream());
			} catch (IOException swallowed) {
				// SolrJ logs "Cannot write Content Stream" and ends the upload
			}
			return null;
		}).when(indexingService).sendUncommitted(eq("shows"), eq(format), any(), any());
	}

	private static UrlFetcher.FetchedBody fetched(String url, String mediaType, String body) {
		return fetched(url, mediaType, StandardCharsets.UTF_8, body.getBytes(StandardCharsets.UTF_8));
	}

	private static UrlFetcher.FetchedBody fetched(String url, String mediaType, Charset charset, byte[] body) {
		return new UrlFetcher.FetchedBody(URI.create(url), mediaType, charset,
				stream(new ByteArrayInputStream(body), body.length));
	}

	/** A body that delivers {@code prefix}, then fails with {@code failure}. */
	private static UrlFetcher.FetchedBody failing(String url, String prefix, IOException failure) {
		byte[] bytes = prefix.getBytes(StandardCharsets.UTF_8);
		InputStream in = new InputStream() {
			private int next;

			@Override
			public int read() throws IOException {
				if (next < bytes.length) {
					return bytes[next++] & 0xff;
				}
				throw failure;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (next < bytes.length) {
					int n = Math.min(len, bytes.length - next);
					System.arraycopy(bytes, next, b, off, n);
					next += n;
					return n;
				}
				throw failure;
			}
		};
		return new UrlFetcher.FetchedBody(URI.create(url), "application/json", StandardCharsets.UTF_8, stream(in, -1));
	}

	private static TransferStream stream(InputStream in, long length) {
		return new TransferStream(in, length, System.nanoTime() + TimeUnit.HOURS.toNanos(1), () -> {
		});
	}
}
