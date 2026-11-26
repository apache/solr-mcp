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
package org.apache.solr.mcp.server.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The result of the {@code search} tool: how many documents matched, where this page starts, the
 * hits on this page, and the facet counts.
 *
 * <p>The shape belongs to this server, not to Solr. Only the keys of each hit's {@link
 * SearchHit#fields() fields} come from the collection, because they are the collection's own field
 * names.
 *
 * <pre>{@code
 * {
 *   "numFound": 42,
 *   "start": 10,
 *   "maxScore": 2.34,
 *   "documents": [
 *     {"id": "book001", "score": 2.34, "fields": {"name": ["A Game of Thrones"], "year": 1996}}
 *   ],
 *   "facets": [
 *     {"field": "genre", "values": [{"value": "drama", "count": 12}, {"value": "comedy", "count": 7}]}
 *   ]
 * }
 * }</pre>
 *
 * @param numFound the total number of matching documents, across all pages
 * @param start the offset of the first hit in {@code documents}
 * @param maxScore the highest score among the matches; absent when Solr computed no scores
 * @param documents the hits on this page, in Solr's ranking order
 * @param facets one entry per faceted field, in the order Solr returned them; empty when the search
 *     asked for no facets
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResponse(
    long numFound,
    long start,
    @Nullable Float maxScore,
    List<SearchHit> documents,
    List<FacetField> facets) {

  /** Copies the lists into unmodifiable lists, so no hit or facet can be added or removed. */
  public SearchResponse {
    documents = List.copyOf(documents);
    facets = List.copyOf(facets);
  }

  /**
   * One matching document.
   *
   * @param id the value of the collection's uniqueKey field, as a string
   * @param score the document's relevance score; absent when Solr returned none
   * @param fields the document's other returned fields by name, in Solr's order, without the
   *     uniqueKey field and without {@code score}. Values keep their JSON types: numbers stay
   *     numbers and multi-valued fields are arrays. The map is unmodifiable; its values are not
   *     deep-copied, so a multi-valued field's list is the one Solr's response holds
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SearchHit(String id, @Nullable Float score, Map<String, Object> fields) {

    /**
     * Copies {@code fields} into an unmodifiable map that keeps its order. {@code Map.copyOf} would
     * lose Solr's field order. The values themselves are not copied.
     */
    public SearchHit {
      fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
  }

  /**
   * The facet counts for one field.
   *
   * @param field the faceted field
   * @param values the field's values with their counts, in the order Solr returned them (highest
   *     count first)
   */
  public record FacetField(String field, List<FacetValue> values) {

    /** Copies {@code values} into an unmodifiable list. */
    public FacetField {
      values = List.copyOf(values);
    }
  }

  /**
   * One value of a faceted field and the number of matching documents that have it.
   *
   * @param value the field value
   * @param count the number of matching documents with that value
   */
  public record FacetValue(String value, long count) {}
}
