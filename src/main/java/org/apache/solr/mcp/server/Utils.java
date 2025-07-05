package org.apache.solr.mcp.server;

import org.apache.solr.common.util.NamedList;

public class Utils {

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

    public static Float getFloat(NamedList<Object> stats, String key) {
        Object value = stats.get(key);
        return value != null ? ((Number) value).floatValue() : 0.0f;
    }

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
