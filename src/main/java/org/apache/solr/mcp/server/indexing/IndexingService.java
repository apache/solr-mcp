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
package org.apache.solr.mcp.server.indexing;

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.apache.solr.mcp.server.util.PromptNames;
import org.apache.solr.mcp.server.util.PromptText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

/**
 * Spring Service providing comprehensive document indexing capabilities for
 * Apache Solr collections through Model Context Protocol (MCP) integration.
 *
 * <p>
 * This service handles the conversion of JSON, CSV, XML, and markdown documents
 * into Solr-compatible format and manages the indexing process with robust
 * error handling and batch processing capabilities. It employs a schema-less
 * approach where Solr automatically detects field types, eliminating the need
 * for predefined schema configuration.
 *
 * <p>
 * <strong>Core Features:</strong>
 *
 * <ul>
 * <li><strong>Schema-less Indexing</strong>: Automatic field type detection by
 * Solr
 * <li><strong>JSON Processing</strong>: Support for complex nested JSON
 * documents
 * <li><strong>CSV Processing</strong>: Support for comma-separated value files
 * with headers
 * <li><strong>XML Processing</strong>: Support for XML documents with element
 * flattening and attribute handling
 * <li><strong>Markdown Processing</strong>: Support for markdown documents with
 * front matter, title, and heading extraction
 * <li><strong>Batch Processing</strong>: Efficient bulk indexing with
 * configurable batch sizes
 * <li><strong>Error Resilience</strong>: Individual document fallback when
 * batch operations fail
 * <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for
 * Solr compatibility
 * </ul>
 *
 * <p>
 * <strong>MCP Tool Integration:</strong>
 *
 * <p>
 * The service exposes indexing functionality as MCP tools that can be invoked
 * by AI clients through natural language requests. This enables seamless
 * document ingestion workflows from external data sources.
 *
 * <p>
 * <strong>JSON Document Processing:</strong>
 *
 * <p>
 * The service processes JSON documents by flattening nested objects using
 * underscore notation (e.g., "user.name" becomes "user_name") and handles
 * arrays by converting them to multi-valued fields that Solr natively supports.
 *
 * <p>
 * <strong>Batch Processing Strategy:</strong>
 *
 * <p>
 * Uses configurable batch sizes (default 1000 documents) for optimal
 * performance. If a batch fails, the service automatically retries by indexing
 * documents individually to identify and skip problematic documents while
 * preserving valid ones.
 *
 * <p>
 * <strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // Index JSON array of documents
 * String jsonData = "[{\"title\":\"Document 1\",\"content\":\"Content here\"}]";
 * indexingService.indexDocuments("my_collection", jsonData);
 *
 * // Programmatic document creation and indexing
 * List<SolrInputDocument> docs = indexingService.createSchemalessDocuments(jsonData);
 * int successful = indexingService.indexDocuments("my_collection", docs);
 * }</pre>
 *
 * @see SolrInputDocument
 * @see SolrClient
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Service
@Observed
public class IndexingService {

	private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);

	private static final int DEFAULT_BATCH_SIZE = 1000;

	/** SolrJ client for communicating with Solr server */
	private final SolrClient solrClient;

	/** Service for creating SolrInputDocument objects from various data formats */
	private final IndexingDocumentCreator indexingDocumentCreator;

	/**
	 * Constructs a new IndexingService with the required dependencies.
	 *
	 * <p>
	 * This constructor is automatically called by Spring's dependency injection
	 * framework during application startup, providing the service with the
	 * necessary Solr client and configuration components.
	 *
	 * @param solrClient
	 *            the SolrJ client instance for communicating with Solr
	 * @param indexingDocumentCreator
	 *            the orchestrator that parses JSON, CSV, and XML input into
	 *            {@code SolrInputDocument} batches
	 * @see SolrClient
	 */
	public IndexingService(SolrClient solrClient, IndexingDocumentCreator indexingDocumentCreator) {
		this.solrClient = solrClient;
		this.indexingDocumentCreator = indexingDocumentCreator;
	}

	/**
	 * Indexes documents supplied inline as a string, selecting the parser by
	 * {@code format}. One tool with a format argument replaces four near-identical
	 * tool schemas in every session's catalog. The format is explicit rather than
	 * sniffed: inline payloads have no filename, and CSV and Markdown are both
	 * plain text with no safe distinguishing prefix.
	 *
	 * @param collection
	 *            the name of the Solr collection to index into
	 * @param content
	 *            the documents, as a string in the given format
	 * @param format
	 *            one of {@code json}, {@code csv}, {@code xml}, {@code markdown}
	 *            (alias {@code md}); case-insensitive
	 * @return a summary of how many documents were indexed and the field names as
	 *         indexed
	 * @throws IllegalArgumentException
	 *             if the format is missing or not one of the accepted values
	 * @throws IOException
	 *             if there are I/O errors during Solr communication
	 * @throws SolrServerException
	 *             if there are Solr-specific errors during indexing
	 * @throws ParserConfigurationException
	 *             if the XML parser cannot be configured
	 * @throws SAXException
	 *             if the XML content is malformed
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "index-documents", annotations = @McpTool.McpAnnotations(idempotentHint = true), description = """
			Index documents supplied inline into a Solr collection. Set format to json (an array of objects
			or a single object), csv (first row is the header), xml (Solr <add><doc> or generic elements),
			or markdown (one document; front matter, title, headings and body are extracted; supply a
			stable 'id' in the YAML front matter). Only convert source content to markdown when it is not
			already JSON, CSV or XML. Field names are sanitized for Solr compatibility (lowercased, special
			characters replaced with underscores); the response lists the field names as indexed.
			""")
	public String indexDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "The documents, as a string in the given format") String content,
			@McpToolParam(description = "Format of content: json, csv, xml or markdown (alias md)") String format)
			throws IOException, SolrServerException, ParserConfigurationException, SAXException {
		return switch (normalizeFormat(format)) {
			case "json" -> indexJsonDocuments(collection, content);
			case "csv" -> indexCsvDocuments(collection, content);
			case "xml" -> indexXmlDocuments(collection, content);
			default -> indexMarkdownDocuments(collection, content);
		};
	}

	/**
	 * Indexes documents from a JSON string into a specified Solr collection.
	 *
	 * <p>
	 * This method serves as the primary entry point for document indexing
	 * operations from Java; MCP clients use the {@code index-documents} tool with
	 * the matching {@code format}. It processes JSON data containing document
	 * arrays and indexes them using a schema-less approach.
	 *
	 * <p>
	 * <strong>Supported JSON Formats:</strong>
	 *
	 * <ul>
	 * <li><strong>Document Array</strong>:
	 * {@code [{"field1":"value1"},{"field2":"value2"}]}
	 * <li><strong>Nested Objects</strong>: Automatically flattened with underscore
	 * notation
	 * <li><strong>Multi-valued Fields</strong>: Arrays converted to Solr
	 * multi-valued fields
	 * </ul>
	 *
	 * <p>
	 * <strong>Processing Workflow:</strong>
	 *
	 * <ol>
	 * <li>Parse JSON string into structured documents
	 * <li>Convert to schema-less SolrInputDocument objects
	 * <li>Execute batch indexing with error handling
	 * <li>Commit changes to make documents searchable
	 * </ol>
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * AI clients can invoke this method with natural language requests like "index
	 * these documents into my_collection" or "add this JSON data to the search
	 * index".
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * If indexing fails, the method attempts individual document processing to
	 * maximize the number of successfully indexed documents. Detailed error
	 * information is logged for troubleshooting purposes.
	 *
	 * @param collection
	 *            the name of the Solr collection to index documents into
	 * @param json
	 *            JSON string containing an array of documents to index
	 * @return a human-readable summary reporting how many documents were
	 *         successfully indexed
	 * @throws IOException
	 *             if there are critical errors in JSON parsing or Solr
	 *             communication
	 * @throws SolrServerException
	 *             if Solr server encounters errors during indexing
	 * @see IndexingDocumentCreator#createSchemalessDocumentsFromJson(String)
	 * @see #indexDocuments(String, List)
	 */
	public String indexJsonDocuments(String collection, String json) throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromJson(json);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'" + describeIndexedFields(schemalessDoc);
	}

	/**
	 * Indexes documents from a CSV string into a specified Solr collection.
	 *
	 * <p>
	 * This method serves as the primary entry point for CSV document indexing
	 * operations from Java; MCP clients use the {@code index-documents} tool with
	 * the matching {@code format}. It processes CSV data with headers and indexes
	 * them using a schema-less approach.
	 *
	 * <p>
	 * <strong>Supported CSV Formats:</strong>
	 *
	 * <ul>
	 * <li><strong>Header Row Required</strong>: First row must contain column names
	 * <li><strong>Comma Delimited</strong>: Standard CSV format with comma
	 * separators
	 * <li><strong>Mixed Data Types</strong>: Automatic type detection by Solr
	 * </ul>
	 *
	 * <p>
	 * <strong>Processing Workflow:</strong>
	 *
	 * <ol>
	 * <li>Parse CSV string to extract headers and data rows
	 * <li>Convert to schema-less SolrInputDocument objects
	 * <li>Execute batch indexing with error handling
	 * <li>Commit changes to make documents searchable
	 * </ol>
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * AI clients can invoke this method with natural language requests like "index
	 * this CSV data into my_collection" or "add these CSV records to the search
	 * index".
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * If indexing fails, the method attempts individual document processing to
	 * maximize the number of successfully indexed documents. Detailed error
	 * information is logged for troubleshooting purposes.
	 *
	 * @param collection
	 *            the name of the Solr collection to index documents into
	 * @param csv
	 *            CSV string containing documents to index (first row must be
	 *            headers)
	 * @return a human-readable summary reporting how many documents were
	 *         successfully indexed
	 * @throws IOException
	 *             if there are critical errors in CSV parsing or Solr communication
	 * @throws SolrServerException
	 *             if Solr server encounters errors during indexing
	 * @see IndexingDocumentCreator#createSchemalessDocumentsFromCsv(String)
	 * @see #indexDocuments(String, List)
	 */
	public String indexCsvDocuments(String collection, String csv) throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromCsv(csv);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'" + describeIndexedFields(schemalessDoc);
	}

	/**
	 * Indexes documents from an XML string into a specified Solr collection.
	 *
	 * <p>
	 * This method serves as the primary entry point for XML document indexing
	 * operations from Java; MCP clients use the {@code index-documents} tool with
	 * the matching {@code format}. It processes XML data with nested elements and
	 * attributes, indexing them using a schema-less approach.
	 *
	 * <p>
	 * <strong>Supported XML Formats:</strong>
	 *
	 * <ul>
	 * <li><strong>Single Document</strong>: Root element treated as one document
	 * <li><strong>Multiple Documents</strong>: Child elements with 'doc', 'item',
	 * or 'record' names treated as separate documents
	 * <li><strong>Nested Elements</strong>: Automatically flattened with underscore
	 * notation
	 * <li><strong>Attributes</strong>: Converted to fields with "_attr" suffix
	 * <li><strong>Mixed Data Types</strong>: Automatic type detection by Solr
	 * </ul>
	 *
	 * <p>
	 * <strong>Processing Workflow:</strong>
	 *
	 * <ol>
	 * <li>Parse XML string to extract elements and attributes
	 * <li>Flatten nested structures using underscore notation
	 * <li>Convert to schema-less SolrInputDocument objects
	 * <li>Execute batch indexing with error handling
	 * <li>Commit changes to make documents searchable
	 * </ol>
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * AI clients can invoke this method with natural language requests like "index
	 * this XML data into my_collection" or "add these XML records to the search
	 * index".
	 *
	 * <p>
	 * <strong>Error Handling:</strong>
	 *
	 * <p>
	 * If indexing fails, the method attempts individual document processing to
	 * maximize the number of successfully indexed documents. Detailed error
	 * information is logged for troubleshooting purposes.
	 *
	 * <p>
	 * <strong>Example XML Processing:</strong>
	 *
	 * <pre>{@code
	 * Input:
	 * <documents>
	 *   <document id="1">
	 *     <title>Sample</title>
	 *     <author>
	 *       <name>John Doe</name>
	 *     </author>
	 *   </document>
	 * </documents>
	 *
	 * Result: {id_attr:"1", title:"Sample", author_name:"John Doe"}
	 * }</pre>
	 *
	 * @param collection
	 *            the name of the Solr collection to index documents into
	 * @param xml
	 *            XML string containing documents to index
	 * @return a human-readable summary reporting how many documents were
	 *         successfully indexed
	 * @throws ParserConfigurationException
	 *             if XML parser configuration fails
	 * @throws SAXException
	 *             if XML parsing fails due to malformed content
	 * @throws IOException
	 *             if I/O errors occur during parsing or Solr communication
	 * @throws SolrServerException
	 *             if Solr server encounters errors during indexing
	 * @see IndexingDocumentCreator#createSchemalessDocumentsFromXml(String)
	 * @see #indexDocuments(String, List)
	 */
	public String indexXmlDocuments(String collection, String xml)
			throws ParserConfigurationException, SAXException, IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromXml(xml);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'" + describeIndexedFields(schemalessDoc);
	}

	/**
	 * Indexes a document from a markdown string into a specified Solr collection.
	 *
	 * <p>
	 * This method serves as the primary entry point for markdown document indexing
	 * operations from Java; MCP clients use the {@code index-documents} tool with
	 * {@code format=markdown}. Unlike the structured formats (JSON, CSV, XML),
	 * markdown is a prose format, so searchable structure is extracted from the
	 * document content itself.
	 *
	 * <p>
	 * <strong>Field Extraction:</strong>
	 *
	 * <ul>
	 * <li><strong>YAML Front Matter</strong>: Each entry becomes a document field
	 * with a sanitized name (multi-valued where applicable)
	 * <li><strong>title</strong>: From the {@code title} front matter entry, or the
	 * first level-1 heading
	 * <li><strong>headings</strong>: Multi-valued field with the text of every
	 * heading (the document outline)
	 * <li><strong>content</strong>: Plain text body for full-text search (front
	 * matter excluded)
	 * </ul>
	 *
	 * <p>
	 * <strong>MCP Tool Usage:</strong>
	 *
	 * <p>
	 * AI clients can invoke this method with natural language requests like "index
	 * this markdown file into my_collection" or "add this README to the search
	 * index".
	 *
	 * <p>
	 * <strong>Example Markdown Processing:</strong>
	 *
	 * <pre>{@code
	 * Input:
	 * ---
	 * author: Jane Doe
	 * ---
	 * # Getting Started
	 * Run the installer.
	 *
	 * Result: {author:"Jane Doe", title:"Getting Started",
	 *          headings:["Getting Started"], content:"Getting Started\nRun the installer."}
	 * }</pre>
	 *
	 * @param collection
	 *            the name of the Solr collection to index documents into
	 * @param markdown
	 *            markdown string to index, optionally starting with YAML front
	 *            matter
	 * @throws IOException
	 *             if there are critical errors in Solr communication
	 * @throws SolrServerException
	 *             if Solr server encounters errors during indexing
	 * @see IndexingDocumentCreator#createSchemalessDocumentsFromMarkdown(String)
	 * @see #indexDocuments(String, List)
	 */
	public String indexMarkdownDocuments(String collection, String markdown) throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'";
	}

	/**
	 * Indexes a list of SolrInputDocument objects into a Solr collection using
	 * batch processing.
	 *
	 * <p>
	 * This method implements a robust batch indexing strategy that optimizes
	 * performance while providing resilience against individual document failures.
	 * It processes documents in configurable batches and includes fallback
	 * mechanisms for error recovery.
	 *
	 * <p>
	 * <strong>Batch Processing Strategy:</strong>
	 *
	 * <ul>
	 * <li><strong>Batch Size</strong>: Configurable (default 1000) for optimal
	 * performance
	 * <li><strong>Error Recovery</strong>: Individual document retry on batch
	 * failure
	 * <li><strong>Success Tracking</strong>: Accurate count of successfully indexed
	 * documents
	 * <li><strong>Commit Strategy</strong>: Single commit after all batches for
	 * consistency
	 * </ul>
	 *
	 * <p>
	 * <strong>Error Handling Workflow:</strong>
	 *
	 * <ol>
	 * <li>Attempt batch indexing for optimal performance
	 * <li>On batch failure, retry each document individually
	 * <li>Track successful vs failed document counts
	 * <li>Continue processing remaining batches despite failures
	 * <li>Commit all successful changes at the end
	 * </ol>
	 *
	 * <p>
	 * <strong>Performance Considerations:</strong>
	 *
	 * <p>
	 * Batch processing significantly improves indexing performance compared to
	 * individual document operations. The fallback to individual processing ensures
	 * maximum document ingestion even when some documents have issues.
	 *
	 * <p>
	 * <strong>Transaction Behavior:</strong>
	 *
	 * <p>
	 * The method commits changes after all batches are processed, making indexed
	 * documents immediately searchable. This ensures atomicity at the operation
	 * level while maintaining performance through batching.
	 *
	 * @param collection
	 *            the name of the Solr collection to index into
	 * @param documents
	 *            list of SolrInputDocument objects to index
	 * @return the number of documents successfully indexed
	 * @throws SolrServerException
	 *             if there are critical errors in Solr communication
	 * @throws IOException
	 *             if there are critical errors in commit operations
	 * @see SolrInputDocument
	 * @see SolrClient#add(String, java.util.Collection)
	 * @see SolrClient#commit(String)
	 */
	/**
	 * Maximum number of distinct field names listed in an indexing response before
	 * the remainder is elided.
	 */
	private static final int MAX_REPORTED_FIELDS = 50;

	/**
	 * Summarizes the field names that were actually indexed. Document creators
	 * sanitize input field names for Solr compatibility (lowercasing, replacing
	 * special characters with underscores), so the indexed names can differ from
	 * the input; reporting them lets MCP clients query the right fields instead of
	 * assuming the input names survived.
	 *
	 * @param documents
	 *            the documents that were submitted for indexing
	 * @return a sentence listing the distinct indexed field names, or an empty
	 *         string if there are none
	 */
	private static String describeIndexedFields(List<SolrInputDocument> documents) {
		Set<String> fieldNames = documents.stream().flatMap(document -> document.getFieldNames().stream())
				.collect(Collectors.toCollection(TreeSet::new));
		if (fieldNames.isEmpty()) {
			return "";
		}
		String listed = fieldNames.stream().limit(MAX_REPORTED_FIELDS).collect(Collectors.joining(", "));
		String elided = fieldNames.size() > MAX_REPORTED_FIELDS
				? " and " + (fieldNames.size() - MAX_REPORTED_FIELDS) + " more"
				: "";
		return ". Indexed field names (input names are sanitized for Solr compatibility): " + listed + elided;
	}

	public int indexDocuments(String collection, List<SolrInputDocument> documents)
			throws SolrServerException, IOException {
		int successCount = 0;
		final int batchSize = DEFAULT_BATCH_SIZE;

		for (int i = 0; i < documents.size(); i += batchSize) {
			final int endIndex = Math.min(i + batchSize, documents.size());
			final List<SolrInputDocument> batch = documents.subList(i, endIndex);

			try {
				solrClient.add(collection, batch);
				successCount += batch.size();
			} catch (SolrServerException | IOException | RuntimeException e) {
				logger.warn("Batch indexing failed, retrying individually", e);
				// Try indexing documents individually to identify problematic ones
				for (SolrInputDocument doc : batch) {
					try {
						solrClient.add(collection, doc);
						successCount++;
					} catch (SolrServerException | IOException | RuntimeException e2) {
						logger.debug("Failed to index individual document", e2);
						// Document failed to index - this is expected behavior for problematic
						// documents
						// We continue processing the rest of the batch
					}
				}
			}
		}

		try {
			solrClient.commit(collection);
		} catch (SolrServerException | IOException e) {
			logger.error("Failed to commit after indexing to collection: {}", collection, e);
			throw e;
		}
		return successCount;
	}

	/**
	 * Normalizes a user-supplied format keyword to the canonical value accepted by
	 * {@code index-documents}.
	 *
	 * @param format
	 *            {@code json}, {@code csv}, {@code xml}, {@code markdown} or
	 *            {@code md}, in any case, with surrounding whitespace ignored
	 * @return {@code json}, {@code csv}, {@code xml} or {@code markdown}
	 * @throws IllegalArgumentException
	 *             if the format is null, blank or unrecognized
	 */
	static String normalizeFormat(String format) {
		String normalized = (format == null) ? "" : format.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "json", "csv", "xml" -> normalized;
			case "markdown", "md" -> "markdown";
			default ->
				throw new IllegalArgumentException("format must be one of json/csv/xml/markdown, got: " + format);
		};
	}

	/**
	 * MCP prompt that guides the client through indexing documents: verify the
	 * target schema, pick the right indexing tool for the input format, and confirm
	 * the result.
	 *
	 * @param collection
	 *            target Solr collection name
	 * @param format
	 *            document format; one of {@code json}, {@code csv}, or {@code xml}
	 * @param sample
	 *            optional small sample of the input document(s) to ground
	 *            field-shape decisions; may be {@code null} or blank
	 * @return the prompt text instructing the model how to index the documents
	 */
	@PreAuthorize("isAuthenticated()")

	@McpPrompt(
			name = PromptNames.INDEX_DATA,
			title = "Index documents into a Solr collection",
			description = "Guides the assistant through verifying the target schema, picking the right indexing tool for the input format, and confirming the result.")
	public String indexDataPrompt(
			@McpArg(
					name = "collection",
					description = "Target Solr collection name",
					required = true) String collection,
			@McpArg(
					name = "format",
					description = "Document format: 'json', 'csv', 'xml', or 'markdown'",
					required = true) String format,
			@McpArg(
					name = "sample",
					description = "Optional small sample of the input document(s) to ground field-shape decisions",
					required = false) String sample) {
		String normalizedFormat = normalizeFormat(format);
		String sampleSection = PromptText.optionalCodeBlock(sample, "Sample input:",
				"No sample was provided. If the user has not pasted the documents yet, ask for them (or a representative subset) before indexing.");
		return """
				You are indexing %s data into collection `%s` via MCP tools. Work incrementally and
				verify after each step.

				1. Confirm the schema is ready.
				   - Call `get-schema` on `%s`. Confirm the fields the input references exist with
				     compatible types. If fields are missing or typed wrong, pause and run the
				     `design-schema` prompt to add them — indexing into a collection without the right
				     fields either fails or silently falls back to schemaless behavior, which can
				     pollute the configset.

				2. Inspect the input.
				%s

				3. Index the documents.
				   - Call `index-documents` with `collection=%s`, `format=%s` and
				     `content=<the document payload>`.
				   - The tool batches internally and commits at the end. The return value is the count
				     of successfully indexed documents.
				   - On error, read the message carefully: an "unknown field" error means the schema is
				     missing a field — go back to step 1 and run `design-schema`. A parse error means
				     the input does not match `format` — fix the payload or the format and retry.

				4. Verify the count.
				   - Call `check-health` on `%s` and confirm the reported doc count increased by the
				     expected amount, OR call `search` with `query=*:*` and `rows=0` and read
				     `numFound`.

				Next step suggestion: once data is indexed, the `search-collection` prompt drives
				searching it.
				""".formatted(normalizedFormat, collection, collection, sampleSection, collection, normalizedFormat,
				collection);
	}
}
