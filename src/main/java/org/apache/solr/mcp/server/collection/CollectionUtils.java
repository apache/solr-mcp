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
package org.apache.solr.mcp.server.collection;

import java.util.function.Function;
import org.apache.solr.common.util.NamedList;
import org.jspecify.annotations.Nullable;

/**
 * Null-safe, lenient numeric accessors for Solr {@link NamedList} responses.
 *
 * <p>Solr admin endpoints are inconsistent about how they encode numbers: the same metric can
 * arrive as an {@code Integer}, a {@code Long}, a {@code Double} or a numeric string depending on
 * the endpoint and Solr version. Every accessor here accepts any {@link Number} or numeric string
 * and returns {@code null} for a missing key, a {@code null} value, or text that does not parse, so
 * metrics collection degrades instead of failing on one malformed entry.
 *
 * @see CollectionService
 */
public final class CollectionUtils {

  private CollectionUtils() {}

  /**
   * Reads {@code key} as a {@link Long}.
   *
   * @param response the NamedList to read from
   * @param key the key to look up
   * @return the value as a Long, or {@code null} if it is missing or not numeric
   */
  public static @Nullable Long getLong(NamedList<?> response, String key) {
    return getNumber(response, key, Number::longValue, Long::parseLong);
  }

  /**
   * Reads {@code key} as a {@link Float}.
   *
   * @param stats the NamedList to read from
   * @param key the key to look up
   * @return the value as a Float, or {@code null} if it is missing or not numeric
   */
  public static @Nullable Float getFloat(NamedList<?> stats, String key) {
    return getNumber(stats, key, Number::floatValue, Float::parseFloat);
  }

  /**
   * Reads {@code key} as an {@link Integer}.
   *
   * @param response the NamedList to read from
   * @param key the key to look up
   * @return the value as an Integer, or {@code null} if it is missing or not numeric
   */
  public static @Nullable Integer getInteger(NamedList<?> response, String key) {
    return getNumber(response, key, Number::intValue, Integer::parseInt);
  }

  private static <T> @Nullable T getNumber(
      NamedList<?> namedList,
      String key,
      Function<Number, T> fromNumber,
      Function<String, T> fromText) {
    Object value = namedList.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return fromNumber.apply(number);
    }
    try {
      return fromText.apply(value.toString());
    } catch (NumberFormatException _) {
      return null;
    }
  }
}
