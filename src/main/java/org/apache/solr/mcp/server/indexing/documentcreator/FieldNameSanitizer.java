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
package org.apache.solr.mcp.server.indexing.documentcreator;

import java.util.regex.Pattern;

/**
 * Utility class for sanitizing field names to ensure compatibility with Solr's field naming
 * requirements.
 *
 * <p>This class provides shared regex patterns and sanitization logic that can be used across all
 * document creators to ensure consistent field name handling.
 *
 * <p>Solr has specific requirements for field names that must be met to ensure proper indexing and
 * searching functionality. This utility transforms arbitrary field names into Solr-compliant
 * identifiers.
 */
public final class FieldNameSanitizer {

    /**
     * Pattern to match invalid characters in field names. Matches any character that is not
     * alphanumeric or underscore.
     */
    private static final Pattern INVALID_CHARACTERS_PATTERN = Pattern.compile("[\\W]");

    /**
     * Pattern to match leading and trailing underscores. Uses explicit grouping to make operator
     * precedence clear.
     */
    private static final Pattern LEADING_TRAILING_UNDERSCORES_PATTERN =
            Pattern.compile("(^_+)|(_+$)");

    /**
     * Pattern to match multiple consecutive underscores. Matches two or more consecutive
     * underscores to collapse them into one.
     */
    private static final Pattern MULTIPLE_UNDERSCORES_PATTERN = Pattern.compile("_{2,}");

    // Private constructor to prevent instantiation
    private FieldNameSanitizer() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Sanitizes field names to ensure they are compatible with Solr's field naming requirements.
     *
     * <p><strong>Sanitization Rules:</strong>
     *
     * <ul>
     *   <li><strong>Case Conversion</strong>: All characters converted to lowercase
     *   <li><strong>Character Replacement</strong>: Non-alphanumeric characters replaced with
     *       underscores
     *   <li><strong>Edge Trimming</strong>: Leading and trailing underscores removed
     *   <li><strong>Duplicate Compression</strong>: Multiple consecutive underscores collapsed to
     *       single
     *   <li><strong>Numeric Prefix</strong>: Field names starting with numbers get "field_" prefix
     * </ul>
     *
     * <p><strong>Example Transformations:</strong>
     *
     * <ul>
     *   <li>"User-Name" → "user_name"
     *   <li>"product.price" → "product_price"
     *   <li>"__field__name__" → "field_name"
     *   <li>"Field123@Test" → "field123_test"
     *   <li>"123field" → "field_123field"
     * </ul>
     *
     * @param fieldName the original field name to sanitize
     * @return sanitized field name compatible with Solr requirements, or "field" if input is
     *     null/empty
     * @see <a href="https://solr.apache.org/guide/solr/latest/indexing-guide/fields.html">Solr
     *     Field Guide</a>
     */
    public static String sanitizeFieldName(String fieldName) {

        // Convert to lowercase and replace invalid characters with underscores
        String sanitized =
                INVALID_CHARACTERS_PATTERN.matcher(fieldName.toLowerCase()).replaceAll("_");

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
