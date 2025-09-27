package org.apache.solr.mcp.server.indexing.documentcreator;

import java.util.regex.Pattern;

/**
 * Utility class for sanitizing field names to ensure compatibility with Solr's field naming requirements.
 *
 * <p>This class provides shared regex patterns and sanitization logic that can be used across
 * all document creators to ensure consistent field name handling.</p>
 *
 * <p>Solr has specific requirements for field names that must be met to ensure proper
 * indexing and searching functionality. This utility transforms arbitrary field names
 * into Solr-compliant identifiers.</p>
 */
public final class FieldNameSanitizer {

    /**
     * Pattern to match invalid characters in field names.
     * Matches any character that is not alphanumeric or underscore.
     */
    private static final Pattern INVALID_CHARACTERS_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");

    /**
     * Pattern to match leading and trailing underscores.
     * Uses explicit grouping to make operator precedence clear.
     */
    private static final Pattern LEADING_TRAILING_UNDERSCORES_PATTERN = Pattern.compile("(^_+|_+$)");

    /**
     * Pattern to match multiple consecutive underscores.
     * Matches two or more consecutive underscores to collapse them into one.
     */
    private static final Pattern MULTIPLE_UNDERSCORES_PATTERN = Pattern.compile("_{2,}");

    // Private constructor to prevent instantiation
    private FieldNameSanitizer() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Sanitizes field names to ensure they are compatible with Solr's field naming requirements.
     *
     * <p><strong>Sanitization Rules:</strong></p>
     * <ul>
     *   <li><strong>Case Conversion</strong>: All characters converted to lowercase</li>
     *   <li><strong>Character Replacement</strong>: Non-alphanumeric characters replaced with underscores</li>
     *   <li><strong>Edge Trimming</strong>: Leading and trailing underscores removed</li>
     *   <li><strong>Duplicate Compression</strong>: Multiple consecutive underscores collapsed to single</li>
     *   <li><strong>Numeric Prefix</strong>: Field names starting with numbers get "field_" prefix</li>
     * </ul>
     *
     * <p><strong>Example Transformations:</strong></p>
     * <ul>
     *   <li>"User-Name" → "user_name"</li>
     *   <li>"product.price" → "product_price"</li>
     *   <li>"__field__name__" → "field_name"</li>
     *   <li>"Field123@Test" → "field123_test"</li>
     *   <li>"123field" → "field_123field"</li>
     * </ul>
     *
     * @param fieldName the original field name to sanitize
     * @return sanitized field name compatible with Solr requirements, or "field" if input is null/empty
     * @see <a href="https://solr.apache.org/guide/solr/latest/indexing-guide/fields.html">Solr Field Guide</a>
     */
    public static String sanitizeFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return "field";
        }

        // Convert to lowercase and replace invalid characters with underscores
        String sanitized = INVALID_CHARACTERS_PATTERN.matcher(fieldName.toLowerCase()).replaceAll("_");

        // Remove leading/trailing underscores and collapse multiple underscores
        sanitized = LEADING_TRAILING_UNDERSCORES_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = MULTIPLE_UNDERSCORES_PATTERN.matcher(sanitized).replaceAll("_");

        // If the result is empty after sanitization, provide a default name
        if (sanitized.isEmpty()) {
            return "field";
        }

        // Ensure the field name doesn't start with a number (Solr requirement)
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "field_" + sanitized;
        }

        return sanitized;
    }
}