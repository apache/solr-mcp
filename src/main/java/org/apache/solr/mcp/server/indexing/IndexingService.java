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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.ContentStreamBase;
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
 * <li><strong>CSV Processing</strong>: CSV with a header row, forwarded as
 * given to Solr's CSV update handler
 * <li><strong>XML Processing</strong>: Solr update XML ({@code <add>} blocks),
 * forwarded to Solr's XML update handler
 * <li><strong>Markdown Processing</strong>: Support for markdown documents with
 * front matter, title, and heading extraction
 * <li><strong>Batch Processing</strong>: Efficient bulk indexing with
 * configurable batch sizes
 * <li><strong>Error Resilience</strong>: Individual document fallback when
 * batch operations fail
 * <li><strong>Field Names</strong>: Used as given in every format; nested JSON
 * objects are flattened with underscores
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
	 *            the orchestrator that parses JSON and markdown input into
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
	 * @param documents
	 *            the documents to index, one map per document
	 * @return a human-readable summary reporting how many documents were
	 *         successfully indexed
	 * @throws IOException
	 *             if there are critical errors in JSON parsing or Solr
	 *             communication
	 * @throws SolrServerException
	 *             if Solr server encounters errors during indexing
	 * @see IndexingDocumentCreator#createSchemalessDocumentsFromJson(List)
	 * @see #indexDocuments(String, List)
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-json-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index documents passed as a JSON array of objects into Solr collection; one object"
					+ " per document, multi-valued fields as arrays, nested objects flattened with underscores."
					+ " Pass the array itself, not a JSON string. Field names are used as given; the response"
					+ " lists the field names as indexed")
	public String indexJsonDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(
					description = "Documents to index: a JSON array with one object per document") List<Map<String, Object>> documents)
			throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromJson(documents);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'" + describeIndexedFields(schemalessDoc);
	}

	/**
	 * Indexes CSV rows into a Solr collection by forwarding the payload, as given,
	 * to Solr's own CSV update handler. Solr reads the header row for the field
	 * names and parses the rows; the server does not inspect the payload. A column
	 * name repeated in the header yields a multi-valued field, and empty cells are
	 * skipped. Solr accepts or rejects the payload as a whole.
	 *
	 * @param collection
	 *            the name of the Solr collection to index documents into
	 * @param csv
	 *            CSV text with a header row
	 * @return a human-readable confirmation that Solr accepted and committed the
	 *         payload
	 * @throws IOException
	 *             if communication with Solr fails
	 * @throws SolrServerException
	 *             if Solr rejects the payload
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-csv-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index documents from CSV string into Solr collection via Solr's CSV handler. The first row"
					+ " is the header and its column names are used as the field names, as given; repeat a column name"
					+ " to make that field multi-valued; empty cells are skipped")
	public String indexCsvDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "CSV string containing documents to index") String csv)
			throws IOException, SolrServerException {
		ContentStreamUpdateRequest request = new ContentStreamUpdateRequest("/update");
		request.setParam("header", "true");
		request.addContentStream(new ContentStreamBase.StringStream(csv, "text/csv; charset=UTF-8"));
		return forward(collection, request, "CSV payload");
	}

	/**
	 * Indexes documents supplied in Solr's update XML format
	 * ({@code <add><doc><field name="...">...</field></doc></add>}) by forwarding
	 * the payload to Solr's update handler. The only server-side step is
	 * {@link SolrUpdateXml}: the same grammar carries {@code <delete>} and
	 * {@code <commit>} commands that an indexing tool must not forward, so the root
	 * element must be {@code <add>}. Solr parses the payload and accepts or rejects
	 * it as a whole.
	 *
	 * @param collection
	 *            the name of the Solr collection to index documents into
	 * @param xml
	 *            a Solr {@code <add>} block
	 * @return a human-readable confirmation that Solr accepted and committed the
	 *         payload
	 * @throws IOException
	 *             if communication with Solr fails
	 * @throws SolrServerException
	 *             if Solr rejects the payload
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-xml-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index documents from Solr update XML into Solr collection: <add><doc><field name=\"id\">1</field>"
					+ "<field name=\"genres\">a</field><field name=\"genres\">b</field></doc></add>; repeat <field> for"
					+ " multi-valued fields. Only <add> blocks are accepted; delete and commit commands are rejected."
					+ " Field names are used as given")
	public String indexXmlDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "Solr update XML: an <add> block of <doc> elements") String xml)
			throws IOException, SolrServerException {
		SolrUpdateXml.requireAddBlock(xml);
		ContentStreamUpdateRequest request = new ContentStreamUpdateRequest("/update");
		request.addContentStream(new ContentStreamBase.StringStream(xml, ClientUtils.TEXT_XML));
		return forward(collection, request, "XML <add> block");
	}

	/**
	 * Sends a payload to a Solr update handler and reports Solr's answer. The
	 * commit rides along on the same request rather than following as a second
	 * round trip, so the status and query time reported here cover the commit this
	 * message claims. Solr's update response carries no document count, so none is
	 * claimed.
	 *
	 * <p>
	 * The commit is a soft one: {@code waitSearcher} keeps the documents searchable
	 * the moment the tool returns, while the segment fsync is left to Solr's
	 * {@code autoCommit}, which the {@code _default} configset enables at 15 s, so
	 * many small calls do not each force one.
	 */
	private String forward(String collection, ContentStreamUpdateRequest request, String payload)
			throws IOException, SolrServerException {
		request.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, true, true);
		UpdateResponse response = request.process(solrClient, collection);
		return "Solr accepted the " + payload + " for collection '" + collection + "' and committed it (status "
				+ response.getStatus() + ", " + response.getQTime() + " ms)";
	}

	/**
	 * Indexes a document from a markdown string into a specified Solr collection.
	 *
	 * <p>
	 * This method serves as the primary entry point for markdown document indexing
	 * operations and is exposed as an MCP tool for AI client interactions. Unlike
	 * the structured formats (JSON, CSV, XML), markdown is a prose format, so
	 * searchable structure is extracted from the document content itself.
	 *
	 * <p>
	 * <strong>Field Extraction:</strong>
	 *
	 * <ul>
	 * <li><strong>YAML Front Matter</strong>: Each entry becomes a document field
	 * under its own name (multi-valued where applicable)
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
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-markdown-documents",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index a document from markdown String into Solr collection, extracting front matter, title, headings, and body text. "
					+ "Do NOT use for JSON/CSV/XML input; use index-json-documents, index-csv-documents, or index-xml-documents instead. "
					+ "Only convert source content to markdown when there is no dedicated tool for the source format, and supply a stable 'id' in the YAML front matter when doing so.")
	public String indexMarkdownDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(
					description = "Markdown string to index, optionally starting with YAML front matter") String markdown)
			throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown);
		int successCount = indexDocuments(collection, schemalessDoc);
		return "Successfully indexed " + successCount + " of " + schemalessDoc.size() + " documents into collection '"
				+ collection + "'";
	}

	/**
	 * Maximum number of distinct field names listed in an indexing response before
	 * the remainder is elided.
	 */
	private static final int MAX_REPORTED_FIELDS = 50;

	/**
	 * Summarizes the field names that were indexed. Nested JSON objects are
	 * flattened with underscores, so the indexed names can differ from the input;
	 * reporting them lets MCP clients query the right fields.
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
		return ". Indexed field names: " + listed + elided;
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
	 * <li><strong>Commit Strategy</strong>: Single soft commit after all batches
	 * for consistency
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
	 * The method soft-commits after all batches are processed, making indexed
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
	 * @see SolrClient#commit(String, boolean, boolean, boolean)
	 */
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
			// waitFlush=false, waitSearcher=true, softCommit=true: the documents are
			// searchable when this method returns, while the hard commit (segment fsync)
			// is left to Solr's autoCommit, so many small calls do not each force one.
			solrClient.commit(collection, false, true, true);
		} catch (SolrServerException | IOException e) {
			logger.error("Failed to commit after indexing to collection: {}", collection, e);
			throw e;
		}
		return successCount;
	}

	/**
	 * Maps an input-format keyword to the canonical format name, the MCP tool, and
	 * the payload parameter for that format.
	 *
	 * @param format
	 *            canonical format name, so the prompt reads "markdown" even when
	 *            the caller passed the {@code md} alias
	 * @param name
	 *            the MCP tool that indexes this format
	 * @param paramName
	 *            the tool's payload parameter name
	 * @param payload
	 *            prose describing what to pass for {@code paramName}
	 */
	private record IndexTool(String format, String name, String paramName, String payload) {
	}

	private static IndexTool resolveIndexTool(String format) {
		String normalized = (format == null) ? "" : format.trim().toLowerCase();
		return switch (normalized) {
			case "json" -> new IndexTool("json", "index-json-documents", "documents",
					"the documents as a JSON array of objects, not as a string");
			case "csv" -> new IndexTool("csv", "index-csv-documents", "csv", "the CSV text");
			case "xml" -> new IndexTool("xml", "index-xml-documents", "xml", "the XML text");
			case "markdown", "md" ->
				new IndexTool("markdown", "index-markdown-documents", "markdown", "the markdown text");
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
				   - Call `%s` with `collection=%s` and `%s=<%s>`.
				   - The tool commits at the end. For JSON and markdown the return value is the count
				     of successfully indexed documents; for CSV and XML it confirms that Solr accepted
				     the whole payload, and step 4 is where you learn the count.
				   - On error, read the message carefully: an "unknown field" error means the schema is
				     missing a field — go back to step 1 and run `design-schema`. A parse error means
				     the input format does not match the chosen tool — fix the payload and retry.

				4. Verify the count.
				   - Call `check-health` on `%s` and confirm the reported doc count increased by the
				     expected amount, OR call `search` with `query=*:*` and `rows=0` and read
				     `numFound`.

				Next step suggestion: once data is indexed, the `search-collection` prompt drives
				searching it.
				""".formatted(indexTool.format(), collection, collection, sampleSection, indexTool.name(), collection,
				indexTool.paramName(), indexTool.payload(), collection);
	}
}
