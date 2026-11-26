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

import static org.apache.solr.mcp.server.McpResultAssertions.promptText;
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
import org.apache.solr.mcp.server.indexing.IndexingResult.Format;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for IndexingService with mocked SolrClient. */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class IndexingServiceTest {

  private static final String COLLECTION = "test_collection";

  private static final RemoteSolrException BAD_REQUEST =
      new RemoteSolrException("http://localhost:8983/solr", 400, "Bad doc", null);

  @Mock private SolrClient solrClient;

  @Mock private IndexingDocumentCreator indexingDocumentCreator;

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
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    indexingService.indexJsonDocuments(COLLECTION, json);

    verify(indexingDocumentCreator).createSchemalessDocumentsFromJson(json);
    // the add and the commit ride on one request
    ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertEquals(1, captor.getValue().getDocuments().size());
    assertEquals("true", captor.getValue().getParams().get("commit"));
    assertEquals("true", captor.getValue().getParams().get("softCommit"));
    verify(solrClient, never()).add(anyString(), any(Collection.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexJsonDocuments_WhenEveryDocumentIsAccepted_ReportsAllIndexed() throws Exception {
    List<Map<String, Object>> json =
        List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"));
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json))
        .thenReturn(createMockDocuments(3));
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    IndexingResult result = indexingService.indexJsonDocuments(COLLECTION, json);

    assertEquals(
        new IndexingResult(COLLECTION, Format.json, 3, 3, List.of("id", "title"), 2), result);
  }

  @Test
  void indexJsonDocuments_WhenSolrRejectsSomeDocuments_ReportsOnlyTheAcceptedOnes()
      throws Exception {
    List<Map<String, Object>> json = List.of(Map.of("id", "1"));
    List<SolrInputDocument> documents = createMockDocuments(5);
    // only the two documents Solr rejects carry this field
    documents.get(1).addField("colour", "red");
    documents.get(3).addField("colour", "blue");
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(documents);
    // the batch fails with a 400, then the per-document retry rejects two of the five
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION))).thenThrow(BAD_REQUEST);
    when(solrClient.add(eq(COLLECTION), any(SolrInputDocument.class)))
        .thenReturn(null)
        .thenThrow(BAD_REQUEST)
        .thenReturn(null)
        .thenThrow(BAD_REQUEST)
        .thenReturn(null);

    IndexingResult result = indexingService.indexJsonDocuments(COLLECTION, json);

    assertEquals(5, result.documentsSubmitted());
    assertEquals(3, result.documentsIndexed());
    assertEquals(Format.json, result.format());
    assertEquals(COLLECTION, result.collection());
    assertEquals(
        List.of("id", "title"),
        result.fieldNames(),
        "field names come from the accepted documents only");
    assertEquals(2, result.fieldCount());
    verify(solrClient, times(5)).add(eq(COLLECTION), any(SolrInputDocument.class));
  }

  @Test
  void indexJsonDocuments_WhenSolrRejectsEveryDocument_FailsWithAFixedMessage() throws Exception {
    List<Map<String, Object>> json = List.of(Map.of("id", "1"));
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json))
        .thenReturn(createMockDocuments(3));
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION))).thenThrow(BAD_REQUEST);
    when(solrClient.add(eq(COLLECTION), any(SolrInputDocument.class))).thenThrow(BAD_REQUEST);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> indexingService.indexJsonDocuments(COLLECTION, json));

    assertEquals(
        IndexingService.EVERY_DOCUMENT_REJECTED,
        failure.getMessage(),
        "the message is the server's own phrase, with no Solr text");
    verify(solrClient, times(3)).add(eq(COLLECTION), any(SolrInputDocument.class));
  }

  @Test
  void indexJsonDocuments_WithNoDocuments_ReportsZeroWithoutFailing() throws Exception {
    List<Map<String, Object>> json = List.of();
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(List.of());
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    IndexingResult result = indexingService.indexJsonDocuments(COLLECTION, json);

    assertEquals(new IndexingResult(COLLECTION, Format.json, 0, 0, List.of(), 0), result);
  }

  @Test
  void indexDocuments_WhenARetriedDocumentFailsWithANon400_PropagatesWithoutCommitting()
      throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(3);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION))).thenThrow(BAD_REQUEST);
    when(solrClient.add(eq(COLLECTION), any(SolrInputDocument.class)))
        .thenReturn(null)
        .thenThrow(new RemoteSolrException("http://localhost:8983/solr", 503, "Unavailable", null));

    RemoteSolrException failure =
        assertThrows(
            RemoteSolrException.class, () -> indexingService.indexDocuments(COLLECTION, docs));

    assertEquals(503, failure.code(), "a 503 is not a rejected document");
    verify(solrClient, times(2)).add(eq(COLLECTION), any(SolrInputDocument.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexJsonDocuments_ListsTheFirstFiftyFieldNamesInSortOrder() throws Exception {
    List<Map<String, Object>> json = List.of(Map.of("id", "1"));
    SolrInputDocument wide = new SolrInputDocument();
    for (int fieldNumber = 59; fieldNumber >= 0; fieldNumber--) {
      wide.addField("field_%02d".formatted(fieldNumber), fieldNumber);
    }
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(List.of(wide));
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    IndexingResult result = indexingService.indexJsonDocuments(COLLECTION, json);

    List<String> fieldNames = result.fieldNames();
    assertNotNull(fieldNames);
    assertEquals(IndexingService.MAX_REPORTED_FIELDS, fieldNames.size());
    assertEquals("field_00", fieldNames.getFirst());
    assertEquals("field_49", fieldNames.getLast());
    assertEquals(60, result.fieldCount(), "fieldCount tells the client the list was cut short");
  }

  @Test
  void indexMarkdownDocuments_WhenOneInputYieldsSeveralDocuments_CountsEachOfThem()
      throws Exception {
    when(indexingDocumentCreator.createSchemalessDocumentsFromMarkdown("# Guide"))
        .thenReturn(createMockDocuments(4));
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    IndexingResult result = indexingService.indexMarkdownDocuments(COLLECTION, List.of("# Guide"));

    assertEquals(4, result.documentsSubmitted());
    assertEquals(4, result.documentsIndexed());
    assertEquals(Format.markdown, result.format());
  }

  @Test
  void indexingResult_IsImmutable() {
    List<String> fieldNames = new ArrayList<>(List.of("id"));
    IndexingResult result = new IndexingResult(COLLECTION, Format.json, 1, 1, fieldNames, 2);

    fieldNames.add("title");

    List<String> copied = result.fieldNames();
    assertEquals(List.of("id"), copied);
    assertNotNull(copied);
    assertThrows(UnsupportedOperationException.class, () -> copied.add("title"));
  }

  @Test
  void indexingResult_AcceptsOnlyItsTwoLegalShapes() {
    List<String> names = List.of("id", "title");

    assertDoesNotThrow(() -> new IndexingResult(COLLECTION, Format.json, 5, 3, names, 2));
    assertDoesNotThrow(() -> new IndexingResult(COLLECTION, Format.json, 5, 3, names, 60));
    assertDoesNotThrow(() -> new IndexingResult(COLLECTION, Format.csv, null, null, null, null));
    // counts and field names are present together or not at all
    assertThrows(
        IllegalArgumentException.class,
        () -> new IndexingResult(COLLECTION, Format.json, 5, 3, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IndexingResult(COLLECTION, Format.json, 5, 3, names, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IndexingResult(COLLECTION, Format.csv, null, null, null, 2));
    // more documents indexed than submitted, or a negative count
    assertThrows(
        IllegalArgumentException.class,
        () -> new IndexingResult(COLLECTION, Format.json, 3, 5, names, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IndexingResult(COLLECTION, Format.json, 3, -1, names, 2));
    // more names listed than the count says there are
    assertThrows(
        IllegalArgumentException.class,
        () -> new IndexingResult(COLLECTION, Format.json, 3, 3, names, 1));
  }

  @Test
  void indexJsonDocuments_WhenDocumentCreatorThrowsException_ShouldPropagateException()
      throws Exception {
    List<Map<String, Object>> invalidJson = List.of();
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(invalidJson))
        .thenThrow(new DocumentProcessingException("Invalid JSON"));

    assertThrows(
        DocumentProcessingException.class,
        () -> {
          indexingService.indexJsonDocuments(COLLECTION, invalidJson);
        });
    verify(solrClient, never()).add(anyString(), any(Collection.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexDocuments_WithSmallBatch_ShouldIndexSuccessfully() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(5);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    List<SolrInputDocument> result = indexingService.indexDocuments(COLLECTION, docs);

    assertEquals(5, result.size());
    ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertEquals(5, captor.getValue().getDocuments().size());
    assertEquals("true", captor.getValue().getParams().get("commit"));
    verify(solrClient, never()).add(anyString(), any(Collection.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexDocuments_WithManyDocuments_SendsOneRequest() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(2500);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    List<SolrInputDocument> result = indexingService.indexDocuments(COLLECTION, docs);

    assertEquals(2500, result.size());
    ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertEquals(2500, captor.getValue().getDocuments().size());
    verify(solrClient, never()).add(anyString(), any(Collection.class));
  }

  @Test
  void indexDocuments_WhenSolrRejectsTheRequest_ShouldRetryIndividually() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(3);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenThrow(new RemoteSolrException("http://localhost:8983/solr", 400, "Batch error", null));
    when(solrClient.add(eq(COLLECTION), any(SolrInputDocument.class))).thenReturn(null);

    List<SolrInputDocument> result = indexingService.indexDocuments(COLLECTION, docs);

    assertEquals(3, result.size());
    verify(solrClient, times(3)).add(eq(COLLECTION), any(SolrInputDocument.class));
    // the rejected request's commit never ran
    verify(solrClient).commit(COLLECTION, false, true, true);
  }

  @Test
  void indexDocuments_WhenSomeIndividualDocumentsFail_ShouldIndexSuccessfulOnes() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(3);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION))).thenThrow(BAD_REQUEST);
    when(solrClient.add(eq(COLLECTION), any(SolrInputDocument.class)))
        .thenReturn(null)
        .thenThrow(BAD_REQUEST)
        .thenReturn(null);

    List<SolrInputDocument> result = indexingService.indexDocuments(COLLECTION, docs);

    assertEquals(2, result.size());
    verify(solrClient, times(3)).add(eq(COLLECTION), any(SolrInputDocument.class));
    verify(solrClient).commit(COLLECTION, false, true, true);
  }

  @Test
  void indexDocuments_WhenTheRequestFails_ShouldPropagateException() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(2);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenThrow(new IOException("Commit failed"));

    assertThrows(
        IOException.class,
        () -> {
          indexingService.indexDocuments(COLLECTION, docs);
        });
    verify(solrClient).request(any(UpdateRequest.class), eq(COLLECTION));
    // not a Solr 400: no per-document fallback
    verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexDocuments_SoftCommitsSoDocumentsAreSearchableWithoutForcingAnFsync() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(2);
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    indexingService.indexDocuments(COLLECTION, docs);

    // waitFlush=false, waitSearcher=true, softCommit=true, on the add request.
    // waitSearcher is what keeps the documents searchable the moment the tool
    // returns; softCommit is what avoids an fsync per call. Measured against
    // Solr: 8.6 ms versus 18.9 ms for a hard commit, and a p90 of 10.7 ms versus
    // 41.3 ms.
    ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertEquals("true", captor.getValue().getParams().get("commit"));
    assertEquals("true", captor.getValue().getParams().get("softCommit"));
    assertEquals("true", captor.getValue().getParams().get("waitSearcher"));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(solrClient, never()).commit(anyString());
  }

  @Test
  void indexDocuments_WhenCollectionIsMissing_ShouldPropagateWithoutRetrying() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(3);

    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenThrow(
            new RemoteSolrException(
                "http://localhost:8983/solr", 404, "Collection not found", null));

    assertThrows(RemoteSolrException.class, () -> indexingService.indexDocuments(COLLECTION, docs));

    verify(solrClient, times(1)).request(any(UpdateRequest.class), eq(COLLECTION));
    verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
    verify(solrClient, never()).add(anyString(), any(Collection.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexJsonDocuments_WhenSolrClientThrowsException_ShouldPropagateException()
      throws Exception {
    List<Map<String, Object>> json = List.of(Map.of("id", "1"));
    List<SolrInputDocument> mockDocs = createMockDocuments(1);
    when(indexingDocumentCreator.createSchemalessDocumentsFromJson(json)).thenReturn(mockDocs);
    // Solr unreachable: propagate rather than retry and report "0 of 1"
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenThrow(new SolrServerException("Solr connection error"));

    assertThrows(
        SolrServerException.class, () -> indexingService.indexJsonDocuments(COLLECTION, json));

    verify(solrClient).request(any(UpdateRequest.class), eq(COLLECTION));
    verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
    verify(solrClient, never()).commit(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  @Test
  void indexCsvDocuments_ForwardsPayloadAsGivenToSolrCsvHandler() throws Exception {
    when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());
    String csv = "id,Show Title,genres,genres\n1,A,x,y\n2,B,z,\n";

    IndexingResult result = indexingService.indexCsvDocuments(COLLECTION, csv);

    ArgumentCaptor<ContentStreamUpdateRequest> captor =
        ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertEquals("/update", captor.getValue().getPath());
    assertEquals("true", captor.getValue().getParams().get("header"));
    assertNull(
        captor.getValue().getParams().get("fieldnames"),
        "column names are Solr's to read, as given");
    var stream = captor.getValue().getContentStreams().iterator().next();
    assertTrue(stream.getContentType().startsWith("text/csv"));
    assertEquals(csv, new String(stream.getStream().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals(
        "true",
        captor.getValue().getParams().get("commit"),
        "the commit rides on the update request, not a second round trip");
    assertEquals(
        "true",
        captor.getValue().getParams().get("softCommit"),
        "soft commit: searchable on return, the segment fsync left to Solr's autoCommit");
    verify(solrClient, never()).commit(anyString());
    assertEquals(
        new IndexingResult(COLLECTION, Format.csv, null, null, null, null),
        result,
        "no document count or field names are claimed: Solr does not report them");
    assertEquals(
        "{\"collection\":\"test_collection\",\"format\":\"csv\"}",
        JsonMapper.builder().build().writeValueAsString(result),
        "the absent counts are left out of the JSON, not sent as null");
  }

  @Test
  void indexXmlDocuments_ForwardsAddBlockToSolr() throws Exception {
    when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());
    String xml =
        "<add><doc><field name=\"id\">1</field><field name=\"title\">T</field></doc></add>";

    IndexingResult result = indexingService.indexXmlDocuments(COLLECTION, xml);

    ArgumentCaptor<ContentStreamUpdateRequest> captor =
        ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertTrue(
        captor
            .getValue()
            .getContentStreams()
            .iterator()
            .next()
            .getContentType()
            .startsWith("application/xml"));
    assertEquals(
        "true",
        captor.getValue().getParams().get("commit"),
        "the commit rides on the update request, not a second round trip");
    assertEquals(
        "true",
        captor.getValue().getParams().get("softCommit"),
        "soft commit: searchable on return, the segment fsync left to Solr's autoCommit");
    verify(solrClient, never()).commit(anyString());
    assertEquals(new IndexingResult(COLLECTION, Format.xml, null, null, null, null), result);
  }

  @Test
  void indexXmlDocuments_WhenSolrRejectsThePayload_Throws() throws Exception {
    when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
        .thenThrow(BAD_REQUEST);

    assertThrows(
        RemoteSolrException.class,
        () ->
            indexingService.indexXmlDocuments(
                COLLECTION, "<add><doc><field name=\"id\">1</field></doc></add>"));
    verify(solrClient, never()).add(anyString(), any(SolrInputDocument.class));
  }

  @Test
  void indexXmlDocuments_RejectsCommandsBeforeSolrSeesThem() {
    assertThrows(
        DocumentProcessingException.class,
        () -> indexingService.indexXmlDocuments(COLLECTION, "<delete><query>*:*</query></delete>"));
    assertThrows(
        DocumentProcessingException.class,
        () -> indexingService.indexXmlDocuments(COLLECTION, "<commit/>"));
    verifyNoInteractions(solrClient);
  }

  @Test
  void indexCsvDocuments_WhenSolrRejectsThePayload_PropagatesWithoutCommitting() throws Exception {
    when(solrClient.request(any(ContentStreamUpdateRequest.class), eq(COLLECTION)))
        .thenThrow(new SolrServerException("bad row"));

    ArgumentCaptor<ContentStreamUpdateRequest> captor =
        ArgumentCaptor.forClass(ContentStreamUpdateRequest.class);
    assertThrows(
        SolrServerException.class,
        () -> indexingService.indexCsvDocuments(COLLECTION, "id,title\n1,Test\n"));
    verify(solrClient).request(captor.capture(), eq(COLLECTION));
    assertEquals(
        "true",
        captor.getValue().getParams().get("commit"),
        "the rejected request carried the commit, so nothing was committed separately");
    verify(solrClient, never()).commit(anyString());
  }

  @Test
  void indexDocuments_WithRuntimeException_ShouldPropagateWithoutRetrying() throws Exception {
    List<SolrInputDocument> docs = createMockDocuments(2);

    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenThrow(new RuntimeException("Unexpected error"));

    assertThrows(RuntimeException.class, () -> indexingService.indexDocuments(COLLECTION, docs));

    verify(solrClient).request(any(UpdateRequest.class), eq(COLLECTION));
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
  void indexMarkdownDocuments_IndexesEachElementAsItsOwnDocument() throws Exception {
    when(indexingDocumentCreator.createSchemalessDocumentsFromMarkdown("# One"))
        .thenReturn(createMockDocuments(1));
    when(indexingDocumentCreator.createSchemalessDocumentsFromMarkdown("# Two"))
        .thenReturn(createMockDocuments(1));
    when(solrClient.request(any(UpdateRequest.class), eq(COLLECTION)))
        .thenReturn(new NamedList<>());

    IndexingResult result =
        indexingService.indexMarkdownDocuments(COLLECTION, List.of("# One", "# Two"));

    assertEquals(
        new IndexingResult(COLLECTION, Format.markdown, 2, 2, List.of("id", "title"), 2), result);
    verify(indexingDocumentCreator).createSchemalessDocumentsFromMarkdown("# One");
    verify(indexingDocumentCreator).createSchemalessDocumentsFromMarkdown("# Two");
    ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
    verify(solrClient, times(1)).request(captor.capture(), eq(COLLECTION));
    assertEquals(2, captor.getValue().getDocuments().size());
  }

  @Test
  void indexDataPrompt_jsonPath_referencesIndexJsonDocuments() {
    String sample =
        """
				[{"id":"1","title":"Test"}]""";

    String body = promptText(indexingService.indexDataPrompt("library", "json", sample));

    assertTrue(body.contains("library"), "Prompt should mention the target collection name");
    assertTrue(
        body.contains("index-json-documents"),
        "JSON path should reference index-json-documents tool");
    assertTrue(body.contains("get-schema"), "Prompt should reference get-schema for verification");
    assertTrue(
        body.contains("design-schema"),
        "Prompt should reference design-schema as fallback when fields are missing");
    assertTrue(body.contains(sample), "Prompt should embed the sample payload");
  }

  @Test
  void indexDataPrompt_csvPath_referencesIndexCsvDocuments() {
    String body = promptText(indexingService.indexDataPrompt("library", "csv", null));

    assertTrue(
        body.contains("index-csv-documents"), "CSV path should reference index-csv-documents tool");
    assertFalse(
        body.contains("index-json-documents"),
        "CSV path should not reference index-json-documents tool");
  }

  @Test
  void indexDataPrompt_xmlPath_referencesIndexXmlDocuments() {
    String body = promptText(indexingService.indexDataPrompt("library", "xml", null));

    assertTrue(
        body.contains("index-xml-documents"), "XML path should reference index-xml-documents tool");
  }

  @Test
  void indexDataPrompt_markdownPath_referencesIndexMarkdownDocuments() {
    String body = promptText(indexingService.indexDataPrompt("library", "markdown", null));

    assertTrue(
        body.contains("index-markdown-documents"),
        "Markdown path should reference index-markdown-documents tool");
  }

  @Test
  void indexDataPrompt_mdAliasResolvesToMarkdownTool() {
    String body = promptText(indexingService.indexDataPrompt("library", "md", null));

    assertTrue(
        body.contains("index-markdown-documents"),
        "'md' alias should reference index-markdown-documents tool");
  }

  @Test
  void indexDataPrompt_unknownFormat_throwsIllegalArgumentException() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> indexingService.indexDataPrompt("library", "yaml", null));
    assertTrue(
        ex.getMessage().contains("json/csv/xml"),
        "Exception message should list the supported formats: " + ex.getMessage());
  }
}
