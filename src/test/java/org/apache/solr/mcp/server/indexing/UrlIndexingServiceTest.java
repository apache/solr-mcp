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

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Error mapping and format resolution of the {@code index-url} tool with the
 * fetcher and the indexing path mocked; the real fetch is covered by
 * {@link UrlFetcherTest} and the integration test. The URL constant ends in
 * {@code .json}, so cases that rely on the media type use their own URLs.
 */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class UrlIndexingServiceTest {

	private static final String URL = "https://raw.githubusercontent.com/apache/solr-mcp/main/shows.json";
	private static final String SUMMARY = "Successfully indexed 2 of 2 documents into collection 'shows'";

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
		when(indexingService.indexPayload("shows", "[]", "json")).thenReturn(SUMMARY);
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
	void decodesTheBodyWithItsCharsetAndReturnsTheIndexingSummary() throws Exception {
		when(fetcher.fetch(URI.create(URL))).thenReturn(fetched(URL, "text/plain", StandardCharsets.ISO_8859_1,
				"[{\"id\":\"café\"}]".getBytes(StandardCharsets.ISO_8859_1)));
		when(indexingService.indexPayload(eq("shows"), anyString(), eq("json"))).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", URL, null));
		var payload = ArgumentCaptor.forClass(String.class);
		verify(indexingService).indexPayload(eq("shows"), payload.capture(), eq("json"));
		assertEquals("[{\"id\":\"café\"}]", payload.getValue());
	}

	@Test
	void explicitFormatOverridesExtensionAndMediaType() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", "id\n1\n"));
		when(indexingService.indexPayload("shows", "id\n1\n", "csv")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", URL, " CSV "));
	}

	@Test
	void extensionOverridesMediaTypeAndIgnoresTheQueryString() throws Exception {
		String url = "https://example.invalid/export.csv?token=abc";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/plain", "id\n1\n"));
		when(indexingService.indexPayload("shows", "id\n1\n", "csv")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
	}

	@Test
	void mediaTypeResolvesTheFormatWhenNeitherUrlHasAnExtension() throws Exception {
		String url = "https://example.invalid/data";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/xml", "<add/>"));
		when(indexingService.indexPayload("shows", "<add/>", "xml")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
	}

	@Test
	void csvAndMarkdownMediaTypesResolveTheirFormats() throws Exception {
		when(fetcher.fetch(URI.create("https://example.invalid/a")))
				.thenReturn(fetched("https://example.invalid/a", "text/csv", "id\n1\n"));
		when(fetcher.fetch(URI.create("https://example.invalid/b")))
				.thenReturn(fetched("https://example.invalid/b", "text/markdown", "# T\n"));
		when(indexingService.indexPayload("shows", "id\n1\n", "csv")).thenReturn(SUMMARY);
		when(indexingService.indexPayload("shows", "# T\n", "markdown")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", "https://example.invalid/a", null));
		assertEquals(SUMMARY, service.indexUrl("shows", "https://example.invalid/b", null));
	}

	@Test
	void aBlankExplicitFormatIsTreatedAsAbsent() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/plain", "[]"));
		when(indexingService.indexPayload("shows", "[]", "json")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", URL, "   "));
	}

	@Test
	void theFinalUrlsExtensionIsUsedWhenTheRequestedUrlHasNone() throws Exception {
		String requested = "https://example.invalid/latest";
		var body = fetched("https://example.invalid/releases/shows.json", "text/plain", "[]");
		when(fetcher.fetch(URI.create(requested))).thenReturn(body);
		when(indexingService.indexPayload("shows", "[]", "json")).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", requested, null));
	}

	@Test
	void textPlainWithoutAKnownExtensionIsAFormatErrorAndIndexesNothing() throws Exception {
		String url = "https://example.invalid/data.txt";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/plain", "id\n1\n"));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals(UrlIndexingService.FORMAT_UNRESOLVED, e.getMessage());
		verifyNoInteractions(indexingService);
	}

	@Test
	void htmlIsAFormatErrorThatSaysSo() throws Exception {
		String url = "https://example.invalid/page";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/html", "<html/>"));

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

	@Test
	void parseFailuresNameTheFormatAndSayNothingWasIndexed() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", "not json"));
		when(indexingService.indexPayload("shows", "not json", "json"))
				.thenThrow(new DocumentProcessingException("bad"));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals("Cannot parse the URL content as json. Check its syntax and format. Nothing was indexed.",
				e.getMessage());
	}

	@Test
	void solrFailuresAreStateErrors() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", "[]"));
		when(indexingService.indexPayload("shows", "[]", "json")).thenThrow(new SolrServerException("down"))
				.thenThrow(new IOException("connection reset"))
				.thenThrow(new SolrException(SolrException.ErrorCode.SERVER_ERROR, "commit failed"));

		for (int i = 0; i < 3; i++) {
			var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
			assertEquals(UrlIndexingService.SOLR_FAILED, e.getMessage());
		}
	}

	private static UrlFetcher.FetchedBody fetched(String url, String mediaType, String body) {
		return fetched(url, mediaType, StandardCharsets.UTF_8, body.getBytes(StandardCharsets.UTF_8));
	}

	private static UrlFetcher.FetchedBody fetched(String url, String mediaType, Charset charset, byte[] body) {
		return new UrlFetcher.FetchedBody(URI.create(url), mediaType, charset, body);
	}
}
