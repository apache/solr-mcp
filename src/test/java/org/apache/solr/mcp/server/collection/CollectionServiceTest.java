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
package org.apache.solr.mcp.server.collection;

import static org.apache.solr.mcp.server.McpResultAssertions.completionValues;
import static org.apache.solr.mcp.server.McpResultAssertions.promptText;
import static org.apache.solr.mcp.server.McpResultAssertions.resourceJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.util.SolrIdentifierValidator;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.mcp.server.collection.CollectionMetrics.CacheInfo;
import org.apache.solr.mcp.server.collection.CollectionMetrics.CacheStats;
import org.apache.solr.mcp.server.collection.CollectionMetrics.HandlerInfo;
import org.apache.solr.mcp.server.collection.CollectionMetrics.HandlerStats;
import org.apache.solr.mcp.server.collection.CollectionMetrics.IndexStats;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.json.JsonContent;
import tools.jackson.databind.json.JsonMapper;

@JsonTest
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class CollectionServiceTest {

  private static final String COLLECTIONS_URI = "solr://collections";

  @Mock private SolrClient solrClient;

  @Mock private QueryResponse queryResponse;

  @Mock private LukeResponse lukeResponse;

  @Mock private SolrPingResponse pingResponse;

  private CollectionService collectionService;

  @Autowired private JsonMapper jsonMapper;

  @BeforeEach
  void setUp() {
    collectionService = new CollectionService(solrClient, jsonMapper);
  }

  // Constructor tests
  @Test
  void constructor_ShouldInitializeWithSolrClient() {
    assertNotNull(collectionService);
  }

  @Test
  void listCollections_WhenExceptionOccurs_ShouldReportSolrUnreachable() throws Exception {
    // Given - mock throws exception
    when(solrClient.request(any(), any()))
        .thenThrow(new SolrServerException("Connection error to http://solr:8983/solr"));

    // When / Then - translated, without SolrJ's text or a cause
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> collectionService.listCollections());
    assertTrue(
        exception.getMessage().startsWith(CollectionService.LIST_COLLECTIONS_FAILED),
        exception::getMessage);
    assertTrue(exception.getMessage().contains("could not be reached"), exception::getMessage);
    assertFalse(exception.getMessage().contains("http://"), exception::getMessage);
    assertNull(exception.getCause());
  }

  // Collection name extraction tests
  @Test
  void extractCollectionName_WithShardName_ShouldExtractCollectionName() {
    // Given
    String shardName = "films_shard1_replica_n1";

    // When
    String result = collectionService.extractCollectionName(shardName);

    // Then
    assertEquals("films", result);
  }

  @Test
  void extractCollectionName_WithMultipleShards_ShouldExtractCorrectly() {
    // Given & When & Then
    assertEquals("products", collectionService.extractCollectionName("products_shard2_replica_n3"));
    assertEquals("users", collectionService.extractCollectionName("users_shard5_replica_n10"));
  }

  @Test
  void extractCollectionName_WithSimpleCollectionName_ShouldReturnUnchanged() {
    // Given
    String simpleName = "simple_collection";

    // When
    String result = collectionService.extractCollectionName(simpleName);

    // Then
    assertEquals("simple_collection", result);
  }

  @Test
  void extractCollectionName_WithEmptyString_ShouldReturnEmptyString() {
    // When
    String result = collectionService.extractCollectionName("");

    // Then
    assertEquals("", result);
  }

  @Test
  void extractCollectionName_WithCollectionNameContainingUnderscore_ShouldOnlyExtractBeforeShard() {
    // Given - collection name itself contains underscore
    String complexName = "my_complex_collection_shard1_replica_n1";

    // When
    String result = collectionService.extractCollectionName(complexName);

    // Then
    assertEquals("my_complex_collection", result);
  }

  @Test
  void extractCollectionName_EdgeCases_ShouldHandleCorrectly() {
    // Test various edge cases
    assertEquals("a", collectionService.extractCollectionName("a_shard1"));
    // "collection_shard" has no shard number, so it is not a SolrCloud core
    // name - it is a collection that merely ends in "_shard" and must survive
    // unchanged. Stripping it would also corrupt names like
    // "orders_shard_archive".
    assertEquals("collection_shard", collectionService.extractCollectionName("collection_shard"));
    assertEquals("test_name", collectionService.extractCollectionName("test_name"));
    assertEquals("", collectionService.extractCollectionName("_shard1"));
  }

  @Test
  void extractCollectionName_WithShardWordInsideName_ShouldNotTruncate() {
    // Regression: a bare "contains(_shard)" check truncated this to "orders".
    assertEquals(
        "orders_shard_archive", collectionService.extractCollectionName("orders_shard_archive"));
    // ...while a genuine core name for that same collection still resolves.
    assertEquals(
        "orders_shard_archive",
        collectionService.extractCollectionName("orders_shard_archive_shard2_replica_n1"));
  }

  @Test
  void extractCollectionName_WithShardInMiddleOfName_ShouldExtractCorrectly() {
    // Given - "shard" appears in collection name but not as suffix pattern
    String name = "resharding_tasks";

    // When
    String result = collectionService.extractCollectionName(name);

    // Then
    assertEquals(
        "resharding_tasks", result, "Should not extract when '_shard' is not followed by number");
  }

  @Test
  void extractCollectionName_WithMultipleOccurrencesOfShard_ShouldStripOnlyTheCoreSuffix() {
    // Given - a collection whose own name ends in "_shard1". SolrCloud names
    // its cores "<collection>_shard<N>_replica_<type><M>", so this core belongs
    // to the collection "data_shard1", not "data".
    String name = "data_shard1_shard2_replica_n1";

    // When
    String result = collectionService.extractCollectionName(name);

    // Then
    assertEquals("data_shard1", result, "Should strip only the trailing shard/replica suffix");
  }

  // Health check tests
  @Test
  void checkHealth_WithHealthyCollection_ShouldReturnHealthyStatus() throws Exception {
    // Given
    SolrDocumentList docList = new SolrDocumentList();
    docList.setNumFound(100);

    when(solrClient.ping("test_collection")).thenReturn(pingResponse);
    when(pingResponse.getElapsedTime()).thenReturn(10L);
    when(solrClient.query(eq("test_collection"), any())).thenReturn(queryResponse);
    when(queryResponse.getResults()).thenReturn(docList);

    // When
    HealthStatus result = collectionService.checkHealth("test_collection");

    // Then
    assertNotNull(result);
    assertTrue(result.healthy());
    assertNull(result.reason());
    assertEquals(10L, result.pingMillis());
    assertEquals(100L, result.numDocs());
    assertEquals("test_collection", result.collection());
  }

  @Test
  void checkHealth_WithUnhealthyCollection_ShouldReturnUnhealthyStatus() throws Exception {
    // Given
    when(solrClient.ping("unhealthy_collection"))
        .thenThrow(new SolrServerException("Connection failed"));

    // When
    HealthStatus result = collectionService.checkHealth("unhealthy_collection");

    // Then
    assertNotNull(result);
    assertFalse(result.healthy());
    assertEquals(HealthStatus.Reason.SOLR_UNREACHABLE, result.reason());
    assertNull(result.pingMillis());
    assertNull(result.numDocs());
    assertEquals("unhealthy_collection", result.collection());
  }

  @Test
  void checkHealth_WhenPingSucceedsButQueryFails_ShouldReturnUnhealthyStatus() throws Exception {
    // Given
    when(solrClient.ping("test_collection")).thenReturn(pingResponse);
    when(solrClient.query(eq("test_collection"), any())).thenThrow(new IOException("Query failed"));

    // When
    HealthStatus result = collectionService.checkHealth("test_collection");

    // Then
    assertNotNull(result);
    assertFalse(result.healthy());
    assertEquals(HealthStatus.Reason.QUERY_FAILED, result.reason());
  }

  @Test
  void checkHealth_WithEmptyCollection_ShouldReturnHealthyWithZeroDocuments() throws Exception {
    // Given
    SolrDocumentList emptyDocList = new SolrDocumentList();
    emptyDocList.setNumFound(0);

    when(solrClient.ping("empty_collection")).thenReturn(pingResponse);
    when(pingResponse.getElapsedTime()).thenReturn(5L);
    when(solrClient.query(eq("empty_collection"), any())).thenReturn(queryResponse);
    when(queryResponse.getResults()).thenReturn(emptyDocList);

    // When
    HealthStatus result = collectionService.checkHealth("empty_collection");

    // Then
    assertNotNull(result);
    assertTrue(result.healthy());
    assertEquals(0L, result.numDocs());
    assertEquals(5, result.pingMillis());
  }

  @Test
  void checkHealth_WithSlowResponse_ShouldCaptureResponseTime() throws Exception {
    // Given
    SolrDocumentList docList = new SolrDocumentList();
    docList.setNumFound(1000);

    when(solrClient.ping("slow_collection")).thenReturn(pingResponse);
    when(pingResponse.getElapsedTime()).thenReturn(5000L); // 5 seconds
    when(solrClient.query(eq("slow_collection"), any())).thenReturn(queryResponse);
    when(queryResponse.getResults()).thenReturn(docList);

    // When
    HealthStatus result = collectionService.checkHealth("slow_collection");

    // Then
    assertNotNull(result);
    assertTrue(result.healthy());
    assertEquals(5000, result.pingMillis());
  }

  @Test
  void checkHealth_UnknownCollection_ReportsCollectionNotFoundWithoutSolrText() throws Exception {
    when(solrClient.ping("missing"))
        .thenThrow(
            new SolrException(
                SolrException.ErrorCode.NOT_FOUND, "Error from server at http://solr:8983/solr"));

    HealthStatus result = collectionService.checkHealth("missing");

    assertEquals(
        HealthStatus.unhealthy("missing", HealthStatus.Reason.COLLECTION_NOT_FOUND), result);
    assertFalse(jsonMapper.writeValueAsString(result).contains("http://"));
  }

  @Test
  void checkHealth_PingRejectedBySolr_ReportsPingFailed() throws Exception {
    when(solrClient.ping("broken"))
        .thenThrow(new SolrException(SolrException.ErrorCode.SERVER_ERROR, "core is loading"));

    assertEquals(HealthStatus.Reason.PING_FAILED, collectionService.checkHealth("broken").reason());
  }

  @Test
  void checkHealth_IOException() throws Exception {
    when(solrClient.ping("error_collection")).thenThrow(new IOException("Network error"));

    HealthStatus result = collectionService.checkHealth("error_collection");

    assertFalse(result.healthy());
    assertEquals(HealthStatus.Reason.SOLR_UNREACHABLE, result.reason());
    assertNull(result.pingMillis());
  }

  // Index stats tests
  @Test
  void buildIndexStats_ShouldExtractStats() {
    NamedList<Object> indexInfo = new NamedList<>();
    indexInfo.add("segmentCount", 5);
    when(lukeResponse.getIndexInfo()).thenReturn(indexInfo);
    when(lukeResponse.getNumDocs()).thenReturn(1000);

    IndexStats result = collectionService.buildIndexStats(lukeResponse);

    assertEquals(1000, result.numDocs());
    assertEquals(5, result.segmentCount());
  }

  @Test
  void buildIndexStats_WithNullSegmentCount() {
    NamedList<Object> indexInfo = new NamedList<>();
    when(lukeResponse.getIndexInfo()).thenReturn(indexInfo);
    when(lukeResponse.getNumDocs()).thenReturn(1000);

    IndexStats result = collectionService.buildIndexStats(lukeResponse);

    assertEquals(1000, result.numDocs());
    assertNull(result.segmentCount());
  }

  // Collection stats tests
  @Test
  void getCollectionStats_NotFound() throws Exception {
    RemoteSolrException notFound =
        new RemoteSolrException(
            "http://solr:8983/solr/non_existent", 404, "Expected mime type ...", null);
    when(solrClient.request(any(LukeRequest.class), eq("non_existent"))).thenThrow(notFound);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> collectionService.getCollectionStats("non_existent"));

    assertEquals(
        CollectionService.COLLECTION_STATS_FAILED
            + ": "
            + SolrCalls.collectionNotFound("non_existent"),
        exception.getMessage());
    assertNull(exception.getCause(), "Spring AI would append the cause's URL-bearing message");
    verify(solrClient, never()).request(any(CollectionAdminRequest.List.class), any());
  }

  @Test
  void getCollectionStats_OtherSolrException_CarriesSolrsReason() throws Exception {
    RemoteSolrException serverError =
        new RemoteSolrException(
            "http://solr:8983/solr/test_collection", 500, Map.of("msg", "boom"));
    when(solrClient.request(any(LukeRequest.class), eq("test_collection"))).thenThrow(serverError);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> collectionService.getCollectionStats("test_collection"));

    assertTrue(exception.getMessage().contains("boom"), exception::getMessage);
    assertFalse(exception.getMessage().contains("http://"), exception::getMessage);
    assertNull(exception.getCause());
  }

  @Test
  void getCollectionStats_MakesExactlyOneMetricsRequestAndNoQuery() throws Exception {
    // Luke response: only the "index" section is needed for buildIndexStats.
    NamedList<Object> lukeIndexSection = new NamedList<>();
    lukeIndexSection.add("numDocs", 42);
    lukeIndexSection.add("segmentCount", 3);
    NamedList<Object> lukeRaw = new NamedList<>();
    lukeRaw.add("index", lukeIndexSection);
    when(solrClient.request(any(LukeRequest.class), eq("test_collection"))).thenReturn(lukeRaw);

    NamedList<Object> metricsResponse =
        wrapInMetricsResponse(createCacheCoreMetrics(), "test_collection");
    when(solrClient.request(argThat(request -> request instanceof GenericSolrRequest)))
        .thenReturn(metricsResponse);

    CollectionMetrics result = collectionService.getCollectionStats("test_collection");

    assertNotNull(result);
    assertEquals(42, result.index().numDocs());
    assertEquals(3, result.index().segmentCount());
    assertNotNull(result.caches());
    // the core the cache and handler stats were read from is named
    assertTrue(result.metricsCore().startsWith("solr.core.test_collection."), result.metricsCore());

    // No query: the handler and cache counters are cumulative, so a probe query would show up in
    // this and every later call's numbers.
    verify(solrClient, never()).query(any(String.class), any(SolrParams.class));
    // Exactly one metrics call (combined cache+handler prefixes), no
    // list-collections pre-flight, and exactly one Luke request.
    verify(solrClient, never()).request(any(CollectionAdminRequest.List.class), any());
    verify(solrClient, times(1)).request(argThat(request -> request instanceof GenericSolrRequest));
    ArgumentCaptor<LukeRequest> luke = ArgumentCaptor.forClass(LukeRequest.class);
    verify(solrClient).request(luke.capture(), eq("test_collection"));
    assertEquals(
        "index",
        luke.getValue().getParams().get("show"),
        "only the index section, not every field");
  }

  // Cache metrics tests
  @Test
  void getCacheMetrics_WithNonExistentCollection_ShouldReturnNull() throws Exception {
    // The metrics response has no core registry for this collection.
    when(solrClient.request(any(SolrRequest.class))).thenReturn(emptyMetricsResponse());

    CacheStats result = collectionService.getCacheMetrics("nonexistent");

    assertNull(result);
  }

  @Test
  void getCacheMetrics_Success() throws Exception {
    NamedList<Object> mbeans = createMockCacheData();
    when(solrClient.request(any(SolrRequest.class))).thenReturn(mbeans);

    CacheStats result = collectionService.getCacheMetrics("test_collection");

    assertNotNull(result);
    assertNotNull(result.queryResultCache());
    assertEquals(100L, result.queryResultCache().lookups());
  }

  @Test
  void getCacheMetrics_CollectionNotFound() throws Exception {
    when(solrClient.request(any(SolrRequest.class))).thenReturn(emptyMetricsResponse());

    CacheStats result = collectionService.getCacheMetrics("non_existent");

    assertNull(result);
  }

  @Test
  void getCacheMetrics_SolrServerException() throws Exception {
    when(solrClient.request(any(SolrRequest.class))).thenThrow(new SolrServerException("Error"));

    CacheStats result = collectionService.getCacheMetrics("test_collection");

    assertNull(result);
  }

  @Test
  void getCacheMetrics_IOException() throws Exception {
    when(solrClient.request(any(SolrRequest.class))).thenThrow(new IOException("IO Error"));

    CacheStats result = collectionService.getCacheMetrics("test_collection");

    assertNull(result);
  }

  @Test
  void getCacheMetrics_EmptyStats() throws Exception {
    // Metrics response with core metrics that contain no cache keys
    NamedList<Object> response = wrapInMetricsResponse(new NamedList<>());
    when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

    CacheStats result = collectionService.getCacheMetrics("test_collection");

    assertNull(result);
  }

  @Test
  void getCacheMetrics_WithShardName() throws Exception {
    NamedList<Object> response = wrapInMetricsResponse(createCacheCoreMetrics(), "films");
    when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

    CacheStats result = collectionService.getCacheMetrics("films_shard1_replica_n1");

    assertNotNull(result);
  }

  @Test
  void extractCacheStats() throws Exception {
    NamedList<Object> coreMetrics = createCacheCoreMetrics();
    Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
    method.setAccessible(true);

    CacheStats result = (CacheStats) method.invoke(collectionService, coreMetrics);

    assertNotNull(result.queryResultCache());
    assertEquals(100L, result.queryResultCache().lookups());
    assertEquals(80L, result.queryResultCache().hits());
  }

  @Test
  void extractCacheStats_AllCacheTypes() throws Exception {
    NamedList<Object> coreMetrics = createCompleteCacheCoreMetrics();
    Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
    method.setAccessible(true);

    CacheStats result = (CacheStats) method.invoke(collectionService, coreMetrics);

    assertNotNull(result.queryResultCache());
    assertNotNull(result.documentCache());
    assertNotNull(result.filterCache());
  }

  @Test
  void extractCacheStats_NoCacheKeys() throws Exception {
    // Core metrics with no cache keys at all
    NamedList<Object> coreMetrics = new NamedList<>();

    Method method = CollectionService.class.getDeclaredMethod("extractCacheStats", NamedList.class);
    method.setAccessible(true);

    CacheStats result = (CacheStats) method.invoke(collectionService, coreMetrics);

    assertNotNull(result);
    assertNull(result.queryResultCache());
    assertNull(result.documentCache());
    assertNull(result.filterCache());
  }

  @Test
  void isCacheStatsEmpty() throws Exception {
    Method method =
        CollectionService.class.getDeclaredMethod("isCacheStatsEmpty", CacheStats.class);
    method.setAccessible(true);

    CacheStats emptyStats = new CacheStats(null, null, null);
    assertTrue((boolean) method.invoke(collectionService, emptyStats));
    assertTrue((boolean) method.invoke(collectionService, (CacheStats) null));

    CacheStats nonEmptyStats =
        new CacheStats(new CacheInfo(100L, null, null, null, null, null), null, null);
    assertFalse((boolean) method.invoke(collectionService, nonEmptyStats));
  }

  // Handler metrics tests
  @Test
  void getHandlerMetrics_WithNonExistentCollection_ShouldReturnNull() throws Exception {
    // The metrics response has no core registry for this collection.
    when(solrClient.request(any(SolrRequest.class))).thenReturn(emptyMetricsResponse());

    HandlerStats result = collectionService.getHandlerMetrics("nonexistent");

    assertNull(result);
  }

  @Test
  void getHandlerMetrics_Success() throws Exception {
    // select handler data only, so the update handler is null
    when(solrClient.request(any(SolrRequest.class))).thenReturn(createMockSelectHandlerData());

    HandlerStats result = collectionService.getHandlerMetrics("test_collection");

    assertNotNull(result);
    assertNotNull(result.selectHandler());
    assertEquals(500L, result.selectHandler().requests());
  }

  @Test
  void getHandlerMetrics_CollectionNotFound() throws Exception {
    when(solrClient.request(any(SolrRequest.class))).thenReturn(emptyMetricsResponse());

    HandlerStats result = collectionService.getHandlerMetrics("non_existent");

    assertNull(result);
  }

  @Test
  void getHandlerMetrics_SolrServerException() throws Exception {
    when(solrClient.request(any(SolrRequest.class))).thenThrow(new SolrServerException("Error"));

    HandlerStats result = collectionService.getHandlerMetrics("test_collection");

    assertNull(result);
  }

  @Test
  void getHandlerMetrics_IOException() throws Exception {
    when(solrClient.request(any(SolrRequest.class))).thenThrow(new IOException("IO Error"));

    HandlerStats result = collectionService.getHandlerMetrics("test_collection");

    assertNull(result);
  }

  @Test
  void getHandlerMetrics_EmptyStats() throws Exception {
    // Metrics response with core metrics that contain no handler keys
    NamedList<Object> response = wrapInMetricsResponse(new NamedList<>());
    when(solrClient.request(any(SolrRequest.class))).thenReturn(response);

    HandlerStats result = collectionService.getHandlerMetrics("test_collection");

    assertNull(result);
  }

  @Test
  void getHandlerMetrics_WithShardName() throws Exception {
    when(solrClient.request(any(SolrRequest.class)))
        .thenReturn(createMockSelectHandlerData("films"));

    HandlerStats result = collectionService.getHandlerMetrics("films_shard1_replica_n1");

    assertNotNull(result);
  }

  @Test
  void extractFlatHandlerInfo_SelectHandler() throws Exception {
    NamedList<Object> coreMetrics = createSelectHandlerCoreMetrics();
    Method method =
        CollectionService.class.getDeclaredMethod(
            "extractFlatHandlerInfo", NamedList.class, String.class);
    method.setAccessible(true);

    HandlerInfo result =
        (HandlerInfo) method.invoke(collectionService, coreMetrics, "QUERY./select.");

    assertNotNull(result);
    assertEquals(500L, result.requests());
    assertEquals(5L, result.errors());
    assertEquals(2L, result.timeouts());
    assertEquals(10000L, result.totalTime());
    // avgTimePerRequest computed: 10000/500 = 20.0
    assertEquals(20.0f, result.avgTimePerRequest());
  }

  @Test
  void extractFlatHandlerInfo_UpdateHandler() throws Exception {
    NamedList<Object> coreMetrics = createUpdateHandlerCoreMetrics();
    Method method =
        CollectionService.class.getDeclaredMethod(
            "extractFlatHandlerInfo", NamedList.class, String.class);
    method.setAccessible(true);

    HandlerInfo result =
        (HandlerInfo) method.invoke(collectionService, coreMetrics, "UPDATE./update.");

    assertNotNull(result);
    assertEquals(250L, result.requests());
    assertEquals(2L, result.errors());
  }

  @Test
  void extractFlatHandlerInfo_NoHandlerKeys() throws Exception {
    NamedList<Object> coreMetrics = new NamedList<>();
    Method method =
        CollectionService.class.getDeclaredMethod(
            "extractFlatHandlerInfo", NamedList.class, String.class);
    method.setAccessible(true);

    HandlerInfo result =
        (HandlerInfo) method.invoke(collectionService, coreMetrics, "QUERY./select.");

    assertNull(result);
  }

  @Test
  void isHandlerStatsEmpty() throws Exception {
    Method method =
        CollectionService.class.getDeclaredMethod("isHandlerStatsEmpty", HandlerStats.class);
    method.setAccessible(true);

    HandlerStats emptyStats = new HandlerStats(null, null);
    assertTrue((boolean) method.invoke(collectionService, emptyStats));

    HandlerStats nonEmptyStats =
        new HandlerStats(new HandlerInfo(100L, null, null, null, null), null);
    assertFalse((boolean) method.invoke(collectionService, nonEmptyStats));
  }

  // List collections tests
  @Test
  void listCollections_Success() throws Exception {
    NamedList<Object> response = new NamedList<>();
    response.add("collections", Arrays.asList("collection1", "collection2"));

    when(solrClient.request(any(), any())).thenReturn(response);

    List<String> result =
        collectionService.listCollections().stream().map(Collection::name).toList();

    assertNotNull(result);
    assertEquals(2, result.size());
    assertTrue(result.contains("collection1"));
    assertTrue(result.contains("collection2"));
  }

  @Test
  void listCollections_NullCollections() throws Exception {
    NamedList<@Nullable Object> response = new NamedList<>();
    response.add("collections", null);

    when(solrClient.request(any(), any())).thenReturn(response);

    List<String> result =
        collectionService.listCollections().stream().map(Collection::name).toList();

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void listCollections_SolrServerException_IsTranslated() throws Exception {
    when(solrClient.request(any(), any())).thenThrow(new SolrServerException("Connection error"));

    assertThrows(IllegalStateException.class, () -> collectionService.listCollections());
  }

  @Test
  void listCollections_IOException_IsTranslated() throws Exception {
    when(solrClient.request(any(), any())).thenThrow(new IOException("IO error"));

    assertThrows(IllegalStateException.class, () -> collectionService.listCollections());
  }

  @Test
  void getCollectionsResource_Success() throws Exception {
    NamedList<Object> response = new NamedList<>();
    response.add("collections", Arrays.asList("collection1", "collection2"));
    when(solrClient.request(any(), any())).thenReturn(response);

    assertEquals(
        "[{\"name\":\"collection1\"},{\"name\":\"collection2\"}]",
        resourceJson(
            collectionService.getCollectionsResource(new ReadResourceRequest(COLLECTIONS_URI)),
            COLLECTIONS_URI));
  }

  @Test
  void getCollectionsResource_SolrFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(
            new SolrServerException(
                "Server refused connection at: http://solr-internal.corp:8983/solr"));

    ReadResourceRequest request = new ReadResourceRequest(COLLECTIONS_URI);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> collectionService.getCollectionsResource(request));

    assertEquals(CollectionService.COLLECTIONS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
  }

  // Helper methods — mock the Solr Metrics API response format:
  // response -> "metrics" -> "solr.core.<name>" -> "CACHE.searcher.xxx" /
  // "HANDLER./xxx"

  // Core metrics builders (unwrapped — used by reflection tests for extract*
  // methods)
  private NamedList<Object> createCacheCoreMetrics() {
    NamedList<Object> coreMetrics = new NamedList<>();

    NamedList<Object> queryResultCache = new NamedList<>();
    queryResultCache.add("lookups", 100L);
    queryResultCache.add("hits", 80L);
    queryResultCache.add("hitratio", 0.8f);
    queryResultCache.add("inserts", 20L);
    queryResultCache.add("evictions", 5L);
    queryResultCache.add("size", 100L);
    coreMetrics.add("CACHE.searcher.queryResultCache", queryResultCache);

    return coreMetrics;
  }

  private NamedList<Object> createCompleteCacheCoreMetrics() {
    NamedList<Object> coreMetrics = createCacheCoreMetrics();

    NamedList<Object> documentCache = new NamedList<>();
    documentCache.add("lookups", 200L);
    documentCache.add("hits", 150L);
    documentCache.add("hitratio", 0.75f);
    documentCache.add("inserts", 50L);
    documentCache.add("evictions", 10L);
    documentCache.add("size", 180L);
    coreMetrics.add("CACHE.searcher.documentCache", documentCache);

    NamedList<Object> filterCache = new NamedList<>();
    filterCache.add("lookups", 150L);
    filterCache.add("hits", 120L);
    filterCache.add("hitratio", 0.8f);
    filterCache.add("inserts", 30L);
    filterCache.add("evictions", 8L);
    filterCache.add("size", 140L);
    coreMetrics.add("CACHE.searcher.filterCache", filterCache);

    return coreMetrics;
  }

  // Handler metrics use flat keys in the Metrics API (e.g.
  // QUERY./select.requests)
  private NamedList<Object> createSelectHandlerCoreMetrics() {
    NamedList<Object> coreMetrics = new NamedList<>();
    coreMetrics.add("QUERY./select.requests", 500L);
    coreMetrics.add("QUERY./select.errors", 5L);
    coreMetrics.add("QUERY./select.timeouts", 2L);
    coreMetrics.add("QUERY./select.totalTime", 10000L);
    return coreMetrics;
  }

  private NamedList<Object> createUpdateHandlerCoreMetrics() {
    NamedList<Object> coreMetrics = new NamedList<>();
    coreMetrics.add("UPDATE./update.requests", 250L);
    coreMetrics.add("UPDATE./update.errors", 2L);
    coreMetrics.add("UPDATE./update.timeouts", 1L);
    coreMetrics.add("UPDATE./update.totalTime", 5000L);
    return coreMetrics;
  }

  // Wrapped response builders (used by tests that go through
  // getCacheMetrics/getHandlerMetrics)
  private NamedList<Object> createMockCacheData() {
    return wrapInMetricsResponse(createCacheCoreMetrics());
  }

  private NamedList<Object> createMockSelectHandlerData() {
    return wrapInMetricsResponse(createSelectHandlerCoreMetrics());
  }

  private NamedList<Object> createMockSelectHandlerData(String collection) {
    return wrapInMetricsResponse(createSelectHandlerCoreMetrics(), collection);
  }

  private NamedList<Object> wrapInMetricsResponse(NamedList<Object> coreMetrics) {
    return wrapInMetricsResponse(coreMetrics, "test_collection");
  }

  private NamedList<Object> wrapInMetricsResponse(
      NamedList<Object> coreMetrics, String collection) {
    NamedList<Object> metrics = new NamedList<>();
    metrics.add("solr.core." + collection + ".shard1.replica_n1", coreMetrics);
    NamedList<Object> response = new NamedList<>();
    response.add("metrics", metrics);
    return response;
  }

  /**
   * A Metrics API response with no core registries at all — models an unknown collection, which has
   * no matching {@code solr.core.<name>.} entry.
   */
  private NamedList<Object> emptyMetricsResponse() {
    NamedList<Object> response = new NamedList<>();
    response.add("metrics", new NamedList<>());
    return response;
  }

  @Test
  void getCollectionStats_WithoutMetricsApi_LeavesCachesAndHandlersOutOfTheJson() throws Exception {
    // Solr 10 removed /admin/mbeans; the metrics request fails and the two sections go missing.
    NamedList<Object> lukeIndexSection = new NamedList<>();
    lukeIndexSection.add("numDocs", 42);
    NamedList<Object> lukeRaw = new NamedList<>();
    lukeRaw.add("index", lukeIndexSection);
    when(solrClient.request(any(LukeRequest.class), eq("test_collection"))).thenReturn(lukeRaw);
    when(solrClient.request(argThat(request -> request instanceof GenericSolrRequest)))
        .thenThrow(new SolrException(SolrException.ErrorCode.NOT_FOUND, "no /admin/mbeans"));

    CollectionMetrics result = collectionService.getCollectionStats("test_collection");

    assertNull(result.caches());
    assertNull(result.handlers());
    assertThat(new JsonContent(jsonMapper.writeValueAsString(result)))
        .doesNotHavePath("$.caches")
        .doesNotHavePath("$.handlers")
        .doesNotHavePath("$.metricsCore");
  }

  @Test
  void healthStatus_RejectsStatesAHealthCheckCannotProduce() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HealthStatus("books", true, 3L, 10L, HealthStatus.Reason.PING_FAILED));
    assertThrows(
        IllegalArgumentException.class, () -> new HealthStatus("books", false, 3L, null, null));
  }

  @Test
  void createCollection_RejectedBySolr_CarriesSolrsReason() throws Exception {
    when(solrClient.request(any(), isNull()))
        .thenThrow(
            new RemoteSolrException(
                "http://solr:8983/solr/admin/collections",
                400,
                Map.of("msg", "collection already exists: books", "code", 400)));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> collectionService.createCollection("books", null, null, null));
    assertTrue(
        exception.getMessage().endsWith("collection already exists: books"), exception::getMessage);
    assertFalse(exception.getMessage().contains("http://"), exception::getMessage);
  }

  // createCollection tests
  @Test
  void createCollection_success() throws Exception {
    when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

    CollectionCreationResult result =
        collectionService.createCollection("new_collection", "_default", 1, 1);

    assertEquals(
        new CollectionCreationResult("new_collection", "new_collection.AUTOCREATED", 1, 1), result);
  }

  @Test
  void createCollection_defaultsApplied() throws Exception {
    when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

    CollectionCreationResult result =
        collectionService.createCollection("defaults_collection", null, null, null);

    assertEquals(
        new CollectionCreationResult(
            "defaults_collection", "defaults_collection.AUTOCREATED", 1, 1),
        result);
  }

  @Test
  void createCollection_noConfigSet_letsSolrMakePrivateCopy() throws Exception {
    SolrRequest<?> request = captureCreateRequest(null);

    assertNull(
        request.getParams().get(CollectionAdminParams.COLL_CONF),
        "No configset should be named, so Solr copies _default");
  }

  @Test
  void createCollection_noConfigSet_reportsAutocreatedConfigSet() throws Exception {
    when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

    CollectionCreationResult result = collectionService.createCollection("films", null, null, null);

    assertEquals("films.AUTOCREATED", result.configSet());
  }

  @Test
  void createCollection_defaultConfigSet_isTreatedAsOmitted() throws Exception {
    when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

    CollectionCreationResult result =
        collectionService.createCollection("films", "_default", null, null);

    assertEquals("films.AUTOCREATED", result.configSet());
    assertNull(captureCreateRequest("_default").getParams().get(CollectionAdminParams.COLL_CONF));
  }

  @Test
  void createCollection_blankConfigSet_isTreatedAsOmitted() throws Exception {
    assertNull(captureCreateRequest("  ").getParams().get(CollectionAdminParams.COLL_CONF));
  }

  @Test
  void createCollection_namedConfigSet_isSharedByReference() throws Exception {
    when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

    CollectionCreationResult result =
        collectionService.createCollection("films", "films_conf", null, null);

    assertEquals("films_conf", result.configSet());
    assertEquals(
        "films_conf",
        captureCreateRequest("films_conf").getParams().get(CollectionAdminParams.COLL_CONF));
  }

  /** Creates collection {@code films} and returns the request sent to Solr. */
  private SolrRequest<?> captureCreateRequest(@Nullable String configSet) throws Exception {
    clearInvocations(solrClient);
    when(solrClient.request(any(), isNull())).thenReturn(new NamedList<>());

    collectionService.createCollection("films", configSet, null, null);

    ArgumentCaptor<SolrRequest<?>> captor = ArgumentCaptor.captor();
    verify(solrClient).request(captor.capture(), isNull());
    return captor.getValue();
  }

  @Test
  void createCollection_blankName_throwsIllegalArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () -> collectionService.createCollection("   ", null, null, null));
  }

  @Test
  void createCollection_emptyName_throwsIllegalArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () -> collectionService.createCollection("", null, null, null));
  }

  @Test
  void createCollection_solrServerException_isTranslated() throws Exception {
    when(solrClient.request(any(), isNull())).thenThrow(new SolrServerException("Solr error"));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> collectionService.createCollection("fail_core", null, null, null));
    assertTrue(
        exception.getMessage().startsWith(CollectionService.CREATE_COLLECTION_FAILED),
        exception::getMessage);
  }

  @Test
  void exploreCollectionsPrompt_isReadOnlyAndReferencesKeyTools() {
    String body = promptText(collectionService.exploreCollectionsPrompt());

    assertNotNull(body);
    assertTrue(body.contains("list-collections"), "Prompt should reference list-collections tool");
    assertTrue(
        body.contains("get-collection-stats"), "Prompt should reference get-collection-stats tool");
    assertTrue(body.contains("check-health"), "Prompt should reference check-health tool");
    assertFalse(
        body.contains("create-collection"),
        "Explore prompt is read-only; should not direct the LLM to create-collection");
    assertTrue(
        body.contains("setup-collection"),
        "Explore prompt should cross-reference setup-collection for follow-up");
  }

  @Test
  void setupCollectionPrompt_includesNameAndInterpolatedDefaults() {
    String body =
        promptText(collectionService.setupCollectionPrompt("widgets", "Catalog of widgets"));

    assertNotNull(body);
    assertTrue(body.contains("widgets"), "Prompt should embed the chosen collection name");
    assertTrue(
        body.contains("Catalog of widgets"), "Prompt should embed the purpose when provided");
    assertTrue(
        body.contains("create-collection"), "Setup prompt should reference create-collection tool");
    assertTrue(body.contains("_default"), "Setup prompt should mention the default configset");
    assertTrue(
        body.contains("design-schema"),
        "Setup prompt should cross-reference design-schema for follow-up");
  }

  @Test
  void setupCollectionPrompt_omitsPurposeLineWhenBlank() {
    String body = promptText(collectionService.setupCollectionPrompt("widgets", null));

    assertFalse(
        body.contains("Purpose:"), "Purpose line should be omitted when no purpose is provided");
  }

  @Test
  void setupCollectionPrompt_statesTheNameRuleSolrEnforces() {
    String body = promptText(collectionService.setupCollectionPrompt("widgets", null));

    // Solr accepts mixed case and periods, so the prompt must not tell the model to reject them.
    assertEquals("Widgets.v2", SolrIdentifierValidator.validateCollectionName("Widgets.v2"));
    assertThrows(
        SolrException.class, () -> SolrIdentifierValidator.validateCollectionName("-widgets"));
    assertFalse(body.contains("uppercase"), body);
    assertTrue(body.contains("periods"), body);
    assertTrue(body.contains("must not start with a hyphen"), body);
  }

  // completeCollection tests
  @Test
  void completeCollection_WithMatchingPrefix_ReturnsMatches() throws Exception {
    stubCollections(Arrays.asList("products", "prod-logs", "users"));

    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("collection", "prod")));

    assertEquals(List.of("prod-logs", "products"), result);
  }

  @Test
  void completeCollection_WithEmptyPrefix_ReturnsAllSorted() throws Exception {
    stubCollections(Arrays.asList("zeta", "alpha", "mu"));

    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("collection", "")));

    assertEquals(List.of("alpha", "mu", "zeta"), result);
  }

  @Test
  @SuppressWarnings("DataFlowIssue") // passes null on purpose to pin the SDK contract
  void completeCollection_NullValueRejectedAtSdkBoundary() {
    // Since MCP Java SDK 2.0, CompleteArgument's constructor rejects a null
    // value (1.x and earlier accept it), so CollectionService#completeCollection
    // does not null-check it. Pin the SDK contract here.
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompleteRequest.CompleteArgument("collection", null));
  }

  @Test
  void completeCollection_IsCaseInsensitive() throws Exception {
    stubCollections(Arrays.asList("Products", "PROD_LOGS", "users"));

    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("collection", "prod")));

    assertEquals(List.of("PROD_LOGS", "Products"), result);
  }

  @Test
  void completeCollection_WithNoMatches_ReturnsEmpty() throws Exception {
    stubCollections(Arrays.asList("alpha", "beta"));

    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("collection", "zzz")));

    assertTrue(result.isEmpty());
  }

  @Test
  void completeCollection_WithWrongArgumentName_ReturnsEmpty() {
    // Should not even attempt to list collections when the argument name does not
    // match the template variable.
    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("field", "prod")));

    assertTrue(result.isEmpty());
    verifyNoInteractions(solrClient);
  }

  @ParameterizedTest(name = "{0} matches")
  @ValueSource(ints = {0, 99, 100, 150})
  void completeCollection_ReportsTotalAndWhetherMoreMatched(int matching) throws Exception {
    List<String> collections = new ArrayList<>();
    IntStream.range(0, matching)
        .mapToObj(index -> String.format("c%04d", index))
        .forEach(collections::add);
    collections.add("other");
    stubCollections(collections);

    CompleteResult result =
        collectionService.completeCollection(
            new CompleteRequest.CompleteArgument("collection", "c"));

    int max = CollectionService.MAX_COMPLETION_RESULTS;
    assertEquals(Math.min(matching, max), result.completion().values().size());
    assertEquals(matching, result.completion().total());
    assertEquals(matching > max, result.completion().hasMore());
  }

  @Test
  void completeCollection_CapsResultsAtMax() throws Exception {
    List<String> many =
        IntStream.range(0, CollectionService.MAX_COMPLETION_RESULTS + 25)
            .mapToObj(index -> String.format("c%04d", index))
            .toList();
    stubCollections(many);

    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("collection", "c")));

    assertEquals(CollectionService.MAX_COMPLETION_RESULTS, result.size());
    assertEquals("c0000", result.get(0));
  }

  @Test
  void completeCollection_WhenListCollectionsFails_ReturnsEmpty() throws Exception {
    when(solrClient.request(any(), any())).thenThrow(new SolrServerException("connection refused"));

    List<String> result =
        completionValues(
            collectionService.completeCollection(
                new CompleteRequest.CompleteArgument("collection", "prod")));

    assertTrue(result.isEmpty());
  }

  /**
   * Answers the Collections API {@code LIST} request with these names. The completions read the
   * collections through the same Solr call as {@code list-collections}, with failures logged at
   * DEBUG, so they are stubbed at the client rather than on the tool method.
   */
  private void stubCollections(List<String> names) throws Exception {
    NamedList<Object> response = new NamedList<>();
    response.add("collections", names);
    when(solrClient.request(any(CollectionAdminRequest.List.class), any())).thenReturn(response);
  }
}
