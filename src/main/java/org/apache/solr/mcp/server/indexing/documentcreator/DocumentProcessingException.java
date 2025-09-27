package org.apache.solr.mcp.server.indexing.documentcreator;

/**
 * Exception thrown when document processing operations fail.
 *
 * <p>This exception provides a unified error handling mechanism for all document creator
 * implementations, wrapping various underlying exceptions while preserving the original
 * error context and stack trace information.</p>
 *
 * <p>Common scenarios where this exception is thrown:</p>
 * <ul>
 *   <li>Invalid document format or structure</li>
 *   <li>Document parsing errors (JSON, XML, CSV)</li>
 *   <li>Input validation failures</li>
 *   <li>Resource access or I/O errors during processing</li>
 * </ul>
 */
public class DocumentProcessingException extends Exception {

    /**
     * Constructs a new DocumentProcessingException with the specified detail message.
     *
     * @param message the detail message explaining the error
     */
    public DocumentProcessingException(String message) {
        super(message);
    }

    /**
     * Constructs a new DocumentProcessingException with the specified detail message and cause.
     *
     * <p>This constructor is particularly useful for wrapping underlying exceptions
     * while providing additional context about the document processing failure.</p>
     *
     * @param message the detail message explaining the error
     * @param cause   the cause of this exception (which is saved for later retrieval)
     */
    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new DocumentProcessingException with the specified cause.
     *
     * <p>The detail message is automatically derived from the cause's toString() method.</p>
     *
     * @param cause the cause of this exception (which is saved for later retrieval)
     */
    public DocumentProcessingException(Throwable cause) {
        super(cause);
    }
}