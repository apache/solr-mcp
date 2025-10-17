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
package org.apache.solr.mcp.server.metadata;

import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Comprehensive test suite for the Utils utility class.
 * Tests all public methods and edge cases for type-safe value extraction from Solr NamedList objects.
 */
class CollectionUtilsTest {

    @Test
    void testGetLong_withNullValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("nullKey", null);

        assertNull(CollectionUtils.getLong(namedList, "nullKey"));
    }

    @Test
    void testGetLong_withMissingKey() {
        NamedList<Object> namedList = new NamedList<>();

        assertNull(CollectionUtils.getLong(namedList, "missingKey"));
    }

    @Test
    void testGetLong_withIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("intKey", 123);

        assertEquals(123L, CollectionUtils.getLong(namedList, "intKey"));
    }

    @Test
    void testGetLong_withLongValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("longKey", 123456789L);

        assertEquals(123456789L, CollectionUtils.getLong(namedList, "longKey"));
    }

    @Test
    void testGetLong_withDoubleValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("doubleKey", 123.45);

        assertEquals(123L, CollectionUtils.getLong(namedList, "doubleKey"));
    }

    @Test
    void testGetLong_withFloatValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("floatKey", 123.45f);

        assertEquals(123L, CollectionUtils.getLong(namedList, "floatKey"));
    }

    @Test
    void testGetLong_withBigIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigIntKey", new BigInteger("123456789"));

        assertEquals(123456789L, CollectionUtils.getLong(namedList, "bigIntKey"));
    }

    @Test
    void testGetLong_withBigDecimalValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigDecKey", new BigDecimal("123.45"));

        assertEquals(123L, CollectionUtils.getLong(namedList, "bigDecKey"));
    }

    @Test
    void testGetLong_withValidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("stringKey", "123456");

        assertEquals(123456L, CollectionUtils.getLong(namedList, "stringKey"));
    }

    @Test
    void testGetLong_withInvalidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("invalidStringKey", "not_a_number");

        assertNull(CollectionUtils.getLong(namedList, "invalidStringKey"));
    }

    @Test
    void testGetLong_withEmptyStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("emptyStringKey", "");

        assertNull(CollectionUtils.getLong(namedList, "emptyStringKey"));
    }

    @Test
    void testGetFloat_withNullValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("nullKey", null);

        assertEquals(0.0f, CollectionUtils.getFloat(namedList, "nullKey"));
    }

    @Test
    void testGetFloat_withMissingKey() {
        NamedList<Object> namedList = new NamedList<>();

        assertEquals(0.0f, CollectionUtils.getFloat(namedList, "missingKey"));
    }

    @Test
    void testGetFloat_withIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("intKey", 123);

        assertEquals(123.0f, CollectionUtils.getFloat(namedList, "intKey"));
    }

    @Test
    void testGetFloat_withFloatValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("floatKey", 123.45f);

        assertEquals(123.45f, CollectionUtils.getFloat(namedList, "floatKey"), 0.001f);
    }

    @Test
    void testGetFloat_withDoubleValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("doubleKey", 123.456789);

        assertEquals(123.456789f, CollectionUtils.getFloat(namedList, "doubleKey"), 0.001f);
    }

    @Test
    void testGetFloat_withLongValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("longKey", 123456789L);

        assertEquals(123456789.0f, CollectionUtils.getFloat(namedList, "longKey"));
    }

    @Test
    void testGetFloat_withBigDecimalValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigDecKey", new BigDecimal("123.45"));

        assertEquals(123.45f, CollectionUtils.getFloat(namedList, "bigDecKey"), 0.001f);
    }

    @Test
    void testGetInteger_withNullValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("nullKey", null);

        assertNull(CollectionUtils.getInteger(namedList, "nullKey"));
    }

    @Test
    void testGetInteger_withMissingKey() {
        NamedList<Object> namedList = new NamedList<>();

        assertNull(CollectionUtils.getInteger(namedList, "missingKey"));
    }

    @Test
    void testGetInteger_withIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("intKey", 123);

        assertEquals(123, CollectionUtils.getInteger(namedList, "intKey"));
    }

    @Test
    void testGetInteger_withLongValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("longKey", 123456L);

        assertEquals(123456, CollectionUtils.getInteger(namedList, "longKey"));
    }

    @Test
    void testGetInteger_withDoubleValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("doubleKey", 123.45);

        assertEquals(123, CollectionUtils.getInteger(namedList, "doubleKey"));
    }

    @Test
    void testGetInteger_withFloatValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("floatKey", 123.45f);

        assertEquals(123, CollectionUtils.getInteger(namedList, "floatKey"));
    }

    @Test
    void testGetInteger_withBigIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigIntKey", new BigInteger("123456"));

        assertEquals(123456, CollectionUtils.getInteger(namedList, "bigIntKey"));
    }

    @Test
    void testGetInteger_withValidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("stringKey", "123456");

        assertEquals(123456, CollectionUtils.getInteger(namedList, "stringKey"));
    }

    @Test
    void testGetInteger_withInvalidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("invalidStringKey", "not_a_number");

        assertNull(CollectionUtils.getInteger(namedList, "invalidStringKey"));
    }

    @Test
    void testGetInteger_withEmptyStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("emptyStringKey", "");

        assertNull(CollectionUtils.getInteger(namedList, "emptyStringKey"));
    }

    @Test
    void testGetInteger_withOverflowValue() {
        NamedList<Object> namedList = new NamedList<>();
        // Value larger than Integer.MAX_VALUE
        namedList.add("overflowKey", Long.MAX_VALUE);

        // Should truncate to int value (overflow behavior)
        assertEquals(-1, CollectionUtils.getInteger(namedList, "overflowKey"));
    }

    @Test
    void testGetLong_withNegativeValues() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("negativeKey", -123456);

        assertEquals(-123456L, CollectionUtils.getLong(namedList, "negativeKey"));
    }

    @Test
    void testGetFloat_withNegativeValues() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("negativeKey", -123.45f);

        assertEquals(-123.45f, CollectionUtils.getFloat(namedList, "negativeKey"), 0.001f);
    }

    @Test
    void testGetInteger_withNegativeValues() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("negativeKey", -123456);

        assertEquals(-123456, CollectionUtils.getInteger(namedList, "negativeKey"));
    }

    @Test
    void testGetLong_withZeroValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("zeroKey", 0);

        assertEquals(0L, CollectionUtils.getLong(namedList, "zeroKey"));
    }

    @Test
    void testGetFloat_withZeroValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("zeroKey", 0.0f);

        assertEquals(0.0f, CollectionUtils.getFloat(namedList, "zeroKey"));
    }

    @Test
    void testGetInteger_withZeroValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("zeroKey", 0);

        assertEquals(0, CollectionUtils.getInteger(namedList, "zeroKey"));
    }
}