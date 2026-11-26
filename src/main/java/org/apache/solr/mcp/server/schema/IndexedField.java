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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A field that holds data in a collection, as {@code list-fields} and the {@code
 * solr://{collection}/fields} resource report it from Solr's Luke handler.
 *
 * @param name the field name, such as {@code genre_s}
 * @param type the field type name
 * @param flags the properties in effect for the field, decoded from Luke's flag letters
 * @param dynamicBase the dynamic-field pattern the field comes from, such as {@code *_s}
 * @param docs the number of documents with terms in the field, when Luke reports one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexedField(
    String name,
    @Nullable String type,
    Set<FieldProperty> flags,
    @Nullable String dynamicBase,
    @Nullable Long docs) {

  /** Copies the flags, in declaration order, so the record cannot change after it is built. */
  public IndexedField {
    EnumSet<FieldProperty> copy = EnumSet.noneOf(FieldProperty.class);
    copy.addAll(flags);
    flags = Collections.unmodifiableSet(copy);
  }

  /**
   * Builds the listing from the {@code fields} section of a Luke response, ordered by field name.
   *
   * @param fields the section: field name to Luke's per-field map
   * @return the fields, ordered by name
   */
  static List<IndexedField> fromLuke(Map<String, ?> fields) {
    List<IndexedField> listed = new ArrayList<>();
    fields.forEach(
        (name, raw) -> {
          if (raw instanceof Map<?, ?> field) {
            listed.add(fromLuke(name, field));
          }
        });
    listed.sort(Comparator.comparing(IndexedField::name));
    return List.copyOf(listed);
  }

  /** One field from Luke's per-field map. */
  private static IndexedField fromLuke(String name, Map<?, ?> field) {
    return new IndexedField(
        name,
        field.get("type") instanceof String type ? type : null,
        field.get("schema") instanceof String letters
            ? FieldProperty.fromLukeLetters(letters)
            : EnumSet.noneOf(FieldProperty.class),
        field.get("dynamicBase") instanceof String base ? base : null,
        field.get("docs") instanceof Number docs ? docs.longValue() : null);
  }
}
