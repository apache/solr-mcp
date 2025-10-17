package org.apache.solr.mcp.server.indexing.documentcreator;

import org.apache.solr.common.SolrInputDocument;

import java.util.List;

/**
 * Interface defining the contract for creating SolrInputDocument objects from various data formats.
 *
 * <p>This interface provides a unified abstraction for converting different document formats
 * (JSON, CSV, XML, etc.) into Solr-compatible SolrInputDocument objects. Implementations
 * handle format-specific parsing and field sanitization to ensure proper Solr indexing.</p>
 *
 * <p><strong>Design Principles:</strong></p>
 * <ul>
 *   <li><strong>Format Agnostic</strong>: Common interface for all document types</li>
 *   <li><strong>Schema-less Processing</strong>: Supports dynamic field creation without predefined schema</li>
 *   <li><strong>Error Handling</strong>: Consistent exception handling across implementations</li>
 *   <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for Solr compatibility</li>
 * </ul>
 *
 * <p><strong>Implementation Guidelines:</strong></p>
 * <ul>
 *   <li>Handle null or empty input gracefully</li>
 *   <li>Sanitize field names using {@link FieldNameSanitizer}</li>
 *   <li>Preserve original data types where possible</li>
 *   <li>Throw {@link DocumentProcessingException} for processing errors</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * SolrDocumentCreator creator = new JsonDocumentCreator();
 * String jsonData = "[{\"title\":\"Document 1\",\"content\":\"Content here\"}]";
 * List<SolrInputDocument> documents = creator.create(jsonData);
 * }</pre>
 *
 * @author adityamparikh
 * @version 0.0.1
 * @see SolrInputDocument
 * @see DocumentProcessingException
 * @see FieldNameSanitizer
 * @since 0.0.1
 */
public interface SolrDocumentCreator {

    /**
     * Creates a list of SolrInputDocument objects from the provided content string.
     *
     * <p>This method parses the input content according to the specific format handled by
     * the implementing class (JSON, CSV, XML, etc.) and converts it into a list of
     * SolrInputDocument objects ready for indexing.</p>
     *
     * <p><strong>Processing Behavior:</strong></p>
     * <ul>
     *   <li><strong>Field Sanitization</strong>: All field names are sanitized for Solr compatibility</li>
     *   <li><strong>Type Preservation</strong>: Original data types are maintained where possible</li>
     *   <li><strong>Multiple Documents</strong>: Single content string may produce multiple documents</li>
     *   <li><strong>Error Handling</strong>: Invalid content results in DocumentProcessingException</li>
     * </ul>
     *
     * <p><strong>Input Validation:</strong></p>
     * <ul>
     *   <li>Null input should be handled gracefully (implementation-dependent)</li>
     *   <li>Empty input should return empty list</li>
     *   <li>Malformed content should throw DocumentProcessingException</li>
     * </ul>
     *
     * @param content the content string to be parsed and converted to SolrInputDocument objects.
     *                The format depends on the implementing class (JSON array, CSV data, XML, etc.)
     * @return a list of SolrInputDocument objects created from the parsed content.
     * Returns empty list if content is empty or contains no valid documents
     * @throws DocumentProcessingException if the content cannot be parsed or converted due to
     *                                     format errors, invalid structure, or processing failures
     * @throws IllegalArgumentException    if content is null (implementation-dependent)
     */
    List<SolrInputDocument> create(String content) throws DocumentProcessingException;
}
