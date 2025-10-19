package org.apache.solr.mcp.server.metadata;

import org.apache.solr.common.util.NamedList;

/**
 * Utility class providing type-safe helper methods for extracting values from Apache Solr NamedList
 * objects.
 *
 * <p>This utility class simplifies the process of working with Solr's {@code NamedList} response
 * format by providing robust type conversion methods that handle various data formats and edge
 * cases commonly encountered when processing Solr admin and query responses.
 *
 * <p><strong>Key Benefits:</strong>
 *
 * <ul>
 *   <li><strong>Type Safety</strong>: Automatic conversion with proper error handling
 *   <li><strong>Null Safety</strong>: Graceful handling of missing or null values
 *   <li><strong>Format Flexibility</strong>: Support for multiple input data types
 *   <li><strong>Error Resilience</strong>: Defensive programming against malformed data
 * </ul>
 *
 * <p><strong>Common Use Cases:</strong>
 *
 * <ul>
 *   <li>Extracting metrics from Solr MBeans responses
 *   <li>Processing Luke handler index statistics
 *   <li>Converting admin API response values to typed objects
 *   <li>Handling cache and handler performance metrics
 * </ul>
 *
 * <p><strong>Thread Safety:</strong>
 *
 * <p>All methods in this utility class are stateless and thread-safe, making them suitable for use
 * in concurrent environments and Spring service beans.
 *
 * @author adityamparikh
 * @version 0.0.1
 * @see org.apache.solr.common.util.NamedList
 * @see CollectionService
 * @since 0.0.1
 */
public class CollectionUtils {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private CollectionUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Extracts a Long value from a NamedList using the specified key with robust type conversion.
     *
     * <p>This method provides flexible extraction of Long values from Solr NamedList responses,
     * handling various input formats that may be returned by different Solr endpoints. It performs
     * safe type conversion with appropriate error handling for malformed data.
     *
     * <p><strong>Supported Input Types:</strong>
     *
     * <ul>
     *   <li><strong>Number instances</strong>: Integer, Long, Double, Float, BigInteger, BigDecimal
     *   <li><strong>String representations</strong>: Numeric strings that can be parsed as Long
     *   <li><strong>Null values</strong>: Returns null without throwing exceptions
     * </ul>
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>Returns {@code null} for missing keys, null values, or unparseable strings rather than
     * throwing exceptions, enabling graceful degradation in metrics collection scenarios.
     *
     * <p><strong>Common Use Cases:</strong>
     *
     * <ul>
     *   <li>Cache statistics: hits, lookups, evictions, size
     *   <li>Handler metrics: request counts, error counts, timeouts
     *   <li>Index statistics: document counts, segment information
     * </ul>
     *
     * @param response the NamedList containing the data to extract from
     * @param key the key to look up in the NamedList
     * @return the Long value if found and convertible, null otherwise
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
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Extracts a Float value from a NamedList using the specified key with automatic type
     * conversion.
     *
     * <p>This method provides convenient extraction of Float values from Solr NamedList responses,
     * commonly used for extracting percentage values, ratios, and performance metrics. It assumes
     * that missing values should be treated as zero, which is appropriate for most metric
     * scenarios.
     *
     * <p><strong>Type Conversion:</strong>
     *
     * <p>Automatically converts any Number instance to Float using the {@link Number#floatValue()}
     * method, ensuring compatibility with various numeric types returned by Solr.
     *
     * <p><strong>Default Value Behavior:</strong>
     *
     * <p>Returns {@code 0.0f} for missing or null values, which is typically the desired behavior
     * for metrics like hit ratios, performance averages, and statistical calculations where missing
     * data should be interpreted as zero.
     *
     * <p><strong>Common Use Cases:</strong>
     *
     * <ul>
     *   <li>Cache hit ratios and performance percentages
     *   <li>Average response times and throughput metrics
     *   <li>Statistical calculations and performance indicators
     * </ul>
     *
     * <p><strong>Note:</strong>
     *
     * <p>This method differs from {@link #getLong(NamedList, String)} by returning a default value
     * instead of null, which is more appropriate for Float metrics that represent rates, ratios, or
     * averages.
     *
     * @param stats the NamedList containing the metric data to extract from
     * @param key the key to look up in the NamedList
     * @return the Float value if found, or 0.0f if the key doesn't exist or value is null
     * @see Number#floatValue()
     */
    public static Float getFloat(NamedList<Object> stats, String key) {
        Object value = stats.get(key);
        return value != null ? ((Number) value).floatValue() : 0.0f;
    }

    /**
     * Extracts an Integer value from a NamedList using the specified key with robust type
     * conversion.
     *
     * <p>This method provides flexible extraction of Integer values from Solr NamedList responses,
     * handling various input formats that may be returned by different Solr endpoints. It performs
     * safe type conversion with appropriate error handling for malformed data.
     *
     * <p><strong>Supported Input Types:</strong>
     *
     * <ul>
     *   <li><strong>Number instances</strong>: Integer, Long, Double, Float, BigInteger, BigDecimal
     *   <li><strong>String representations</strong>: Numeric strings that can be parsed as Integer
     *   <li><strong>Null values</strong>: Returns null without throwing exceptions
     * </ul>
     *
     * <p><strong>Type Conversion Strategy:</strong>
     *
     * <p>For Number instances, uses {@link Number#intValue()} which truncates decimal values. For
     * string values, attempts parsing with {@link Integer#parseInt(String)} and returns null if
     * parsing fails rather than throwing an exception.
     *
     * <p><strong>Error Handling:</strong>
     *
     * <p>Returns {@code null} for missing keys, null values, or unparseable strings rather than
     * throwing exceptions, enabling graceful degradation in metrics collection scenarios.
     *
     * <p><strong>Common Use Cases:</strong>
     *
     * <ul>
     *   <li>Index segment counts and document counts (when within Integer range)
     *   <li>Configuration values and small numeric metrics
     *   <li>Count-based statistics that don't exceed Integer.MAX_VALUE
     * </ul>
     *
     * <p><strong>Range Considerations:</strong>
     *
     * <p>For large values that may exceed Integer range, consider using {@link #getLong(NamedList,
     * String)} instead to avoid truncation or overflow issues.
     *
     * @param response the NamedList containing the data to extract from
     * @param key the key to look up in the NamedList
     * @return the Integer value if found and convertible, null otherwise
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
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
