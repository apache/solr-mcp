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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.solr.common.luke.FieldFlag;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * The fields that hold data in a collection, as Solr's Luke handler reports them, returned by the
 * {@code list-fields} tool and the {@code solr://{collection}/fields} resource.
 *
 * @param fields each field in the index by name, in Luke's order
 */
public record FieldListing(Map<String, IndexedField> fields) {

  /**
   * One field in the index.
   *
   * <p>Luke's {@code schema} flag letters ({@code "I-SD------OF-----l"}) are reported as {@code
   * flags}, SolrJ's {@link FieldFlag}, the same enum {@code get-schema} uses. Every other property
   * Luke sends is kept in {@code other} and written back at the same level, so a property a later
   * Solr version adds is passed on rather than dropped.
   *
   * @param type the field type name
   * @param flags the field's flags, decoded from Luke's {@code schema} letters
   * @param dynamicBase the dynamic-field pattern this field is an instance of, such as {@code *_s}
   * @param docs the number of documents with terms in the field, deleted documents included; Luke
   *     cannot count a field without terms (point and docValues-only fields), so it is absent, or 0
   *     on Solr 9.11+ and 10.1+, for those
   * @param other every other property Luke sends, and the {@code schema} letters themselves if one
   *     is not a flag SolrJ knows
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record IndexedField(
      @Nullable String type,
      @JsonSerialize(contentUsing = FieldFlags.NameSerializer.class) Set<FieldFlag> flags,
      @Nullable String dynamicBase,
      @Nullable Long docs,
      @JsonAnySetter @JsonAnyGetter Map<String, Object> other) {

    /**
     * Decodes the {@code schema} letters Jackson collected into {@code other} into {@code flags}.
     */
    public IndexedField {
      if (flags == null || flags.isEmpty()) {
        Map<String, Object> rest = new LinkedHashMap<>(other);
        EnumSet<FieldFlag> decoded =
            rest.get(LUKE_FLAGS) instanceof String letters
                ? FieldFlags.fromLukeLetters(letters)
                : null;
        if (decoded != null) {
          rest.remove(LUKE_FLAGS);
        }
        flags = decoded != null ? decoded : EnumSet.noneOf(FieldFlag.class);
        other = rest;
      }
    }
  }

  /** The Luke property holding a field's schema flags as letters. */
  private static final String LUKE_FLAGS = "schema";
}
