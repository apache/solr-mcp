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
import org.apache.solr.common.luke.FieldFlag;
import org.jspecify.annotations.Nullable;

/**
 * A field property in effect for an indexed field, as {@code list-fields} reports it.
 *
 * <p>The names are the camelCase spellings of Solr's schema attributes ({@code indexed}, {@code
 * docValues}, ...), the vocabulary {@code get-schema} and {@code add-fields} use too. The constants
 * are written that way on purpose: Spring AI's output-schema generator lists enum constants by
 * name, so the constant names are what clients see, both in the schema and in the result.
 *
 * <p>SolrJ's {@link FieldFlag} decodes Luke's flag letters; this enum keeps that SolrJ type, and
 * Luke's letter encoding, out of the contract.
 */
@SuppressWarnings("java:S115") // constant names are the JSON values; see the class comment
public enum FieldProperty {
  indexed,
  tokenized,
  stored,
  docValues,
  uninvertible,
  multiValued,
  termVectors,
  termOffsets,
  termPositions,
  termPayloads,
  omitNorms,
  omitTermFreqAndPositions,
  omitPositions,
  storeOffsetsWithPositions,
  lazy,
  binary,
  sortMissingFirst,
  sortMissingLast;

  /** The property for one of SolrJ's Luke flags. */
  static FieldProperty of(FieldFlag flag) {
    return switch (flag) {
      case INDEXED -> indexed;
      case TOKENIZED -> tokenized;
      case STORED -> stored;
      case DOC_VALUES -> docValues;
      case UNINVERTIBLE -> uninvertible;
      case MULTI_VALUED -> multiValued;
      case TERM_VECTOR_STORED -> termVectors;
      case TERM_VECTOR_OFFSET -> termOffsets;
      case TERM_VECTOR_POSITION -> termPositions;
      case TERM_VECTOR_PAYLOADS -> termPayloads;
      case OMIT_NORMS -> omitNorms;
      case OMIT_TF -> omitTermFreqAndPositions;
      case OMIT_POSITIONS -> omitPositions;
      case STORE_OFFSETS_WITH_POSITIONS -> storeOffsetsWithPositions;
      case LAZY -> lazy;
      case BINARY -> binary;
      case SORT_MISSING_FIRST -> sortMissingFirst;
      case SORT_MISSING_LAST -> sortMissingLast;
    };
  }

  /**
   * Decodes Luke's per-field flag string, such as {@code I-S-M---OF-----l}, where {@code -} marks
   * an absent flag.
   *
   * @return the properties, or {@code null} when a letter is not one SolrJ knows
   */
  static @Nullable EnumSet<FieldProperty> fromLukeLetters(String letters) {
    EnumSet<FieldProperty> properties = EnumSet.noneOf(FieldProperty.class);
    for (char letter : letters.toCharArray()) {
      if (letter == '-') {
        continue;
      }
      FieldFlag flag = FieldFlag.getFlag(letter);
      if (flag == null) {
        return null;
      }
      properties.add(of(flag));
    }
    return properties;
  }
}
