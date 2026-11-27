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
package org.apache.solr.mcp.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Verifies that {@link SolrNativeHints} registers the reflection and resource hints the native
 * image depends on.
 *
 * <p>The hints are otherwise only exercised by {@code nativeTest -Pnative} (a full GraalVM build),
 * so an accidentally removed registration would surface as a hard-to-diagnose native-only startup
 * failure. This test pins the registrations on the plain JVM path where a regression fails in
 * seconds.
 */
class SolrNativeHintsTest {

  private final RuntimeHints hints = new RuntimeHints();

  @BeforeEach
  void registerHints() {
    new SolrNativeHints().registerHints(hints, getClass().getClassLoader());
  }

  @Test
  void registersDefaultMetaProviderConstructorHint() {
    // Spring AI MCP instantiates DefaultMetaProvider reflectively in
    // MetaUtils.getMeta(); without this hint every Spring context refresh
    // fails in native image with "Required no-arg constructor not found".
    // Spring AI 2.x moved the class out of the springaicommunity package and
    // into Spring AI core, so this pins the current coordinate — the legacy
    // org.springaicommunity.mcp.context name is still registered defensively
    // in the Registrar, but registerTypeIfPresent no-ops when, as here, the
    // class is absent from the classpath.
    assertTrue(
        RuntimeHintsPredicates.reflection()
            .onType(
                TypeReference.of(
                    "org.springframework.ai.mcp.annotation.context.DefaultMetaProvider"))
            .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
            .test(hints));
  }

  @Test
  void registersSolrjResponseTypeHints() {
    assertTrue(
        RuntimeHintsPredicates.reflection()
            .onType(QueryResponse.class)
            .withMemberCategories(
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS)
            .test(hints));
  }

  @Test
  void registersMcpResponseRecordHints() {
    // Jackson reads each record's components through its accessors (methods).
    for (String recordName :
        List.of(
            "org.apache.solr.mcp.server.search.SearchResponse",
            "org.apache.solr.mcp.server.search.SearchResponse$SearchHit",
            "org.apache.solr.mcp.server.search.SearchResponse$FacetField",
            "org.apache.solr.mcp.server.search.SearchResponse$FacetValue")) {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(recordName))
              .withMemberCategories(
                  MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                  MemberCategory.INVOKE_DECLARED_METHODS)
              .test(hints),
          recordName);
    }
  }

  /**
   * The hints are registered with {@code registerTypeIfPresent}, which skips a name that does not
   * load, so a renamed or deleted record would silently lose its hint in the native image.
   */
  @Test
  void everyMcpToolRecordNameLoads() {
    for (String className : SolrNativeHints.MCP_TOOL_RECORDS) {
      assertDoesNotThrow(
          () -> Class.forName(className, false, getClass().getClassLoader()),
          () -> className + " is listed in MCP_TOOL_RECORDS but does not exist");
    }
  }

  @Test
  void registersAliasAndCollectionRecordHints() {
    for (String className :
        List.of(
            "org.apache.solr.mcp.server.alias.Alias",
            "org.apache.solr.mcp.server.collection.Collection",
            "org.apache.solr.mcp.server.collection.HealthStatus",
            "org.apache.solr.mcp.server.collection.CollectionMetrics$CacheInfo")) {
      assertTrue(
          RuntimeHintsPredicates.reflection().onType(TypeReference.of(className)).test(hints),
          () -> "missing reflection hint for " + className);
    }
  }

  @Test
  void registersClusterRecordHints() {
    for (String type :
        List.of(
            "org.apache.solr.mcp.server.cluster.ClusterStatus",
            "org.apache.solr.mcp.server.cluster.ClusterStatus$Collection",
            "org.apache.solr.mcp.server.cluster.ClusterStatus$Shard",
            "org.apache.solr.mcp.server.cluster.ClusterStatus$Replica",
            "org.apache.solr.mcp.server.cluster.ConfigSet")) {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(type))
              .withMemberCategories(
                  MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                  MemberCategory.INVOKE_DECLARED_METHODS)
              .test(hints),
          type);
    }
  }

  @Test
  void registersIndexingResultHints() {
    for (String type :
        List.of(
            "org.apache.solr.mcp.server.indexing.IndexingResult",
            "org.apache.solr.mcp.server.indexing.IndexingResult$Format")) {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(type))
              .withMemberCategories(
                  MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                  MemberCategory.INVOKE_DECLARED_METHODS)
              .test(hints),
          type);
    }
  }

  @Test
  void registersCollectionSchemaRecordHints() {
    // Jackson builds the input records for add-fields and add-field-types (constructors) and
    // writes every record out through its accessors (methods), so both are needed.
    for (String record :
        List.of(
            "org.apache.solr.mcp.server.schema.CollectionSchema",
            "org.apache.solr.mcp.server.schema.CollectionSchema$Field",
            "org.apache.solr.mcp.server.schema.CollectionSchema$FieldType",
            "org.apache.solr.mcp.server.schema.CollectionSchema$Analyzer",
            "org.apache.solr.mcp.server.schema.CollectionSchema$AnalyzerComponent",
            "org.apache.solr.mcp.server.schema.CollectionSchema$CopyField",
            "org.apache.solr.mcp.server.schema.FieldProperty",
            "org.apache.solr.mcp.server.schema.IndexedField")) {
      assertTrue(
          RuntimeHintsPredicates.reflection()
              .onType(TypeReference.of(record))
              .withMemberCategories(
                  MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                  MemberCategory.INVOKE_DECLARED_METHODS)
              .test(hints),
          record);
    }
  }

  @Test
  void registersLogbackSpringXmlResourceHint() {
    // The configuration Spring Boot loads by convention; it carries the
    // per-profile appenders and must survive AOT.
    assertTrue(RuntimeHintsPredicates.resource().forResource("logback-spring.xml").test(hints));
  }
}
