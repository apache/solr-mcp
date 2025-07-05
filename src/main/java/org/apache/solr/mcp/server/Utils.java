package org.apache.solr.mcp.server;

import org.apache.solr.common.util.NamedList;

/**
 * Utility class providing helper methods for working with Solr NamedList objects.
 * These methods help extract typed values from Solr responses.
 */
public class Utils {

    /**
     * Extracts a Long value from a NamedList using the specified key.
     * Handles type conversion from various number formats and string representations.
     *
     * @param response The NamedList to extract the value from
     * @param key The key to look up in the NamedList
     * @return The Long value, or null if the key doesn't exist or the value can't be converted to a Long
     */
    public static Long getLong(NamedList<Object> response, String key) {
        Object value = response.get(key);
        if (value == null) return null;

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts a Float value from a NamedList using the specified key.
     * Returns 0.0f if the key doesn't exist.
     *
     * @param stats The NamedList to extract the value from
     * @param key The key to look up in the NamedList
     * @return The Float value, or 0.0f if the key doesn't exist
     */
    public static Float getFloat(NamedList<Object> stats, String key) {
        Object value = stats.get(key);
        return value != null ? ((Number) value).floatValue() : 0.0f;
    }

    /**
     * Extracts an Integer value from a NamedList using the specified key.
     * Handles type conversion from various number formats and string representations.
     *
     * @param response The NamedList to extract the value from
     * @param key The key to look up in the NamedList
     * @return The Integer value, or null if the key doesn't exist or the value can't be converted to an Integer
     */
    public static Integer getInteger(NamedList<Object> response, String key) {
        Object value = response.get(key);
        if (value == null) return null;

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
