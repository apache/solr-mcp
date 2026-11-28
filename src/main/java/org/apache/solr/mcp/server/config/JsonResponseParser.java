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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.solr.client.solrj.response.ResponseParser;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SolrJ {@link ResponseParser} that requests JSON wire format ({@code wt=json}) and converts the
 * Solr JSON response into the {@link NamedList} object tree that SolrJ's typed response classes
 * ({@link org.apache.solr.client.solrj.response.QueryResponse}, {@link
 * org.apache.solr.client.solrj.response.LukeResponse}, etc.) expect internally.
 *
 * <p>JSON carries less type information than JavaBin or XML, so the conversion relies on two things
 * it can check rather than guess:
 *
 * <ul>
 *   <li>A request that reads a plain {@link NamedList}, which Solr writes as a flat {@code
 *       ["term",5,...]} array by default, sends {@code json.nl=map} so it arrives as a JSON object
 *       instead. The search facet counts are the one such case; {@code SearchService} sets it.
 *       Objects become {@link SimpleOrderedMap}, which is a {@link NamedList}, and arrays are
 *       always plain lists.
 *   <li>Solr writes a result list as an object whose {@code numFound} is a number and {@code docs}
 *       an array. An object with that shape becomes a {@link SolrDocumentList}. Both types are
 *       checked, so an object that merely has keys of those names, such as Luke's {@code fields}
 *       for a collection with fields called {@code numFound} and {@code docs}, stays a {@link
 *       NamedList}.
 * </ul>
 *
 * <p>Numbers: integers become {@link Integer} or {@link Long} by size. Decimals in document field
 * values become {@link Double}, so a stored {@code pdouble} keeps its precision. Decimals elsewhere
 * become {@link Float}, matching the JavaBin codec, because SolrJ response classes cast to it
 * ({@code SchemaResponse} reads the schema version as a {@code Float}).
 */
class JsonResponseParser extends ResponseParser {

  private final JsonMapper mapper;

  JsonResponseParser(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String getWriterType() {
    return "json";
  }

  @Override
  public Collection<String> getContentTypes() {
    // Some Solr endpoints (notably /admin/ping) return JSON-encoded bodies with
    // Content-Type: text/plain. Accept it so SolrJ does not reject otherwise-valid
    // responses.
    return List.of(MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE);
  }

  @Override
  public NamedList<@Nullable Object> processResponse(InputStream body, String encoding) {
    try {
      return toNamedList(mapper.readTree(body));
    } catch (JacksonException e) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR, "Failed to parse Solr JSON response", e);
    }
  }

  private SimpleOrderedMap<@Nullable Object> toNamedList(JsonNode objectNode) {
    SimpleOrderedMap<@Nullable Object> result = new SimpleOrderedMap<>();
    objectNode
        .properties()
        .forEach(entry -> result.add(entry.getKey(), convertValue(entry.getValue())));
    return result;
  }

  private @Nullable Object convertValue(JsonNode node) {
    if (node.isNull()) return null;
    if (node.isBoolean()) return node.booleanValue();
    if (node.isTextual()) return node.textValue();
    if (node.isInt()) return node.intValue();
    if (node.isLong()) return node.longValue();
    if (node.isDouble() || node.isFloat()) return node.floatValue();
    if (node.isObject()) return isResultList(node) ? toSolrDocumentList(node) : toNamedList(node);
    if (node.isArray()) {
      List<@Nullable Object> list = new ArrayList<>(node.size());
      node.forEach(element -> list.add(convertValue(element)));
      return list;
    }
    return node.asText();
  }

  /**
   * A field value inside a document: as {@link #convertValue}, but decimals keep full precision.
   */
  private @Nullable Object convertFieldValue(JsonNode node) {
    return node.isFloatingPointNumber() ? node.doubleValue() : convertValue(node);
  }

  /** Solr's result list: {@code {"numFound": <number>, ..., "docs": [ ... ]}}. */
  private static boolean isResultList(JsonNode node) {
    return node.path("numFound").isNumber() && node.path("docs").isArray();
  }

  private SolrDocumentList toSolrDocumentList(JsonNode node) {
    SolrDocumentList list = new SolrDocumentList();
    list.setNumFound(node.get("numFound").longValue());
    list.setStart(node.path("start").longValue());
    JsonNode numFoundExact = node.get("numFoundExact");
    if (numFoundExact != null && !numFoundExact.isNull()) {
      list.setNumFoundExact(numFoundExact.booleanValue());
    }
    JsonNode maxScore = node.get("maxScore");
    if (maxScore != null && !maxScore.isNull()) {
      list.setMaxScore(maxScore.floatValue());
    }
    node.get("docs").forEach(doc -> list.add(toSolrDocument(doc)));
    return list;
  }

  private SolrDocument toSolrDocument(JsonNode node) {
    SolrDocument doc = new SolrDocument();
    node.properties()
        .forEach(
            entry -> {
              JsonNode val = entry.getValue();
              if (val.isArray()) {
                // A multi-valued field stays a list, even with one value.
                List<@Nullable Object> values = new ArrayList<>(val.size());
                val.forEach(element -> values.add(convertFieldValue(element)));
                doc.setField(entry.getKey(), values);
              } else {
                doc.setField(entry.getKey(), convertFieldValue(val));
              }
            });
    return doc;
  }
}
