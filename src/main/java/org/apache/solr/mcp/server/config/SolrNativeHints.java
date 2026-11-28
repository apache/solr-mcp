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

import java.util.List;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM native image reachability hints for SolrJ types this project actually uses.
 *
 * <p>The client reads JSON responses through {@link JsonResponseParser} on {@code
 * HttpJdkSolrClient}. It builds every response from {@code NamedList} and {@code SimpleOrderedMap},
 * which the hints below cover along with the typed response containers.
 *
 * <p>{@link SolrConfig} imports this registrar unconditionally — on the JVM path it is a no-op
 * because no runtime reflection happens against these types at startup. It is only meaningful when
 * running as a native image.
 *
 * @see SolrConfig
 */
class SolrNativeHints implements RuntimeHintsRegistrar {

  /**
   * Record types used by {@code @McpTool} methods as responses or parameters. Jackson needs
   * reflection access to serialize responses and to bind request parameters in native image.
   * Registered by name because some are package-private.
   */
  static final List<String> MCP_TOOL_RECORDS =
      List.of(
          "org.apache.solr.mcp.server.alias.Alias",
          "org.apache.solr.mcp.server.alias.AliasDeletion",
          "org.apache.solr.mcp.server.collection.Collection",
          "org.apache.solr.mcp.server.collection.CollectionCreationResult",
          "org.apache.solr.mcp.server.collection.HealthStatus",
          "org.apache.solr.mcp.server.collection.HealthStatus$Reason",
          "org.apache.solr.mcp.server.collection.CollectionMetrics",
          "org.apache.solr.mcp.server.collection.CollectionMetrics$IndexStats",
          "org.apache.solr.mcp.server.collection.CollectionMetrics$QueryStats",
          "org.apache.solr.mcp.server.collection.CollectionMetrics$CacheStats",
          "org.apache.solr.mcp.server.collection.CollectionMetrics$CacheInfo",
          "org.apache.solr.mcp.server.collection.CollectionMetrics$HandlerStats",
          "org.apache.solr.mcp.server.collection.CollectionMetrics$HandlerInfo",
          "org.apache.solr.mcp.server.search.SearchResponse",
          "org.apache.solr.mcp.server.search.SortClause",
          "org.apache.solr.mcp.server.schema.SchemaUpdateResult",
          "org.apache.solr.mcp.server.schema.CollectionSchema",
          "org.apache.solr.mcp.server.schema.CollectionSchema$Field",
          "org.apache.solr.mcp.server.schema.CollectionSchema$FieldType",
          "org.apache.solr.mcp.server.schema.CollectionSchema$Analyzer",
          "org.apache.solr.mcp.server.schema.CollectionSchema$AnalyzerComponent",
          "org.apache.solr.mcp.server.schema.CollectionSchema$CopyField",
          "org.apache.solr.mcp.server.schema.FieldProperty",
          "org.apache.solr.mcp.server.schema.IndexedField");

  @Override
  public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
    MemberCategory[] categories = {
      MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
      MemberCategory.INVOKE_DECLARED_METHODS,
      MemberCategory.ACCESS_DECLARED_FIELDS
    };

    // SolrJ response types
    hints.reflection().registerType(QueryResponse.class, categories);
    hints.reflection().registerType(UpdateResponse.class, categories);
    hints.reflection().registerType(NamedList.class, categories);
    hints.reflection().registerType(SimpleOrderedMap.class, categories);
    hints.reflection().registerType(SolrDocument.class, categories);
    hints.reflection().registerType(SolrDocumentList.class, categories);

    // SolrJ input/indexing types
    hints.reflection().registerType(SolrInputDocument.class, categories);
    hints.reflection().registerType(SolrInputField.class, categories);

    // SolrJ facet types
    hints.reflection().registerType(FacetField.class, categories);
    hints.reflection().registerType(FacetField.Count.class, categories);

    // SolrJ / Solr API model types used by CoreAdminResponse.getCoreStatus()
    // which deserializes via Jackson reflection.
    for (String solrApiType :
        List.of(
            "org.apache.solr.client.api.model.SolrJerseyResponse",
            "org.apache.solr.client.api.model.SolrJerseyResponse$ResponseHeader",
            "org.apache.solr.client.api.model.ErrorInfo",
            "org.apache.solr.client.api.model.CoreStatusResponse",
            "org.apache.solr.client.api.model.CoreStatusResponse$SingleCoreData",
            "org.apache.solr.client.api.model.CoreStatusResponse$CloudDetails",
            "org.apache.solr.client.api.model.CoreStatusResponse$IndexDetails")) {
      hints.reflection().registerTypeIfPresent(classLoader, solrApiType, categories);
    }

    // Spring AI MCP annotation internals — MetaUtils reflectively
    // instantiates DefaultMetaProvider via its no-arg constructor.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "org.springframework.ai.mcp.annotation.context.DefaultMetaProvider",
            categories);

    // SolrJ schema request types. No current path reaches them reflectively:
    // add-field-types builds both with setters, and SolrJ reads them back through
    // getters into NamedLists before Utils.writeJson. They are kept so that a
    // definition serialized or logged reflectively still works in native image.
    hints
        .reflection()
        .registerType(
            org.apache.solr.client.solrj.request.schema.AnalyzerDefinition.class, categories);
    hints
        .reflection()
        .registerType(
            org.apache.solr.client.solrj.request.schema.FieldTypeDefinition.class, categories);

    // MCP tool request/response records (some package-private, registered by
    // name); request records are deserialized reflectively by Jackson when
    // tool arguments are bound
    for (String className : MCP_TOOL_RECORDS) {
      hints.reflection().registerTypeIfPresent(classLoader, className, categories);
    }

    // SolrJ EnvUtils loads these properties files in its static
    // initializer; without them getResourceAsStream returns null
    // and the <clinit> throws NullPointerException.
    hints.resources().registerPattern("EnvToSyspropMappings.properties");
    hints.resources().registerPattern("DeprecatedSystemPropertyMappings.properties");

    // Older springaicommunity location of DefaultMetaProvider (Spring AI 1.x
    // transitive). Spring AI 2.x relocated this to
    // org.springframework.ai.mcp.annotation.context.DefaultMetaProvider,
    // which is registered above. Kept defensively via registerTypeIfPresent
    // so this is a no-op when the older class isn't on the classpath.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "org.springaicommunity.mcp.context.DefaultMetaProvider",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

    // logback-spring.xml is the only logback configuration; Spring Boot
    // resolves it by convention. Under AOT the parsed model is replayed
    // from META-INF/spring/logback-model, so this hint is belt-and-braces
    // for the non-AOT path. Logback's own pre-Spring status output is
    // silenced by Main, not by a second configuration file; see
    // LoggingConfigurationTest.
    hints.resources().registerPattern("logback-spring.xml");
  }
}
