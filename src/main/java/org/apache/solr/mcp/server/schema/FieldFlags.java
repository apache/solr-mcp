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

import java.util.EnumSet;
import java.util.Map;
import org.apache.solr.common.luke.FieldFlag;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Field and field-type flags expressed as SolrJ's {@link FieldFlag}, the enum Luke's flag letters
 * decode to, so that {@code get-schema} and {@code list-fields} describe a field's flags the same
 * way.
 */
public final class FieldFlags {

  /**
   * Schema API boolean properties and the {@link FieldFlag} each one sets. The Schema API names
   * them as {@code SchemaField} properties; {@code FieldFlag} has no counterpart for {@code
   * required}, {@code useDocValuesAsStored} or {@code large}, which stay ordinary properties.
   */
  static final Map<String, FieldFlag> SCHEMA_PROPERTIES =
      Map.ofEntries(
          Map.entry("indexed", FieldFlag.INDEXED),
          Map.entry("stored", FieldFlag.STORED),
          Map.entry("docValues", FieldFlag.DOC_VALUES),
          Map.entry("uninvertible", FieldFlag.UNINVERTIBLE),
          Map.entry("multiValued", FieldFlag.MULTI_VALUED),
          Map.entry("termVectors", FieldFlag.TERM_VECTOR_STORED),
          Map.entry("termOffsets", FieldFlag.TERM_VECTOR_OFFSET),
          Map.entry("termPositions", FieldFlag.TERM_VECTOR_POSITION),
          Map.entry("termPayloads", FieldFlag.TERM_VECTOR_PAYLOADS),
          Map.entry("omitNorms", FieldFlag.OMIT_NORMS),
          Map.entry("omitTermFreqAndPositions", FieldFlag.OMIT_TF),
          Map.entry("omitPositions", FieldFlag.OMIT_POSITIONS),
          Map.entry("storeOffsetsWithPositions", FieldFlag.STORE_OFFSETS_WITH_POSITIONS),
          Map.entry("sortMissingFirst", FieldFlag.SORT_MISSING_FIRST),
          Map.entry("sortMissingLast", FieldFlag.SORT_MISSING_LAST));

  private FieldFlags() {}

  /**
   * Removes the flag properties from a field's or field type's properties and returns the flags
   * that were set to {@code true}. A property set to {@code false} is removed and not reported, so
   * the result does not tell an explicit {@code false} from a property inherited from the type.
   *
   * @param properties the remaining Schema API properties; flag properties are removed from it
   * @return the flags set to {@code true}
   */
  static EnumSet<FieldFlag> fromSchemaProperties(Map<String, Object> properties) {
    EnumSet<FieldFlag> flags = EnumSet.noneOf(FieldFlag.class);
    for (Map.Entry<String, FieldFlag> property : SCHEMA_PROPERTIES.entrySet()) {
      if (Boolean.TRUE.equals(properties.remove(property.getKey()))) {
        flags.add(property.getValue());
      }
    }
    return flags;
  }

  /**
   * Decodes Luke's flag string ({@code "I-SD------OF-----l"}) with SolrJ's {@link
   * FieldFlag#getFlag(char)}.
   *
   * <p>Not {@code LukeResponse.FieldInfo.parseFlags}: it adds {@code getFlag}'s result without a
   * null check, so a letter a later Solr version adds would throw.
   *
   * @param letters Luke's flag string
   * @return the decoded flags, or {@code null} if a letter is not a known flag, so the caller can
   *     keep the string rather than report a partial set
   */
  static @Nullable EnumSet<FieldFlag> fromLukeLetters(String letters) {
    EnumSet<FieldFlag> flags = EnumSet.noneOf(FieldFlag.class);
    for (char letter : letters.toCharArray()) {
      if (letter == '-') {
        continue;
      }
      FieldFlag flag = FieldFlag.getFlag(letter);
      if (flag == null) {
        return null;
      }
      flags.add(flag);
    }
    return flags;
  }

  /**
   * Writes a flag as its enum constant name ({@code DOC_VALUES}). Without it Jackson 3 writes
   * {@link FieldFlag#toString()}, Luke's legend text ({@code "D - DocValues"}).
   */
  public static final class NameSerializer extends ValueSerializer<FieldFlag> {
    @Override
    public void serialize(FieldFlag flag, JsonGenerator generator, SerializationContext context) {
      generator.writeString(flag.name());
    }
  }
}
