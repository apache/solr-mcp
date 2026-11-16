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
package org.apache.solr.mcp.server.schema;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A collection's schema as the Schema API reports it, returned by the {@code get-schema} tool and
 * the {@code solr://{collection}/schema} resource.
 *
 * <p>Each record types the properties clients read and keeps every other property Solr sends in
 * {@code other}: {@link JsonAnySetter} collects them when Solr's response is converted, and {@link
 * JsonAnyGetter} writes them back at the same level. The JSON a client receives is therefore the
 * Schema API's own, key for key, and a property a later Solr version adds is passed on rather than
 * dropped.
 *
 * @param name the schema name
 * @param version the schema version
 * @param uniqueKey the uniqueKey field
 * @param fieldTypes the field types
 * @param fields the declared fields
 * @param dynamicFields the dynamic-field patterns
 * @param copyFields the copy-field rules
 * @param other every other top-level property, such as {@code similarity}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionSchema(
    @Nullable String name,
    @Nullable Float version,
    @Nullable String uniqueKey,
    List<FieldType> fieldTypes,
    List<Field> fields,
    List<Field> dynamicFields,
    List<CopyField> copyFields,
    @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

  /**
   * A declared field, or a dynamic-field pattern such as {@code *_s}.
   *
   * @param name the field name, or the pattern for a dynamic field
   * @param type the field type name
   * @param indexed whether the field is indexed, if set on the field
   * @param stored whether the field is stored, if set on the field
   * @param docValues whether the field has docValues, if set on the field
   * @param multiValued whether the field is multi-valued, if set on the field
   * @param required whether the field is required, if set on the field
   * @param other every other property, such as {@code useDocValuesAsStored}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Field(
      String name,
      String type,
      @Nullable Boolean indexed,
      @Nullable Boolean stored,
      @Nullable Boolean docValues,
      @Nullable Boolean multiValued,
      @Nullable Boolean required,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {}

  /**
   * A field type, with its analyzers for a text type.
   *
   * @param name the type name
   * @param className the implementing class, sent by Solr as {@code class}
   * @param analyzer the analyzer used for both indexing and querying
   * @param indexAnalyzer the index-time analyzer
   * @param queryAnalyzer the query-time analyzer
   * @param multiTermAnalyzer the analyzer for wildcard, prefix and range terms
   * @param other every other property, such as {@code positionIncrementGap} or {@code
   *     vectorDimension}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FieldType(
      String name,
      @JsonProperty("class") String className,
      @Nullable Analyzer analyzer,
      @Nullable Analyzer indexAnalyzer,
      @Nullable Analyzer queryAnalyzer,
      @Nullable Analyzer multiTermAnalyzer,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {}

  /**
   * An analyzer chain. Factory entries keep Solr's open-ended properties as maps.
   *
   * @param charFilters the char filters, in order
   * @param tokenizer the tokenizer
   * @param filters the token filters, in order
   * @param other every other property, such as an analyzer {@code class}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Analyzer(
      @Nullable List<Map<String, Object>> charFilters,
      @Nullable Map<String, Object> tokenizer,
      @Nullable List<Map<String, Object>> filters,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {}

  /**
   * A copy-field rule.
   *
   * @param source the source field or pattern
   * @param dest the destination field or pattern
   * @param maxChars the number of characters copied, if limited
   * @param other every other property
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CopyField(
      String source,
      String dest,
      @Nullable Integer maxChars,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {}
}
