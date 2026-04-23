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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
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
		String json = "[{\"id\":\"1\",\"title\":\"Test\"}]";
		List<SolrInputDocument> mockDocs = createMockDocuments(1);
		when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		indexingService.indexJsonDocuments("test_collection", json);

		verify(indexingDocumentCreator).createSchemalessDocumentsFromJson(json);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexJsonDocuments_WhenDocumentCreatorThrowsException_ShouldPropagateException() throws Exception {
		String invalidJson = "not valid json";
		when(indexingDocumentCreator.createSchemalessDocumentsFromJson(invalidJson)).thenThrow(
				new org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException("Invalid JSON"));

		assertThrows(org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException.class, () -> {
			indexingService.indexJsonDocuments("test_collection", invalidJson);
		});
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void indexCsvDocuments_WithValidCsv_ShouldIndexDocuments() throws Exception {
		String csv = "id,title\n1,Test\n2,Test2";
		List<SolrInputDocument> mockDocs = createMockDocuments(2);
		when(indexingDocumentCreator.createSchemalessDocumentsFromCsv(csv)).thenReturn(mockDocs);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		indexingService.indexCsvDocuments("test_collection", csv);

		verify(indexingDocumentCreator).createSchemalessDocumentsFromCsv(csv);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexCsvDocuments_WhenDocumentCreatorThrowsException_ShouldPropagateException() throws Exception {
		String invalidCsv = "malformed csv data";
		when(indexingDocumentCreator.createSchemalessDocumentsFromCsv(invalidCsv)).thenThrow(
				new org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException("Invalid CSV"));

		assertThrows(org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException.class, () -> {
			indexingService.indexCsvDocuments("test_collection", invalidCsv);
		});
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void indexXmlDocuments_WithValidXml_ShouldIndexDocuments() throws Exception {
		String xml = "<documents><doc><id>1</id><title>Test</title></doc></documents>";
		List<SolrInputDocument> mockDocs = createMockDocuments(1);
		when(indexingDocumentCreator.createSchemalessDocumentsFromXml(xml)).thenReturn(mockDocs);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		indexingService.indexXmlDocuments("test_collection", xml);

		verify(indexingDocumentCreator).createSchemalessDocumentsFromXml(xml);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexXmlDocuments_WhenParserConfigurationFails_ShouldPropagateException() throws Exception {
		String xml = "<invalid>xml</invalid>";
		when(indexingDocumentCreator.createSchemalessDocumentsFromXml(xml)).thenThrow(
				new org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException("Parser error"));

		assertThrows(org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException.class, () -> {
			indexingService.indexXmlDocuments("test_collection", xml);
		});
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void indexXmlDocuments_WhenSaxExceptionOccurs_ShouldPropagateException() throws Exception {
		String xml = "<malformed><unclosed>";
		when(indexingDocumentCreator.createSchemalessDocumentsFromXml(xml))
				.thenThrow(new org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException(
						"SAX parsing error"));

		assertThrows(org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException.class, () -> {
			indexingService.indexXmlDocuments("test_collection", xml);
		});
		verify(solrClient, never()).add(anyString(), any(Collection.class));
		verify(solrClient, never()).commit(anyString());
	}

	@Test
	void indexDocuments_WithSmallBatch_ShouldIndexSuccessfully() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(5);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(5, result);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexDocuments_WithLargeBatch_ShouldProcessInBatches() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2500);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit(eq("test_collection"))).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(2500, result);
		verify(solrClient, times(3)).add(eq("test_collection"), any(Collection.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexDocuments_WhenBatchFails_ShouldRetryIndividually() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(3);

		when(solrClient.add(eq("test_collection"), any(List.class))).thenThrow(new SolrServerException("Batch error"));

		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(3, result);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient, times(3)).add(eq("test_collection"), any(SolrInputDocument.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexDocuments_WhenSomeIndividualDocumentsFail_ShouldIndexSuccessfulOnes() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(3);

		when(solrClient.add(eq("test_collection"), any(List.class))).thenThrow(new SolrServerException("Batch error"));

		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null)
				.thenThrow(new SolrServerException("Document error")).thenReturn(null);

		when(solrClient.commit("test_collection")).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(2, result);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient, times(3)).add(eq("test_collection"), any(SolrInputDocument.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexDocuments_WithEmptyList_ShouldStillCommit() throws Exception {
		List<SolrInputDocument> emptyDocs = new ArrayList<>();
		when(solrClient.commit("test_collection")).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", emptyDocs);

		assertEquals(0, result);
		verify(solrClient, never()).add(anyString(), any(List.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexDocuments_WhenCommitFails_ShouldPropagateException() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenThrow(new IOException("Commit failed"));

		assertThrows(IOException.class, () -> {
			indexingService.indexDocuments("test_collection", docs);
		});
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexDocuments_ShouldBatchCorrectly() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(1000);
		when(solrClient.add(eq("test_collection"), any(Collection.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(1000, result);

		ArgumentCaptor<Collection<SolrInputDocument>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(solrClient).add(eq("test_collection"), captor.capture());
		assertEquals(1000, captor.getValue().size());
		verify(solrClient).commit("test_collection");
	}

	@Test
	void indexJsonDocuments_WhenSolrClientThrowsException_ShouldPropagateException() throws Exception {
		String json = "[{\"id\":\"1\"}]";
		List<SolrInputDocument> mockDocs = createMockDocuments(1);
		when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
		when(solrClient.add(eq("test_collection"), any(List.class)))
				.thenThrow(new SolrServerException("Solr connection error"));
		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class)))
				.thenThrow(new SolrServerException("Solr connection error"));
		when(solrClient.commit("test_collection")).thenReturn(null);

		indexingService.indexJsonDocuments("test_collection", json);

		verify(solrClient).add(eq("test_collection"), any(List.class));
		verify(solrClient).add(eq("test_collection"), any(SolrInputDocument.class));
	}

	@Test
	void indexCsvDocuments_WhenSolrClientThrowsIOException_ShouldPropagateException() throws Exception {
		String csv = "id,title\n1,Test";
		List<SolrInputDocument> mockDocs = createMockDocuments(1);
		when(indexingDocumentCreator.createSchemalessDocumentsFromCsv(csv)).thenReturn(mockDocs);
		when(solrClient.add(eq("test_collection"), any(List.class))).thenThrow(new IOException("Network error"));
		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class)))
				.thenThrow(new IOException("Network error"));
		when(solrClient.commit("test_collection")).thenReturn(null);

		indexingService.indexCsvDocuments("test_collection", csv);

		verify(solrClient).add(eq("test_collection"), any(List.class));
		verify(solrClient).add(eq("test_collection"), any(SolrInputDocument.class));
	}

	@Test
	void indexDocuments_WithRuntimeException_ShouldRetryIndividually() throws Exception {
		List<SolrInputDocument> docs = createMockDocuments(2);

		when(solrClient.add(eq("test_collection"), any(List.class)))
				.thenThrow(new RuntimeException("Unexpected error"));

		when(solrClient.add(eq("test_collection"), any(SolrInputDocument.class))).thenReturn(null);
		when(solrClient.commit("test_collection")).thenReturn(null);

		int result = indexingService.indexDocuments("test_collection", docs);

		assertEquals(2, result);
		verify(solrClient).add(eq("test_collection"), any(Collection.class));
		verify(solrClient, times(2)).add(eq("test_collection"), any(SolrInputDocument.class));
		verify(solrClient).commit("test_collection");
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
}
