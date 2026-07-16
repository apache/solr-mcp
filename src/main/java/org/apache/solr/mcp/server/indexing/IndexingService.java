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
 * This service handles the conversion of JSON, CSV, and XML documents into
 * Solr-compatible format and manages the indexing process with robust error
 * handling and batch processing capabilities. It employs a schema-less approach
 * where Solr automatically detects field types, eliminating the need for
 * predefined schema configuration.
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
	 * Indexes documents from a JSON string into a specified Solr collection.
	 *
	 * <p>
	 * This method serves as the primary entry point for document indexing
	 * operations and is exposed as an MCP tool for AI client interactions. It
	 * processes JSON data containing document arrays and indexes them using a
	 * schema-less approach.
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
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-json-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index documents from json String into Solr collection. Field names are"
					+ " sanitized for Solr compatibility (lowercased, special characters replaced"
					+ " with underscores); the response lists the field names as indexed")
	public String indexJsonDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "JSON string containing documents to index") String json)
			throws IOException, SolrServerException {
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
	 * operations and is exposed as an MCP tool for AI client interactions. It
	 * processes CSV data with headers and indexes them using a schema-less
	 * approach.
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
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-csv-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index documents from CSV string into Solr collection. Column names are"
					+ " sanitized for Solr compatibility (lowercased, special characters replaced"
					+ " with underscores); the response lists the field names as indexed")
	public String indexCsvDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "CSV string containing documents to index") String csv)
			throws IOException, SolrServerException {
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
	 * operations and is exposed as an MCP tool for AI client interactions. It
	 * processes XML data with nested elements and attributes, indexing them using a
	 * schema-less approach.
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
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-xml-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index documents from XML string into Solr collection. Element names are"
					+ " sanitized for Solr compatibility (lowercased, special characters replaced"
					+ " with underscores); the response lists the field names as indexed")
	public String indexXmlDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "XML string containing documents to index") String xml)
			throws ParserConfigurationException, SAXException, IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromXml(xml);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'" + describeIndexedFields(schemalessDoc);
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
				// Try indexing documents individually to identify problematic ones
				for (SolrInputDocument doc : batch) {
					try {
						solrClient.add(collection, doc);
						successCount++;
					} catch (SolrServerException | IOException | RuntimeException _) {
						// Document failed to index - this is expected behavior for problematic
						// documents
						// We continue processing the rest of the batch
					}
				}
			}
		}

		solrClient.commit(collection);
		return successCount;
	}

	/**
	 * Maps an input-format keyword to the MCP tool and payload parameter for that
	 * format.
	 */
	private record IndexTool(String name, String paramName) {
	}

	private static IndexTool resolveIndexTool(String format) {
		String normalized = (format == null) ? "" : format.trim().toLowerCase();
		return switch (normalized) {
			case "json" -> new IndexTool("index-json-documents", "json");
			case "csv" -> new IndexTool("index-csv-documents", "csv");
			case "xml" -> new IndexTool("index-xml-documents", "xml");
			default -> throw new IllegalArgumentException("format must be one of json/csv/xml, got: " + format);
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
					description = "Document format: 'json', 'csv', or 'xml'",
					required = true) String format,
			@McpArg(
					name = "sample",
					description = "Optional small sample of the input document(s) to ground field-shape decisions",
					required = false) String sample) {
		IndexTool indexTool = resolveIndexTool(format);
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
				   - Call `%s` with `collection=%s` and `%s=<the document payload>`.
				   - The tool batches internally and commits at the end. The return value is the count
				     of successfully indexed documents.
				   - On error, read the message carefully: an "unknown field" error means the schema is
				     missing a field — go back to step 1 and run `design-schema`. A parse error means
				     the input format does not match the chosen tool — fix the payload and retry.

				4. Verify the count.
				   - Call `check-health` on `%s` and confirm the reported doc count increased by the
				     expected amount, OR call `search` with `query=*:*` and `rows=0` and read
				     `numFound`.

				Next step suggestion: once data is indexed, the `search-collection` prompt drives
				searching it.
				""".formatted(indexTool.paramName(), collection, collection, sampleSection, indexTool.name(),
				collection, indexTool.paramName(), collection);
	}
}
