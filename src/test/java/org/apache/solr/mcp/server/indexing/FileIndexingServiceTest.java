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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.CsvDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.JsonDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.MarkdownDocumentCreator;
import org.apache.solr.mcp.server.indexing.documentcreator.XmlDocumentCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class FileIndexingServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	SolrClient solrClient;

	private IndexingService indexingService;
	private FileIndexingService fileIndexingService;

	@BeforeEach
	void setUp() {
		var creator = new IndexingDocumentCreator(new XmlDocumentCreator(), new CsvDocumentCreator(),
				new JsonDocumentCreator(new ObjectMapper()), new MarkdownDocumentCreator());
		indexingService = new IndexingService(solrClient, creator);
		fileIndexingService = new FileIndexingService(indexingService);
	}

	@Test
	void reusesShowsFileAcrossCollectionsWithoutReturningPayload() throws Exception {
		Path file = tempDir.resolve("shows.json");
		try (var input = getClass().getResourceAsStream("/shows.json")) {
			assertNotNull(input);
			Files.copy(input, file);
		}
		String first = fileIndexingService.indexFile("shows", file.toString(), null);
		String second = fileIndexingService.indexFile("shows-copy", file.toString(), "json");
		assertTrue(first.contains("61 of 61"), first);
		assertTrue(second.contains("61 of 61"), second);
		assertTrue(first.contains("platform"));
		assertFalse(first.contains("Stranger Things"));
		assertTrue(Files.exists(file), "The file must remain reusable");
		verify(solrClient).add(eq("shows"), argThat((Collection<SolrInputDocument> docs) -> docs.size() == 61));
		verify(solrClient).add(eq("shows-copy"), argThat((Collection<SolrInputDocument> docs) -> docs.size() == 61));
		verify(solrClient).commit("shows");
		verify(solrClient).commit("shows-copy");
	}

	@Test
	void acceptsRelativePathsWithoutConfiguration() throws Exception {
		String result = fileIndexingService.indexFile("shows", "src/test/resources/shows.json", " ");
		assertTrue(result.contains("61 of 61"), result);
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "csv", "xml", "md", "markdown", "JSON"})
	void detectsAllSupportedFormats(String extension) throws Exception {
		String content = switch (extension) {
			case "json", "JSON" -> "{\"id\":\"one\",\"title\":\"Café\"}";
			case "csv" -> "id,title\none,Café\n";
			case "xml" -> "<documents><document><id>one</id><title>Café</title></document>"
					+ "<document><id>two</id><title>Café</title></document></documents>";
			default -> "---\nid: one\n---\n# Café\n";
		};
		Path file = Files.writeString(tempDir.resolve("data." + extension), content);
		String result = fileIndexingService.indexFile("shows", file.toString(), null);
		int count = extension.equals("xml") ? 2 : 1;
		assertTrue(result.contains(count + " of " + count), result);
		String prefix = extension.equals("xml") ? "document_" : "";
		verify(solrClient).add(eq("shows"),
				argThat((Collection<SolrInputDocument> docs) -> docs.size() == count
						&& docs.stream().allMatch(doc -> "Café".equals(doc.getFieldValue(prefix + "title"))
								&& doc.getFieldValue(prefix + "id") != null)));
	}

	@Test
	void explicitFormatOverridesUnknownExtension() throws Exception {
		Path file = Files.writeString(tempDir.resolve("download.tmp"), "{\"id\":\"one\"}");
		assertTrue(fileIndexingService.indexFile("shows", file.toString(), " JSON ").contains("1 of 1"));
		assertThrows(IllegalArgumentException.class,
				() -> fileIndexingService.indexFile("shows", file.toString(), null));
		assertThrows(IllegalArgumentException.class,
				() -> fileIndexingService.indexFile("shows", file.toString(), "yaml"));
	}

	@Test
	void allowsReadableSymlinkTargetsWithoutAnIngestRoot() throws Exception {
		Path file = Files.writeString(tempDir.resolve("data.json"), "{\"id\":\"one\"}");
		Path link = Files.createSymbolicLink(tempDir.resolve("link.json"), file);
		assertTrue(fileIndexingService.indexFile("shows", link.toString(), null).contains("1 of 1"));
	}

	@Test
	void rejectsMissingDirectoryInvalidAndBlankPathsWithoutLeakingDetails() {
		for (String path : List.of("missing.json", tempDir.toString(), "", "  ", "\u0000",
				"https://example.com/shows.json")) {
			var error = assertThrows(IllegalArgumentException.class,
					() -> fileIndexingService.indexFile("shows", path, null));
			assertNull(error.getCause());
			assertFalse(error.getMessage().contains(tempDir.toString()));
		}
		assertThrows(IllegalArgumentException.class, () -> fileIndexingService.indexFile("shows", null, null));
		assertThrows(IllegalArgumentException.class, () -> fileIndexingService.indexFile(" ", "shows.json", null));
		verifyNoInteractions(solrClient);
	}

	@Test
	void rejectsNonUtf8FilesBeforeIndexing() throws Exception {
		Path file = Files.write(tempDir.resolve("bad.json"), new byte[]{(byte) 0xc3, (byte) 0x28});
		var error = assertThrows(RuntimeException.class,
				() -> fileIndexingService.indexFile("shows", file.toString(), null));
		assertTrue(error.getMessage().contains("UTF-8"));
		assertNull(error.getCause());
		verifyNoInteractions(solrClient);
	}

	@Test
	void malformedJsonHasActionableErrorAndNoSolrWrites() throws Exception {
		for (String json : List.of("", "not-json-secret-content", "42")) {
			Path file = Files.writeString(tempDir.resolve("bad.json"), json);
			var error = assertThrows(IllegalArgumentException.class,
					() -> fileIndexingService.indexFile("shows", file.toString(), null));
			assertTrue(error.getMessage().contains("Check its syntax and format"));
			assertFalse(error.getMessage().contains("secret"));
			assertNull(error.getCause());
		}
		verifyNoInteractions(solrClient);
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "csv", "xml"})
	void streamsFilesLargerThanTenMiBInBoundedBatches(String format) throws Exception {
		Path file = tempDir.resolve("large." + format);
		String value = "x".repeat(1024);
		try (var writer = Files.newBufferedWriter(file)) {
			writer.write(switch (format) {
				case "json" -> "[";
				case "csv" -> "id,title\n";
				default -> "<documents>";
			});
			for (int i = 0; i < 11001; i++) {
				writer.write(switch (format) {
					case "json" -> (i == 0 ? "" : ",") + "{\"id\":\"" + i + "\",\"title\":\"" + value + "\"}";
					case "csv" -> i + "," + value + "\n";
					default -> "<document><id>" + i + "</id><title>" + value + "</title></document>";
				});
			}
			writer.write(switch (format) {
				case "json" -> "]";
				case "csv" -> "";
				default -> "</documents>";
			});
		}
		assertTrue(Files.size(file) > 10 * 1024 * 1024);
		List<Integer> sizes = new ArrayList<>();
		doAnswer(invocation -> {
			Collection<SolrInputDocument> docs = invocation.getArgument(1);
			sizes.add(docs.size());
			return null;
		}).when(solrClient).add(eq("shows"), anyCollection());
		String result = fileIndexingService.indexFile("shows", file.toString(), null);
		assertTrue(result.contains("11001 of 11001"), result);
		assertEquals(12, sizes.size());
		assertTrue(sizes.stream().allMatch(size -> size > 0 && size <= 1000));
		assertEquals(1, sizes.getLast());
		verify(solrClient).commit("shows");
	}

	@Test
	void markdownLargerThanTenMiBRemainsOneDocument() throws Exception {
		Path file = tempDir.resolve("large.md");
		try (var writer = Files.newBufferedWriter(file)) {
			writer.write("---\nid: one\n---\n# Large document\n");
			writer.write("x".repeat(11 * 1024 * 1024));
		}
		assertTrue(fileIndexingService.indexFile("shows", file.toString(), null).contains("1 of 1"));
		verify(solrClient).add(eq("shows"), argThat((Collection<SolrInputDocument> docs) -> docs.size() == 1));
	}

	@Test
	void malformedTailReportsPossiblePartialWritesWithoutCommitting() throws Exception {
		Path file = Files.writeString(tempDir.resolve("tail.json"), "[" + "{\"id\":\"one\"},".repeat(1000) + "broken]");
		var error = assertThrows(IllegalArgumentException.class,
				() -> fileIndexingService.indexFile("shows", file.toString(), null));
		assertTrue(error.getMessage().contains("Some documents may already be indexed"));
		assertNull(error.getCause());
		verify(solrClient).add(eq("shows"), argThat((Collection<SolrInputDocument> docs) -> docs.size() == 1000));
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void reportsPartialBatchFailuresWithActualCounts() throws Exception {
		Path file = Files.writeString(tempDir.resolve("partial.json"), "[{\"id\":\"good\"},{\"id\":\"bad\"}]");
		when(solrClient.add(eq("shows"), anyCollection())).thenThrow(new IOException("private batch failure"));
		when(solrClient.add(eq("shows"), any(SolrInputDocument.class))).thenAnswer(invocation -> {
			SolrInputDocument document = invocation.getArgument(1);
			if ("bad".equals(document.getFieldValue("id"))) {
				throw new IOException("private document failure");
			}
			return null;
		});
		String result = fileIndexingService.indexFile("shows", file.toString(), null);
		assertTrue(result.contains("1 of 2"), result);
		assertTrue(result.contains("get-schema"), result);
		assertFalse(result.contains("private"));
		verify(solrClient).commit("shows");
	}

	@Test
	void solrFailureDoesNotLeakBackendDetails() throws Exception {
		Path file = Files.writeString(tempDir.resolve("single.json"), "{\"id\":\"one\"}");
		when(solrClient.commit("shows")).thenThrow(new SolrServerException("private backend address"));
		var error = assertThrows(IllegalStateException.class,
				() -> fileIndexingService.indexFile("shows", file.toString(), null));
		assertTrue(error.getMessage().contains("get-schema"));
		assertTrue(error.getMessage().contains("some documents may already be indexed"));
		assertFalse(error.getMessage().contains("private"));
		assertNull(error.getCause());
	}

	@Test
	void registersOnlyForLocalStdioWithoutConfiguration() {
		var runner = new ApplicationContextRunner().withUserConfiguration(FileIndexingService.class)
				.withBean(IndexingService.class, () -> indexingService);
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("stdio"))
				.run(context -> assertEquals(1, context.getBeansOfType(FileIndexingService.class).size()));
		for (String[] profiles : List.of(new String[]{"http"}, new String[]{"stdio", "http"})) {
			runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
					.run(context -> assertTrue(context.getBeansOfType(FileIndexingService.class).isEmpty()));
		}
		new WebApplicationContextRunner().withUserConfiguration(FileIndexingService.class)
				.withBean(IndexingService.class, () -> indexingService)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("stdio"))
				.run(context -> assertTrue(context.getBeansOfType(FileIndexingService.class).isEmpty()));
	}
}
