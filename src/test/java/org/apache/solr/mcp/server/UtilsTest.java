package org.apache.solr.mcp.server;

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
class UtilsTest {

    @Test
    void testGetLong_withNullValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("nullKey", null);

        assertNull(Utils.getLong(namedList, "nullKey"));
    }

    @Test
    void testGetLong_withMissingKey() {
        NamedList<Object> namedList = new NamedList<>();

        assertNull(Utils.getLong(namedList, "missingKey"));
    }

    @Test
    void testGetLong_withIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("intKey", 123);

        assertEquals(123L, Utils.getLong(namedList, "intKey"));
    }

    @Test
    void testGetLong_withLongValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("longKey", 123456789L);

        assertEquals(123456789L, Utils.getLong(namedList, "longKey"));
    }

    @Test
    void testGetLong_withDoubleValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("doubleKey", 123.45);

        assertEquals(123L, Utils.getLong(namedList, "doubleKey"));
    }

    @Test
    void testGetLong_withFloatValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("floatKey", 123.45f);

        assertEquals(123L, Utils.getLong(namedList, "floatKey"));
    }

    @Test
    void testGetLong_withBigIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigIntKey", new BigInteger("123456789"));

        assertEquals(123456789L, Utils.getLong(namedList, "bigIntKey"));
    }

    @Test
    void testGetLong_withBigDecimalValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigDecKey", new BigDecimal("123.45"));

        assertEquals(123L, Utils.getLong(namedList, "bigDecKey"));
    }

    @Test
    void testGetLong_withValidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("stringKey", "123456");

        assertEquals(123456L, Utils.getLong(namedList, "stringKey"));
    }

    @Test
    void testGetLong_withInvalidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("invalidStringKey", "not_a_number");

        assertNull(Utils.getLong(namedList, "invalidStringKey"));
    }

    @Test
    void testGetLong_withEmptyStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("emptyStringKey", "");

        assertNull(Utils.getLong(namedList, "emptyStringKey"));
    }

    @Test
    void testGetFloat_withNullValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("nullKey", null);

        assertEquals(0.0f, Utils.getFloat(namedList, "nullKey"));
    }

    @Test
    void testGetFloat_withMissingKey() {
        NamedList<Object> namedList = new NamedList<>();

        assertEquals(0.0f, Utils.getFloat(namedList, "missingKey"));
    }

    @Test
    void testGetFloat_withIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("intKey", 123);

        assertEquals(123.0f, Utils.getFloat(namedList, "intKey"));
    }

    @Test
    void testGetFloat_withFloatValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("floatKey", 123.45f);

        assertEquals(123.45f, Utils.getFloat(namedList, "floatKey"), 0.001f);
    }

    @Test
    void testGetFloat_withDoubleValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("doubleKey", 123.456789);

        assertEquals(123.456789f, Utils.getFloat(namedList, "doubleKey"), 0.001f);
    }

    @Test
    void testGetFloat_withLongValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("longKey", 123456789L);

        assertEquals(123456789.0f, Utils.getFloat(namedList, "longKey"));
    }

    @Test
    void testGetFloat_withBigDecimalValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigDecKey", new BigDecimal("123.45"));

        assertEquals(123.45f, Utils.getFloat(namedList, "bigDecKey"), 0.001f);
    }

    @Test
    void testGetInteger_withNullValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("nullKey", null);

        assertNull(Utils.getInteger(namedList, "nullKey"));
    }

    @Test
    void testGetInteger_withMissingKey() {
        NamedList<Object> namedList = new NamedList<>();

        assertNull(Utils.getInteger(namedList, "missingKey"));
    }

    @Test
    void testGetInteger_withIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("intKey", 123);

        assertEquals(123, Utils.getInteger(namedList, "intKey"));
    }

    @Test
    void testGetInteger_withLongValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("longKey", 123456L);

        assertEquals(123456, Utils.getInteger(namedList, "longKey"));
    }

    @Test
    void testGetInteger_withDoubleValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("doubleKey", 123.45);

        assertEquals(123, Utils.getInteger(namedList, "doubleKey"));
    }

    @Test
    void testGetInteger_withFloatValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("floatKey", 123.45f);

        assertEquals(123, Utils.getInteger(namedList, "floatKey"));
    }

    @Test
    void testGetInteger_withBigIntegerValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("bigIntKey", new BigInteger("123456"));

        assertEquals(123456, Utils.getInteger(namedList, "bigIntKey"));
    }

    @Test
    void testGetInteger_withValidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("stringKey", "123456");

        assertEquals(123456, Utils.getInteger(namedList, "stringKey"));
    }

    @Test
    void testGetInteger_withInvalidStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("invalidStringKey", "not_a_number");

        assertNull(Utils.getInteger(namedList, "invalidStringKey"));
    }

    @Test
    void testGetInteger_withEmptyStringValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("emptyStringKey", "");

        assertNull(Utils.getInteger(namedList, "emptyStringKey"));
    }

    @Test
    void testGetInteger_withOverflowValue() {
        NamedList<Object> namedList = new NamedList<>();
        // Value larger than Integer.MAX_VALUE
        namedList.add("overflowKey", Long.MAX_VALUE);

        // Should truncate to int value (overflow behavior)
        assertEquals(-1, Utils.getInteger(namedList, "overflowKey"));
    }

    @Test
    void testGetLong_withNegativeValues() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("negativeKey", -123456);

        assertEquals(-123456L, Utils.getLong(namedList, "negativeKey"));
    }

    @Test
    void testGetFloat_withNegativeValues() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("negativeKey", -123.45f);

        assertEquals(-123.45f, Utils.getFloat(namedList, "negativeKey"), 0.001f);
    }

    @Test
    void testGetInteger_withNegativeValues() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("negativeKey", -123456);

        assertEquals(-123456, Utils.getInteger(namedList, "negativeKey"));
    }

    @Test
    void testGetLong_withZeroValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("zeroKey", 0);

        assertEquals(0L, Utils.getLong(namedList, "zeroKey"));
    }

    @Test
    void testGetFloat_withZeroValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("zeroKey", 0.0f);

        assertEquals(0.0f, Utils.getFloat(namedList, "zeroKey"));
    }

    @Test
    void testGetInteger_withZeroValue() {
        NamedList<Object> namedList = new NamedList<>();
        namedList.add("zeroKey", 0);

        assertEquals(0, Utils.getInteger(namedList, "zeroKey"));
    }
}