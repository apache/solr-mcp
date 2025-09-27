package org.apache.solr.mcp.server;

import org.apache.solr.common.util.NamedList;

/**
 * Utility class providing type-safe helper methods for extracting values from Apache Solr NamedList objects.
 * 
 * <p>This utility class simplifies the process of working with Solr's {@code NamedList} response format
 * by providing robust type conversion methods that handle various data formats and edge cases commonly
 * encountered when processing Solr admin and query responses.</p>
 * 
 * <p><strong>Key Benefits:</strong></p>
 * <ul>
 *   <li><strong>Type Safety</strong>: Automatic conversion with proper error handling</li>
 *   <li><strong>Null Safety</strong>: Graceful handling of missing or null values</li>
 *   <li><strong>Format Flexibility</strong>: Support for multiple input data types</li>
 *   <li><strong>Error Resilience</strong>: Defensive programming against malformed data</li>
 * </ul>
 * 
 * <p><strong>Common Use Cases:</strong></p>
 * <ul>
 *   <li>Extracting metrics from Solr MBeans responses</li>
 *   <li>Processing Luke handler index statistics</li>
 *   <li>Converting admin API response values to typed objects</li>
 *   <li>Handling cache and handler performance metrics</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <p>All methods in this utility class are stateless and thread-safe, making them
 * suitable for use in concurrent environments and Spring service beans.</p>
 * 
 * @author Solr MCP Server
 * @version 1.0
 * @since 1.0
 * 
 * @see org.apache.solr.common.util.NamedList
 * @see CollectionService
 */
public class Utils {

    /**
     * Extracts a Long value from a NamedList using the specified key with robust type conversion.
     * 
     * <p>This method provides flexible extraction of Long values from Solr NamedList responses,
     * handling various input formats that may be returned by different Solr endpoints. It performs
     * safe type conversion with appropriate error handling for malformed data.</p>
     * 
     * <p><strong>Supported Input Types:</strong></p>
     * <ul>
     *   <li><strong>Number instances</strong>: Integer, Long, Double, Float, BigInteger, BigDecimal</li>
     *   <li><strong>String representations</strong>: Numeric strings that can be parsed as Long</li>
     *   <li><strong>Null values</strong>: Returns null without throwing exceptions</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Returns {@code null} for missing keys, null values, or unparseable strings rather than
     * throwing exceptions, enabling graceful degradation in metrics collection scenarios.</p>
     * 
     * <p><strong>Common Use Cases:</strong></p>
     * <ul>
     *   <li>Cache statistics: hits, lookups, evictions, size</li>
     *   <li>Handler metrics: request counts, error counts, timeouts</li>
     *   <li>Index statistics: document counts, segment information</li>
     * </ul>
     *
     * @param response the NamedList containing the data to extract from
     * @param key the key to look up in the NamedList
     * @return the Long value if found and convertible, null otherwise
     * 
     * @see Number#longValue()
     * @see Long#parseLong(String)
     */
    public static Long getLong(NamedList<Object> response, String key) {
        Object value = response.get(key);
        if (value == null) return null;

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts a Float value from a NamedList using the specified key with automatic type conversion.
     * 
     * <p>This method provides convenient extraction of Float values from Solr NamedList responses,
     * commonly used for extracting percentage values, ratios, and performance metrics. It assumes
     * that missing values should be treated as zero, which is appropriate for most metric scenarios.</p>
     * 
     * <p><strong>Type Conversion:</strong></p>
     * <p>Automatically converts any Number instance to Float using the {@link Number#floatValue()}
     * method, ensuring compatibility with various numeric types returned by Solr.</p>
     * 
     * <p><strong>Default Value Behavior:</strong></p>
     * <p>Returns {@code 0.0f} for missing or null values, which is typically the desired behavior
     * for metrics like hit ratios, performance averages, and statistical calculations where
     * missing data should be interpreted as zero.</p>
     * 
     * <p><strong>Common Use Cases:</strong></p>
     * <ul>
     *   <li>Cache hit ratios and performance percentages</li>
     *   <li>Average response times and throughput metrics</li>
     *   <li>Statistical calculations and performance indicators</li>
     * </ul>
     * 
     * <p><strong>Note:</strong></p>
     * <p>This method differs from {@link #getLong(NamedList, String)} by returning a default
     * value instead of null, which is more appropriate for Float metrics that represent
     * rates, ratios, or averages.</p>
     *
     * @param stats the NamedList containing the metric data to extract from
     * @param key the key to look up in the NamedList
     * @return the Float value if found, or 0.0f if the key doesn't exist or value is null
     * 
     * @see Number#floatValue()
     */
    public static Float getFloat(NamedList<Object> stats, String key) {
        Object value = stats.get(key);
        return value != null ? ((Number) value).floatValue() : 0.0f;
    }

    /**
     * Extracts an Integer value from a NamedList using the specified key with robust type conversion.
     * 
     * <p>This method provides flexible extraction of Integer values from Solr NamedList responses,
     * handling various input formats that may be returned by different Solr endpoints. It performs
     * safe type conversion with appropriate error handling for malformed data.</p>
     * 
     * <p><strong>Supported Input Types:</strong></p>
     * <ul>
     *   <li><strong>Number instances</strong>: Integer, Long, Double, Float, BigInteger, BigDecimal</li>
     *   <li><strong>String representations</strong>: Numeric strings that can be parsed as Integer</li>
     *   <li><strong>Null values</strong>: Returns null without throwing exceptions</li>
     * </ul>
     * 
     * <p><strong>Type Conversion Strategy:</strong></p>
     * <p>For Number instances, uses {@link Number#intValue()} which truncates decimal values.
     * For string values, attempts parsing with {@link Integer#parseInt(String)} and returns
     * null if parsing fails rather than throwing an exception.</p>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Returns {@code null} for missing keys, null values, or unparseable strings rather than
     * throwing exceptions, enabling graceful degradation in metrics collection scenarios.</p>
     * 
     * <p><strong>Common Use Cases:</strong></p>
     * <ul>
     *   <li>Index segment counts and document counts (when within Integer range)</li>
     *   <li>Configuration values and small numeric metrics</li>
     *   <li>Count-based statistics that don't exceed Integer.MAX_VALUE</li>
     * </ul>
     * 
     * <p><strong>Range Considerations:</strong></p>
     * <p>For large values that may exceed Integer range, consider using {@link #getLong(NamedList, String)}
     * instead to avoid truncation or overflow issues.</p>
     *
     * @param response the NamedList containing the data to extract from
     * @param key the key to look up in the NamedList
     * @return the Integer value if found and convertible, null otherwise
     * 
     * @see Number#intValue()
     * @see Integer#parseInt(String)
     * @see #getLong(NamedList, String)
     */
    public static Integer getInteger(NamedList<Object> response, String key) {
        Object value = response.get(key);
        if (value == null) return null;

        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
