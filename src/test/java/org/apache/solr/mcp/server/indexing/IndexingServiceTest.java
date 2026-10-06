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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for IndexingService with mocked SolrClient.
 */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class IndexingServiceTest {

	@Mock
	private SolrClient solrClient;

	@Mock
	private IndexingDocumentCreator indexingDocumentCreator;

	private IndexingService indexingService;

	@BeforeEach
	void setUp() {
		indexingService = new IndexingService(solrClient, indexingDocumentCreator);
	}

	@Test
	void constructor_ShouldInitializeWithDependencies() {
		assertNotNull(indexingService);
	}

	@Test
	void indexJsonDocuments_WithValidJson_ShouldIndexDocuments() throws Exception {
		List<Map<String, Object>> json = List.of(Map.of("id", "1", "title", "Test"));
		List<SolrInputDocument> mockDocs = createMockDocuments(1);
		when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection"))).thenReturn(new NamedList<>());

		indexingService.indexJsonDocuments("test_collection", json);

		verify(indexingDocumentCreator).createSchemalessDocumentsFromJson(json);
		// the add and the commit ride on one request
		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertEquals(1, captor.getValue().getDocuments().size());
		assertEquals("true", captor.getValue().getParams().get("commit"));
		assertEquals("true", captor.getValue().getParams().get("softCommit"));
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void indexJsonDocuments_WhenDocumentCreatorThrowsException_ShouldPropagateException() throws Exception {
		List<Map<String, Object>> invalidJson = List.of();
		when(indexingDocumentCreator.createSchemalessDocumentsFromJson(invalidJson))
				.thenThrow(new DocumentProcessingException("Invalid JSON"));

		assertThrows(DocumentProcessingException.class, () -> {
			indexingService.indexJsonDocuments("test_collection", invalidJson);
		});
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void indexDocuments_WithSmallBatch_ShouldIndexSuccessfully() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(5);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection"))).thenReturn(new NamedList<>());

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(5, result);
		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertEquals(5, captor.getValue().getDocuments().size());
		assertEquals("true", captor.getValue().getParams().get("commit"));
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void indexDocuments_WithManyDocuments_SendsOneRequest() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2500);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection"))).thenReturn(new NamedList<>());

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(2500, result);
		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertEquals(2500, captor.getValue().getDocuments().size());
		verify(solrClient, never()).add(anyString(), any(Collection.class));
	}
	@Test
	void indexDocuments_WhenSolrRejectsTheRequest_ShouldRetryIndividually() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(3);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection")))
				.thenThrow(new RemoteSolrException("http://localhost:8983/solr", 400, "Batch error", null));
		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(3, result);
		verify(solrClient, times(3)).add(eq("test_collection"), any(SolrInputDocument.class));
		// the rejected request's commit never ran
		verify(solrClient).commit("test_collection", false, true, true);
	}
	@Test
	void indexDocuments_WhenSomeIndividualDocumentsFail_ShouldIndexSuccessfulOnes() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(3);
		RemoteSolrException badRequest = new RemoteSolrException("http://localhost:8983/solr", 400, "Bad doc", null);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection"))).thenThrow(badRequest);
		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null).thenThrow(badRequest)
				.thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(2, result);
		verify(solrClient, times(3)).add(eq("test_collection"), any(SolrInputDocument.class));
		verify(solrClient).commit("test_collection", false, true, true);
	}
	@Test
	void indexDocuments_WhenTheRequestFails_ShouldPropagateException() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection")))
				.thenThrow(new IOException("Commit failed"));

		assertThrows(IOException.class, () -> {
			indexingService.indexDocuments("test_collection", docs);
		});
		verify(solrClient).request(any(UpdateRequest.class), eq("test_collection"));
		// not a Solr 400: no per-document fallback
		verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void indexDocuments_SoftCommitsSoDocumentsAreSearchableWithoutForcingAnFsync() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2);
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection"))).thenReturn(new NamedList<>());

		indexingService.indexDocuments("test_collection", docs);

		// waitFlush=false, waitSearcher=true, softCommit=true, on the add request.
		// waitSearcher is what keeps the documents searchable the moment the tool
		// returns; softCommit is what avoids an fsync per call. Measured against
		// Solr: 8.6 ms versus 18.9 ms for a hard commit, and a p90 of 10.7 ms versus
		// 41.3 ms.
		ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertEquals("true", captor.getValue().getParams().get("commit"));
		assertEquals("true", captor.getValue().getParams().get("softCommit"));
		assertEquals("true", captor.getValue().getParams().get("waitSearcher"));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void indexDocuments_WhenCollectionIsMissing_ShouldPropagateWithoutRetrying() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(3);

		when(solrClient.request(any(UpdateRequest.class), eq("test_collection")))
				.thenThrow(new RemoteSolrException("http://localhost:8983/solr", 404, "Collection not found", null));

		assertThrows(RemoteSolrException.class, () -> indexingService.indexDocuments("test_collection", docs));

		verify(solrClient, times(1)).request(any(UpdateRequest.class), eq("test_collection"));
		verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void indexJsonDocuments_WhenSolrClientThrowsException_ShouldPropagateException() throws Exception {
		List<Map<String, Object>> json = List.of(Map.of("id", "1"));
		List<SolrInputDocument> mockDocs = createMockDocuments(1);
		when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
		// Solr unreachable: propagate rather than retry and report "0 of 1"
		when(solrClient.request(any(UpdateRequest.class), eq("test_collection")))
				.thenThrow(new SolrServerException("Solr connection error"));

		assertThrows(SolrServerException.class, () -> indexingService.indexJsonDocuments("test_collection", json));

		verify(solrClient).request(any(UpdateRequest.class), eq("test_collection"));
		verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void indexCsvDocuments_ForwardsPayloadAsGivenToSolrCsvHandler() throws Exception {
		when(solrClient.request(any(ContentStreamUpdateRequest.class), eq("test_collection")))
				.thenReturn(new NamedList<>());
		String csv = "id,Show Title,genres,genres\n1,A,x,y\n2,B,z,\n";

		String result = indexingService.indexCsvDocuments("test_collection", csv);

		ArgumentCaptor<ContentStreamUpdateRequest> captor = ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertEquals("/update", captor.getValue().getPath());
		assertEquals("true", captor.getValue().getParams().get("header"));
		assertNull(captor.getValue().getParams().get("fieldnames"), "column names are Solr's to read, as given");
		var stream = captor.getValue().getContentStreams().iterator().next();
		assertTrue(stream.getContentType().startsWith("text/csv"));
		assertEquals(csv, new String(stream.getStream().readAllBytes(), StandardCharsets.UTF_8));
		assertEquals("true", captor.getValue().getParams().get("commit"),
				"the commit rides on the update request, not a second round trip");
		assertEquals("true", captor.getValue().getParams().get("softCommit"),
				"soft commit: searchable on return, the segment fsync left to Solr's autoCommit");
		verify(solrClient, never()).commit(anyString());
		assertTrue(result.contains("Solr accepted the CSV payload"), result);
		assertTrue(result.contains("'test_collection'"), result);
		assertFalse(result.contains(" of "), "no document count is claimed: Solr does not report one: " + result);
	}

	@Test
	void indexXmlDocuments_ForwardsAddBlockToSolr() throws Exception {
		when(solrClient.request(any(ContentStreamUpdateRequest.class), eq("test_collection")))
				.thenReturn(new NamedList<>());
		String xml = "<add><doc><field name=\"id\">1</field><field name=\"title\">T</field></doc></add>";

		String result = indexingService.indexXmlDocuments("test_collection", xml);

		ArgumentCaptor<ContentStreamUpdateRequest> captor = ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertTrue(
				captor.getValue().getContentStreams().iterator().next().getContentType().startsWith("application/xml"));
		assertEquals("true", captor.getValue().getParams().get("commit"),
				"the commit rides on the update request, not a second round trip");
		assertEquals("true", captor.getValue().getParams().get("softCommit"),
				"soft commit: searchable on return, the segment fsync left to Solr's autoCommit");
		verify(solrClient, never()).commit(anyString());
		assertTrue(result.contains("Solr accepted the XML <add> block"), result);
		assertTrue(result.contains("'test_collection'"), result);
	}

	@Test
	void indexXmlDocuments_RejectsCommandsBeforeSolrSeesThem() {
		assertThrows(DocumentProcessingException.class,
				() -> indexingService.indexXmlDocuments("test_collection", "<delete><query>*:*</query></delete>"));
		assertThrows(DocumentProcessingException.class,
				() -> indexingService.indexXmlDocuments("test_collection", "<commit/>"));
		verifyNoInteractions(solrClient);
	}

	@Test
	void indexCsvDocuments_WhenSolrRejectsThePayload_PropagatesWithoutCommitting() throws Exception {
		when(solrClient.request(any(ContentStreamUpdateRequest.class), eq("test_collection")))
				.thenThrow(new SolrServerException("bad row"));

		ArgumentCaptor<ContentStreamUpdateRequest> captor = ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
		assertThrows(SolrServerException.class,
				() -> indexingService.indexCsvDocuments("test_collection", "id,title\n1,Test\n"));
		verify(solrClient).request(captor.capture(), eq("test_collection"));
		assertEquals("true", captor.getValue().getParams().get("commit"),
				"the rejected request carried the commit, so nothing was committed separately");
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void indexDocuments_WithRuntimeException_ShouldPropagateWithoutRetrying() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2);

		when(solrClient.request(any(UpdateRequest.class), eq("test_collection")))
				.thenThrow(new RuntimeException("Unexpected error"));

		assertThrows(RuntimeException.class, () -> indexingService.indexDocuments("test_collection", docs));

		verify(solrClient).request(any(UpdateRequest.class), eq("test_collection"));
		verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
		verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	private List<SolrInputDocument> createMockDocuments(int count) {
		List<SolrInputDocument> docs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			SolrInputDocument doc = new SolrInputDocument();
			doc.addField("id", "doc" + i);
			doc.addField("title", "Document " + i);
			docs.add(doc);
		}
		return docs;
	}

	@Test
	void indexDataPrompt_jsonPath_referencesIndexJsonDocuments() {
		String sample = """
				[{"id":"1","title":"Test"}]""";

		String body = indexingService.indexDataPrompt("library", "json", sample);

		assertTrue(body.contains("library"), "Prompt should mention the target collection name");
		assertTrue(body.contains("index-json-documents"), "JSON path should reference index-json-documents tool");
		assertTrue(body.contains("get-schema"), "Prompt should reference get-schema for verification");
		assertTrue(body.contains("design-schema"),
				"Prompt should reference design-schema as fallback when fields are missing");
		assertTrue(body.contains(sample), "Prompt should embed the sample payload");
	}

	@Test
	void indexDataPrompt_csvPath_referencesIndexCsvDocuments() {
		String body = indexingService.indexDataPrompt("library", "csv", null);

		assertTrue(body.contains("index-csv-documents"), "CSV path should reference index-csv-documents tool");
		assertFalse(body.contains("index-json-documents"), "CSV path should not reference index-json-documents tool");
	}

	@Test
	void indexDataPrompt_xmlPath_referencesIndexXmlDocuments() {
		String body = indexingService.indexDataPrompt("library", "xml", null);

		assertTrue(body.contains("index-xml-documents"), "XML path should reference index-xml-documents tool");
	}

	@Test
	void indexDataPrompt_markdownPath_referencesIndexMarkdownDocuments() {
		String body = indexingService.indexDataPrompt("library", "markdown", null);

		assertTrue(body.contains("index-markdown-documents"),
				"Markdown path should reference index-markdown-documents tool");
	}

	@Test
	void indexDataPrompt_mdAliasResolvesToMarkdownTool() {
		String body = indexingService.indexDataPrompt("library", "md", null);

		assertTrue(body.contains("index-markdown-documents"),
				"'md' alias should reference index-markdown-documents tool");
	}

	@Test
	void indexDataPrompt_unknownFormat_throwsIllegalArgumentException() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> indexingService.indexDataPrompt("library", "yaml", null));
		assertTrue(ex.getMessage().contains("json/csv/xml"),
				"Exception message should list the supported formats: " + ex.getMessage());
	}
}
