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

/**
 * Exception thrown when document processing operations fail.
 *
 * <p>This exception provides a unified error handling mechanism for all document creator
 * implementations, wrapping various underlying exceptions while preserving the original error
 * context and stack trace information.
 *
 * <p>Common scenarios where this exception is thrown:
 *
 * <ul>
 *   <li>Invalid document format or structure
 *   <li>Document parsing errors (JSON, XML, CSV)
 *   <li>Input validation failures
 *   <li>Resource access or I/O errors during processing
 * </ul>
 */
public class DocumentProcessingException extends RuntimeException {

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
     * <p>This constructor is particularly useful for wrapping underlying exceptions while providing
     * additional context about the document processing failure.
     *
     * @param message the detail message explaining the error
     * @param cause the cause of this exception (which is saved for later retrieval)
     */
    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new DocumentProcessingException with the specified cause.
     *
     * <p>The detail message is automatically derived from the cause's toString() method.
     *
     * @param cause the cause of this exception (which is saved for later retrieval)
     */
    public DocumentProcessingException(Throwable cause) {
        super(cause);
    }
}
