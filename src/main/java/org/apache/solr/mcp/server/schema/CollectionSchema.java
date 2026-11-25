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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A collection's schema, returned by the {@code get-schema} tool and the {@code
 * solr://{collection}/schema} resource.
 *
 * <p>Every record holds only the properties the contract names. An attribute Solr sends that the
 * contract does not name is not passed on, with one exception: a field type's or analyzer
 * component's class-specific settings, such as {@code positionIncrementGap}, go into its {@code
 * parameters}, because which settings exist depends on the Solr class the user chose.
 *
 * <p>{@link Field} and {@link FieldType} report each schema property the schema declares as a
 * {@code Boolean}, and leave out the ones it does not, so a declared {@code false} is kept. The
 * same {@link Field} and {@link FieldType} objects are what {@code add-fields} and {@code
 * add-field-types} accept.
 *
 * @param name the schema's name
 * @param version the schema version
 * @param uniqueKey the name of the unique-key field
 * @param fieldTypes the field types
 * @param fields the declared fields
 * @param dynamicFields the dynamic-field patterns
 * @param copyFields the copy-field rules
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionSchema(
    @Nullable String name,
    @Nullable Float version,
    @Nullable String uniqueKey,
    List<FieldType> fieldTypes,
    List<Field> fields,
    List<Field> dynamicFields,
    List<CopyField> copyFields) {

  /** Reads Solr's JSON, dropping the attributes the contract does not name. */
  private static final JsonMapper SOLR_JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** Copies the lists so the record cannot change after it is built. */
  public CollectionSchema {
    fieldTypes = List.copyOf(fieldTypes);
    fields = List.copyOf(fields);
    dynamicFields = List.copyOf(dynamicFields);
    copyFields = List.copyOf(copyFields);
  }

  /**
   * Builds the schema from the {@code schema} section of a Schema API response.
   *
   * @param schema the section, as SolrJ parses Solr's JSON into maps and lists
   * @return the schema
   */
  static CollectionSchema fromSolr(Map<String, Object> schema) {
    return new CollectionSchema(
        string(schema, "name"),
        schema.get("version") instanceof Number version ? version.floatValue() : null,
        string(schema, "uniqueKey"),
        maps(schema.get("fieldTypes")).stream().map(FieldType::fromSolr).toList(),
        maps(schema.get("fields")).stream().map(Field::fromSolr).toList(),
        maps(schema.get("dynamicFields")).stream().map(Field::fromSolr).toList(),
        maps(schema.get("copyFields")).stream()
            .map(copyField -> SOLR_JSON.convertValue(copyField, CopyField.class))
            .toList());
  }

  /**
   * A declared field or dynamic-field pattern. A schema property is present only when the schema
   * declares it; an absent one inherits from the field type.
   *
   * @param name the field name, or the pattern for a dynamic field
   * @param type the field type name
   * @param defaultValue the default value, when set
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Field(
      String name,
      String type,
      @JsonProperty("default") @Nullable String defaultValue,
      @Nullable Boolean indexed,
      @Nullable Boolean stored,
      @Nullable Boolean docValues,
      @Nullable Boolean multiValued,
      @Nullable Boolean required,
      @Nullable Boolean uninvertible,
      @Nullable Boolean omitNorms,
      @Nullable Boolean omitTermFreqAndPositions,
      @Nullable Boolean omitPositions,
      @Nullable Boolean termVectors,
      @Nullable Boolean termPositions,
      @Nullable Boolean termOffsets,
      @Nullable Boolean termPayloads,
      @Nullable Boolean sortMissingFirst,
      @Nullable Boolean sortMissingLast,
      @Nullable Boolean useDocValuesAsStored,
      @Nullable Boolean large) {

    static Field fromSolr(Map<String, Object> field) {
      return SOLR_JSON.convertValue(field, Field.class);
    }

    /** The field as Schema API {@code add-field} attributes. */
    Map<String, Object> toSolr() {
      return SOLR_JSON.convertValue(this, ATTRIBUTES);
    }
  }

  /**
   * A field type.
   *
   * @param name the field type name
   * @param className the Solr class implementing it, such as {@code solr.TextField}
   * @param analyzer the analyzer used for both indexing and querying, when one is declared
   * @param indexAnalyzer the analyzer used when indexing, when declared separately
   * @param queryAnalyzer the analyzer used when querying, when declared separately
   * @param multiTermAnalyzer the analyzer used for multi-term queries, when declared
   * @param parameters the class-specific settings, such as {@code positionIncrementGap}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FieldType(
      String name,
      String className,
      @Nullable Boolean indexed,
      @Nullable Boolean stored,
      @Nullable Boolean docValues,
      @Nullable Boolean multiValued,
      @Nullable Boolean required,
      @Nullable Boolean uninvertible,
      @Nullable Boolean omitNorms,
      @Nullable Boolean omitTermFreqAndPositions,
      @Nullable Boolean omitPositions,
      @Nullable Boolean termVectors,
      @Nullable Boolean termPositions,
      @Nullable Boolean termOffsets,
      @Nullable Boolean termPayloads,
      @Nullable Boolean sortMissingFirst,
      @Nullable Boolean sortMissingLast,
      @Nullable Boolean useDocValuesAsStored,
      @Nullable Boolean large,
      @Nullable Analyzer analyzer,
      @Nullable Analyzer indexAnalyzer,
      @Nullable Analyzer queryAnalyzer,
      @Nullable Analyzer multiTermAnalyzer,
      @Nullable Map<String, String> parameters) {

    /** The attributes that are not class-specific {@code parameters}. */
    private static final Set<String> NAMED =
        Set.of(
            "name",
            "class",
            "indexed",
            "stored",
            "docValues",
            "multiValued",
            "required",
            "uninvertible",
            "omitNorms",
            "omitTermFreqAndPositions",
            "omitPositions",
            "termVectors",
            "termPositions",
            "termOffsets",
            "termPayloads",
            "sortMissingFirst",
            "sortMissingLast",
            "useDocValuesAsStored",
            "large",
            "analyzer",
            "indexAnalyzer",
            "queryAnalyzer",
            "multiTermAnalyzer");

    /**
     * Copies the parameters, ordered by name, so the record cannot change after it is built. A
     * client may leave them out; that means none.
     */
    public FieldType {
      parameters = sorted(parameters);
    }

    static FieldType fromSolr(Map<String, Object> type) {
      return new FieldType(
          String.valueOf(type.get("name")),
          String.valueOf(type.get("class")),
          bool(type, "indexed"),
          bool(type, "stored"),
          bool(type, "docValues"),
          bool(type, "multiValued"),
          bool(type, "required"),
          bool(type, "uninvertible"),
          bool(type, "omitNorms"),
          bool(type, "omitTermFreqAndPositions"),
          bool(type, "omitPositions"),
          bool(type, "termVectors"),
          bool(type, "termPositions"),
          bool(type, "termOffsets"),
          bool(type, "termPayloads"),
          bool(type, "sortMissingFirst"),
          bool(type, "sortMissingLast"),
          bool(type, "useDocValuesAsStored"),
          bool(type, "large"),
          Analyzer.fromSolr(type.get("analyzer")),
          Analyzer.fromSolr(type.get("indexAnalyzer")),
          Analyzer.fromSolr(type.get("queryAnalyzer")),
          Analyzer.fromSolr(type.get("multiTermAnalyzer")),
          scalarParameters(type, NAMED));
    }

    /** The field type's own attributes, without its analyzers, as the Schema API names them. */
    Map<String, Object> attributesToSolr() {
      Map<String, Object> attributes = new LinkedHashMap<>(sorted(parameters));
      Map<String, Object> declared = SOLR_JSON.convertValue(this, ATTRIBUTES);
      declared.remove("className");
      declared.remove("parameters");
      declared.remove("analyzer");
      declared.remove("indexAnalyzer");
      declared.remove("queryAnalyzer");
      declared.remove("multiTermAnalyzer");
      attributes.putAll(declared);
      attributes.put("class", className);
      return attributes;
    }
  }

  /**
   * An analyzer: an optional char-filter chain, a tokenizer and a filter chain, or a single Lucene
   * analyzer class.
   *
   * @param className the analyzer class, when the analyzer is one class rather than a chain
   * @param charFilters the char filters, in order
   * @param tokenizer the tokenizer, when the analyzer is a chain
   * @param filters the token filters, in order
   * @param parameters other analyzer settings, such as {@code luceneMatchVersion}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Analyzer(
      @Nullable String className,
      @Nullable List<AnalyzerComponent> charFilters,
      @Nullable AnalyzerComponent tokenizer,
      @Nullable List<AnalyzerComponent> filters,
      @Nullable Map<String, String> parameters) {

    private static final Set<String> NAMED = Set.of("class", "charFilters", "tokenizer", "filters");

    /**
     * Copies the collections so the record cannot change after it is built. A client may leave any
     * of them out; that means none.
     */
    public Analyzer {
      charFilters = charFilters == null ? List.of() : List.copyOf(charFilters);
      filters = filters == null ? List.of() : List.copyOf(filters);
      parameters = sorted(parameters);
    }

    static @Nullable Analyzer fromSolr(@Nullable Object analyzer) {
      if (!(analyzer instanceof Map<?, ?> raw)) {
        return null;
      }
      Map<String, Object> map = cast(raw);
      return new Analyzer(
          string(map, "class"),
          maps(map.get("charFilters")).stream().map(AnalyzerComponent::fromSolr).toList(),
          map.get("tokenizer") instanceof Map<?, ?> tokenizer
              ? AnalyzerComponent.fromSolr(cast(tokenizer))
              : null,
          maps(map.get("filters")).stream().map(AnalyzerComponent::fromSolr).toList(),
          scalarParameters(map, NAMED));
    }

    /** This analyzer's own attributes, as the Schema API names them. */
    Map<String, Object> attributesToSolr() {
      Map<String, Object> attributes = new LinkedHashMap<>(sorted(parameters));
      if (className != null) {
        attributes.put("class", className);
      }
      return attributes;
    }

    /** The char filters; empty when there are none. */
    List<AnalyzerComponent> charFilterList() {
      return charFilters == null ? List.of() : charFilters;
    }

    /** The token filters; empty when there are none. */
    List<AnalyzerComponent> filterList() {
      return filters == null ? List.of() : filters;
    }
  }

  /**
   * A tokenizer, char filter or token filter, named by Solr's short name or by its class.
   *
   * @param name the short name, such as {@code standard} or {@code lowercase}
   * @param className the factory class, such as {@code solr.StandardTokenizerFactory}
   * @param parameters the component's settings, such as {@code ignoreCase}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AnalyzerComponent(
      @Nullable String name, @Nullable String className, @Nullable Map<String, String> parameters) {

    private static final Set<String> NAMED = Set.of("name", "class");

    /**
     * Copies the parameters, ordered by name, so the record cannot change after it is built. A
     * client may leave them out; that means none.
     */
    public AnalyzerComponent {
      parameters = sorted(parameters);
    }

    static AnalyzerComponent fromSolr(Map<String, Object> component) {
      return new AnalyzerComponent(
          string(component, "name"),
          string(component, "class"),
          scalarParameters(component, NAMED));
    }

    /** The component as the Schema API names its attributes. */
    Map<String, Object> toSolr() {
      Map<String, Object> attributes = new LinkedHashMap<>();
      if (name != null) {
        attributes.put("name", name);
      }
      if (className != null) {
        attributes.put("class", className);
      }
      attributes.putAll(sorted(parameters));
      return attributes;
    }
  }

  /**
   * A copy-field rule.
   *
   * @param source the source field or pattern
   * @param dest the destination field
   * @param maxChars the most characters copied, when limited
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CopyField(String source, String dest, @Nullable Integer maxChars) {}

  private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

  private static @Nullable String string(Map<String, Object> map, String key) {
    return map.get(key) instanceof String value ? value : null;
  }

  private static @Nullable Boolean bool(Map<String, Object> map, String key) {
    return map.get(key) instanceof Boolean value ? value : null;
  }

  /**
   * The scalar attributes of {@code map} not in {@code named}, as strings. Nested values, such as a
   * similarity definition, are not part of the contract and are left out.
   */
  private static Map<String, String> scalarParameters(Map<String, Object> map, Set<String> named) {
    Map<String, String> parameters = new TreeMap<>();
    map.forEach(
        (key, value) -> {
          if (!named.contains(key)
              && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
            parameters.put(key, String.valueOf(value));
          }
        });
    return parameters;
  }

  private static Map<String, String> sorted(@Nullable Map<String, String> parameters) {
    return parameters == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(parameters));
  }

  private static List<Map<String, Object>> maps(@Nullable Object list) {
    List<Map<String, Object>> maps = new ArrayList<>();
    if (list instanceof List<?> items) {
      for (Object item : items) {
        if (item instanceof Map<?, ?> map) {
          maps.add(cast(map));
        }
      }
    }
    return maps;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }
}
