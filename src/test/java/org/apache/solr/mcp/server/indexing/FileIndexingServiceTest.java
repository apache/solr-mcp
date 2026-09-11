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
import java.util.Collection;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class FileIndexingServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	SolrClient solrClient;

	private Path root;
	private IndexingService indexingService;
	private FileIndexingService fileIndexingService;

	@BeforeEach
	void setUp() throws IOException {
		root = Files.createDirectory(tempDir.resolve("ingest"));
		var creator = new IndexingDocumentCreator(new XmlDocumentCreator(), new CsvDocumentCreator(),
				new JsonDocumentCreator(new ObjectMapper()), new MarkdownDocumentCreator());
		indexingService = new IndexingService(solrClient, creator);
		fileIndexingService = new FileIndexingService(indexingService, root.toString());
	}

	@Test
	void reusesShowsFileAcrossCollectionsWithoutReturningPayload() throws Exception {
		Path file = root.resolve("shows.json");
		try (var input = getClass().getResourceAsStream("/shows.json")) {
			assertNotNull(input);
			Files.copy(input, file);
		}
		String first = fileIndexingService.indexJsonFile("shows", "shows.json");
		String second = fileIndexingService.indexJsonFile("shows-copy", file.toString());
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
	void disabledByDefault() {
		var disabled = new FileIndexingService(indexingService, "");
		var error = assertThrows(IllegalArgumentException.class, () -> disabled.indexJsonFile("shows", "shows.json"));
		assertTrue(error.getMessage().contains("disabled"));
		verifyNoInteractions(solrClient);
	}

	@Test
	void rejectsTraversalAbsolutePathsAndSiblingPrefixOutsideRoot() throws Exception {
		Path outside = Files.writeString(tempDir.resolve("secret.json"), "[{\"id\":\"secret\"}]");
		Path sibling = Files.createDirectory(tempDir.resolve("ingest-other"));
		Path siblingFile = Files.writeString(sibling.resolve("shows.json"), "[]");
		for (String path : List.of("../secret.json", outside.toString(), siblingFile.toString())) {
			var error = assertThrows(IllegalArgumentException.class,
					() -> fileIndexingService.indexJsonFile("shows", path));
			assertTrue(error.getMessage().contains("inside SOLR_MCP_INGEST_ROOT"));
			assertFalse(error.getMessage().contains("secret"));
		}
		verifyNoInteractions(solrClient);
	}

	@Test
	void rejectsSymlinksToFilesAndDirectoriesOutsideRoot() throws Exception {
		Path outside = Files.writeString(tempDir.resolve("secret.json"), "[]");
		Files.createSymbolicLink(root.resolve("file-link"), outside);
		Files.createSymbolicLink(root.resolve("dir-link"), tempDir);
		for (String path : List.of("file-link", "dir-link/secret.json")) {
			assertThrows(IllegalArgumentException.class, () -> fileIndexingService.indexJsonFile("shows", path));
		}
		verifyNoInteractions(solrClient);
	}

	@Test
	void rejectsMissingDirectoryInvalidAndBlankPathsWithoutLeakingDetails() {
		for (String path : List.of("missing.json", ".", "", "  ", "\u0000", "https://example.com/shows.json")) {
			var error = assertThrows(IllegalArgumentException.class,
					() -> fileIndexingService.indexJsonFile("shows", path));
			assertNull(error.getCause());
			assertFalse(error.getMessage().contains(tempDir.toString()));
		}
		assertThrows(IllegalArgumentException.class, () -> fileIndexingService.indexJsonFile("shows", null));
		assertThrows(IllegalArgumentException.class, () -> fileIndexingService.indexJsonFile(" ", "shows.json"));
		verifyNoInteractions(solrClient);
	}

	@Test
	void rejectsOversizedAndNonUtf8FilesBeforeIndexing() throws Exception {
		Files.write(root.resolve("large.json"), new byte[10 * 1024 * 1024 + 1]);
		var oversized = assertThrows(IllegalArgumentException.class,
				() -> fileIndexingService.indexJsonFile("shows", "large.json"));
		assertTrue(oversized.getMessage().contains("10 MiB"));
		Files.write(root.resolve("bad-encoding.json"), new byte[]{(byte) 0xc3, (byte) 0x28});
		var encoding = assertThrows(IllegalArgumentException.class,
				() -> fileIndexingService.indexJsonFile("shows", "bad-encoding.json"));
		assertTrue(encoding.getMessage().contains("UTF-8"));
		verifyNoInteractions(solrClient);
	}

	@Test
	void malformedJsonHasActionableErrorAndNoSolrWrites() throws Exception {
		for (String json : List.of("", "not-json-secret-content", "42")) {
			Files.writeString(root.resolve("bad.json"), json);
			var error = assertThrows(IllegalArgumentException.class,
					() -> fileIndexingService.indexJsonFile("shows", "bad.json"));
			assertTrue(error.getMessage().contains("JSON object or array"));
			assertFalse(error.getMessage().contains("secret"));
			assertNull(error.getCause());
		}
		verifyNoInteractions(solrClient);
	}

	@Test
	void solrFailureDoesNotLeakBackendDetails() throws Exception {
		Files.writeString(root.resolve("single.json"), "{\"id\":\"one\",\"title\":\"Café\"}");
		when(solrClient.commit("shows")).thenThrow(new IOException("private backend address"));
		var error = assertThrows(IllegalStateException.class,
				() -> fileIndexingService.indexJsonFile("shows", "single.json"));
		assertTrue(error.getMessage().contains("get-schema"));
		assertTrue(error.getMessage().contains("some documents may already be indexed"));
		assertFalse(error.getMessage().contains("private"));
		assertNull(error.getCause());
	}
}
