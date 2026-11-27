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
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.mcp.server.indexing.IndexingResult.Format;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.apache.solr.mcp.server.security.McpAuthorities;
import org.apache.solr.mcp.server.util.McpResults;
import org.apache.solr.mcp.server.util.PromptArguments;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Spring Service providing comprehensive document indexing capabilities for Apache Solr collections
 * through Model Context Protocol (MCP) integration.
 *
 * <p>This service handles the conversion of JSON, CSV, XML, and markdown documents into
 * Solr-compatible format and manages the indexing process with robust error handling. It employs a
 * schema-less approach where Solr automatically detects field types, eliminating the need for
 * predefined schema configuration.
 *
 * <p><strong>Core Features:</strong>
 *
 * <ul>
 *   <li><strong>Schema-less Indexing</strong>: Automatic field type detection by Solr
 *   <li><strong>JSON Processing</strong>: Support for complex nested JSON documents
 *   <li><strong>CSV Processing</strong>: CSV with a header row, forwarded as given to Solr's CSV
 *       update handler
 *   <li><strong>XML Processing</strong>: Solr update XML ({@code <add>} blocks), forwarded to
 *       Solr's XML update handler
 *   <li><strong>Markdown Processing</strong>: Support for markdown documents with front matter,
 *       title, and heading extraction
 *   <li><strong>Error Resilience</strong>: Individual document fallback when Solr rejects a
 *       document
 *   <li><strong>Field Names</strong>: Used as given in every format; nested JSON objects are
 *       flattened with underscores
 * </ul>
 *
 * <p><strong>MCP Tool Integration:</strong>
 *
 * <p>The service exposes indexing functionality as MCP tools that can be invoked by AI clients
 * through natural language requests. This enables seamless document ingestion workflows from
 * external data sources.
 *
 * <p><strong>JSON Document Processing:</strong>
 *
 * <p>The service processes JSON documents by flattening nested objects using underscore notation
 * (e.g., "user.name" becomes "user_name") and handles arrays by converting them to multi-valued
 * fields that Solr natively supports.
 *
 * <p><strong>Error Recovery:</strong>
 *
 * <p>JSON and markdown documents are sent in one update request. If Solr rejects it because of a
 * bad document, the service retries the documents individually to skip the problematic ones while
 * preserving valid ones.
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>{@code
 * // Index a JSON array of documents, one map per document
 * List<Map<String, Object>> documents = List.of(Map.of("id", "1", "title", "Document 1"));
 * IndexingResult result = indexingService.indexJsonDocuments("my_collection", documents);
 *
 * // Programmatic indexing of documents built elsewhere
 * List<SolrInputDocument> accepted = indexingService.indexDocuments("my_collection", docs);
 * }</pre>
 *
 * @see SolrInputDocument
 * @see SolrClient
 * @see org.springframework.ai.tool.annotation.Tool
 */
@Service
@Observed
public class IndexingService {

  /** The description of the {@code collection} parameter of every indexing tool. */
  private static final String COLLECTION_PARAM_DESCRIPTION = "Solr collection to index into";

  private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);

  /**
   * Name of the {@code index-data} prompt. Public because {@code CollectionService} binds its
   * {@code @McpComplete} handler to this name, and MCP matches the two by exact string, so both
   * sides must reference this constant.
   */
  public static final String INDEX_DATA_PROMPT = "index-data";

  /**
   * Description of the {@code index-data} prompt, shared by its {@code @McpPrompt} and its result.
   */
  private static final String INDEX_DATA_PROMPT_DESCRIPTION =
      "Guides the assistant through verifying the target schema, picking the right indexing tool for the input format, and confirming the result.";

  /** Solr's update handler, which reads CSV and update XML by their content type. */
  private static final String UPDATE_PATH = "/update";

  /** Maximum number of distinct field names listed in an indexing result. */
  static final int MAX_REPORTED_FIELDS = 50;

  /**
   * The part of the JSON and Markdown tool descriptions that describes the result. It is a
   * compile-time constant so the annotations can use it.
   */
  private static final String PARSED_RESULT_DESCRIPTION =
      "Returns documentsSubmitted, documentsIndexed (lower when Solr rejected some documents),"
          + " fieldNames (up to "
          + MAX_REPORTED_FIELDS
          + " field names of the indexed documents, in sort order) and fieldCount (how many"
          + " field names there are). Fails if Solr rejects every document.";

  /**
   * The error for a call whose documents Solr rejected one and all. It is a fixed phrase, so no
   * Solr text reaches the client; the per-document failures are logged.
   */
  static final String EVERY_DOCUMENT_REJECTED =
      "Solr rejected every document; check the field names and types against get-schema";

  /** Leads the error text of a failed indexing tool. */
  static final String INDEXING_FAILED = "Indexing failed";

  /** SolrJ client for communicating with Solr server */
  private final SolrClient solrClient;

  /** Service for creating SolrInputDocument objects from various data formats */
  private final IndexingDocumentCreator indexingDocumentCreator;

  /**
   * Constructs a new IndexingService with the required dependencies.
   *
   * <p>This constructor is automatically called by Spring's dependency injection framework during
   * application startup, providing the service with the necessary Solr client and configuration
   * components.
   *
   * @param solrClient the SolrJ client instance for communicating with Solr
   * @param indexingDocumentCreator the orchestrator that parses JSON and markdown input into {@code
   *     SolrInputDocument} batches
   * @see SolrClient
   */
  public IndexingService(SolrClient solrClient, IndexingDocumentCreator indexingDocumentCreator) {
    this.solrClient = solrClient;
    this.indexingDocumentCreator = indexingDocumentCreator;
  }

  /**
   * Indexes documents from a JSON string into a specified Solr collection.
   *
   * <p>This method serves as the primary entry point for document indexing operations and is
   * exposed as an MCP tool for AI client interactions. It processes JSON data containing document
   * arrays and indexes them using a schema-less approach.
   *
   * <p><strong>Supported JSON Formats:</strong>
   *
   * <ul>
   *   <li><strong>Document Array</strong>: {@code [{"field1":"value1"},{"field2":"value2"}]}
   *   <li><strong>Nested Objects</strong>: Automatically flattened with underscore notation
   *   <li><strong>Multi-valued Fields</strong>: Arrays converted to Solr multi-valued fields
   * </ul>
   *
   * <p><strong>Processing Workflow:</strong>
   *
   * <ol>
   *   <li>Parse JSON string into structured documents
   *   <li>Convert to schema-less SolrInputDocument objects
   *   <li>Index them in one request, with error handling
   *   <li>Commit changes to make documents searchable
   * </ol>
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>AI clients can invoke this method with natural language requests like "index these documents
   * into my_collection" or "add this JSON data to the search index".
   *
   * <p><strong>Error Handling:</strong>
   *
   * <p>If indexing fails, the method attempts individual document processing to maximize the number
   * of successfully indexed documents. Detailed error information is logged for troubleshooting
   * purposes.
   *
   * @param collection the name of the Solr collection to index documents into
   * @param documents the documents to index, one map per document
   * @return the collection, the number of documents submitted and indexed, and the field names
   * @throws IllegalArgumentException if the collection does not exist, or Solr rejected every
   *     document
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   * @see IndexingDocumentCreator#createSchemalessDocumentsFromJson(List)
   * @see #indexDocuments(String, List)
   */
  @PreAuthorize(McpAuthorities.WRITE)
  @McpTool(
      name = INDEX_JSON_TOOL,
      annotations = @McpTool.McpAnnotations(idempotentHint = true),
      description =
          "Index documents passed as a JSON array of objects into Solr collection; one object"
              + " per document, multi-valued fields as arrays, nested objects flattened with underscores."
              + " Pass the array itself, not a JSON string. Field names are used as given. "
              + PARSED_RESULT_DESCRIPTION,
      generateOutputSchema = true)
  public IndexingResult indexJsonDocuments(
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection,
      @McpToolParam(description = "Documents to index: a JSON array with one object per document")
          List<Map<String, Object>> documents) {
    List<SolrInputDocument> schemalessDocuments =
        indexingDocumentCreator.createSchemalessDocumentsFromJson(documents);
    return indexParsed(collection, Format.json, schemalessDocuments);
  }

  /**
   * Indexes CSV rows into a Solr collection by forwarding the payload, as given, to Solr's own CSV
   * update handler. Solr reads the header row for the field names and parses the rows; the server
   * does not inspect the payload. A column name repeated in the header yields a multi-valued field,
   * and empty cells are skipped. Solr accepts or rejects the payload as a whole.
   *
   * @param collection the name of the Solr collection to index documents into
   * @param csv CSV text with a header row
   * @return the collection and format, without counts: Solr does not report one
   * @throws IllegalArgumentException if the collection does not exist, or Solr rejected the
   *     payload; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.WRITE)
  @McpTool(
      name = INDEX_CSV_TOOL,
      annotations = @McpTool.McpAnnotations(idempotentHint = true),
      description =
          "Index documents from CSV string into Solr collection via Solr's CSV handler. The first row"
              + " is the header and its column names are used as the field names, as given; repeat a column name"
              + " to make that field multi-valued; empty cells are skipped. Solr accepts or rejects the"
              + " payload as a whole and reports no document count",
      generateOutputSchema = true)
  public IndexingResult indexCsvDocuments(
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection,
      @McpToolParam(description = "CSV string containing documents to index") String csv) {
    ContentStreamUpdateRequest request = new ContentStreamUpdateRequest(UPDATE_PATH);
    request.setParam("header", "true");
    request.addContentStream(new ContentStreamBase.StringStream(csv, "text/csv; charset=UTF-8"));
    return forward(collection, request, Format.csv);
  }

  /**
   * Indexes documents supplied in Solr's update XML format ({@code <add><doc><field
   * name="...">...</field></doc></add>}) by forwarding the payload to Solr's update handler. The
   * only server-side step is {@link SolrUpdateXml}: the same grammar carries {@code <delete>} and
   * {@code <commit>} commands that an indexing tool must not forward, so the root element must be
   * {@code <add>}. Solr parses the payload and accepts or rejects it as a whole.
   *
   * @param collection the name of the Solr collection to index documents into
   * @param xml a Solr {@code <add>} block
   * @return the collection and format, without counts: Solr does not report one
   * @throws IllegalArgumentException if the collection does not exist, or Solr rejected the
   *     payload; the message carries Solr's reason
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   */
  @PreAuthorize(McpAuthorities.WRITE)
  @McpTool(
      name = INDEX_XML_TOOL,
      annotations = @McpTool.McpAnnotations(idempotentHint = true),
      description =
          "Index documents from Solr update XML into Solr collection: <add><doc><field name=\"id\">1</field>"
              + "<field name=\"genres\">a</field><field name=\"genres\">b</field></doc></add>; repeat <field> for"
              + " multi-valued fields. Only <add> blocks are accepted; delete and commit commands are rejected."
              + " Field names are used as given. Solr accepts or rejects the payload as a whole and"
              + " reports no document count",
      generateOutputSchema = true)
  public IndexingResult indexXmlDocuments(
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection,
      @McpToolParam(description = "Solr update XML: an <add> block of <doc> elements") String xml) {
    SolrUpdateXml.requireAddBlock(xml);
    ContentStreamUpdateRequest request = new ContentStreamUpdateRequest(UPDATE_PATH);
    request.addContentStream(new ContentStreamBase.StringStream(xml, ClientUtils.TEXT_XML));
    return forward(collection, request, Format.xml);
  }

  /**
   * Sends a payload to a Solr update handler, committing it on the same request rather than in a
   * second round trip, so a returned result means Solr accepted and committed the payload. A
   * rejected payload throws. Solr's update response carries no document count, so the result claims
   * none.
   *
   * <p>The commit is a soft one: {@code waitSearcher} keeps the documents searchable the moment the
   * tool returns, while the segment fsync is left to Solr's {@code autoCommit}, which the {@code
   * _default} configset enables at 15 s, so many small calls do not each force one.
   */
  private IndexingResult forward(
      String collection, ContentStreamUpdateRequest request, Format format) {
    request.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, true, true);
    SolrCalls.call(
        INDEXING_FAILED,
        SolrCalls.collectionNotFound(collection),
        () -> request.process(solrClient, collection));
    return IndexingResult.forwarded(collection, format);
  }

  /**
   * Indexes markdown documents into a specified Solr collection, one array element per document.
   *
   * <p>This method serves as the primary entry point for markdown document indexing operations and
   * is exposed as an MCP tool for AI client interactions. Unlike the structured formats (JSON, CSV,
   * XML), markdown is a prose format, so searchable structure is extracted from the document
   * content itself.
   *
   * <p><strong>Field Extraction:</strong>
   *
   * <ul>
   *   <li><strong>YAML Front Matter</strong>: Each entry becomes a document field under its own
   *       name (multi-valued where applicable)
   *   <li><strong>title</strong>: From the {@code title} front matter entry, or the first level-1
   *       heading
   *   <li><strong>headings</strong>: Multi-valued field with the text of every heading (the
   *       document outline)
   *   <li><strong>content</strong>: Plain text body for full-text search (front matter excluded)
   * </ul>
   *
   * <p><strong>MCP Tool Usage:</strong>
   *
   * <p>AI clients can invoke this method with natural language requests like "index these markdown
   * documents into my_collection".
   *
   * <p><strong>Example Markdown Processing:</strong>
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
   * @param collection the name of the Solr collection to index documents into
   * @param documents the markdown documents, one string per document
   * @return the collection, the number of documents submitted and indexed, and the field names
   * @throws IllegalArgumentException if the collection does not exist, or Solr rejected every
   *     document
   * @throws IllegalStateException if Solr failed or could not be reached
   * @see SolrCalls
   * @see IndexingDocumentCreator#createSchemalessDocumentsFromMarkdown(String)
   * @see #indexDocuments(String, List)
   */
  @PreAuthorize(McpAuthorities.WRITE)
  @McpTool(
      name = INDEX_MARKDOWN_TOOL,
      annotations = @McpTool.McpAnnotations(idempotentHint = true),
      description =
          "Index markdown documents into Solr collection, one array element per document, extracting front matter, title, headings, and body text from each. "
              + "Pass many documents in one call rather than one call per document. "
              + "Do NOT use for JSON/CSV/XML input; use index-json-documents, index-csv-documents, or index-xml-documents instead. "
              + "Only convert source content to markdown when there is no dedicated tool for the source format, and supply a stable 'id' in the YAML front matter when doing so. "
              + PARSED_RESULT_DESCRIPTION,
      generateOutputSchema = true)
  public IndexingResult indexMarkdownDocuments(
      @McpToolParam(description = COLLECTION_PARAM_DESCRIPTION) String collection,
      @McpToolParam(
              description =
                  "Markdown documents to index, one string per document, each optionally starting with YAML front matter")
          List<String> documents) {
    List<SolrInputDocument> schemalessDocuments = new ArrayList<>();
    for (String markdown : documents) {
      schemalessDocuments.addAll(
          indexingDocumentCreator.createSchemalessDocumentsFromMarkdown(markdown));
    }
    return indexParsed(collection, Format.markdown, schemalessDocuments);
  }

  /**
   * Indexes documents the server parsed itself and reports how many Solr accepted, with the field
   * names of the accepted documents. Nested JSON objects are flattened with underscores, so the
   * indexed names can differ from the input; reporting them lets MCP clients query the right
   * fields.
   *
   * @param collection the collection to index into
   * @param format the input format the documents were parsed from
   * @param documents the parsed documents
   * @return the counts, the first {@link #MAX_REPORTED_FIELDS} field names of the accepted
   *     documents in sort order, and how many distinct field names they have
   * @throws IllegalArgumentException if there were documents and Solr rejected all of them, or the
   *     collection does not exist
   * @throws IllegalStateException if Solr failed or could not be reached
   */
  private IndexingResult indexParsed(
      String collection, Format format, List<SolrInputDocument> documents) {
    List<SolrInputDocument> accepted =
        SolrCalls.call(
            INDEXING_FAILED,
            SolrCalls.collectionNotFound(collection),
            () -> indexDocuments(collection, documents));
    if (!documents.isEmpty() && accepted.isEmpty()) {
      throw new IllegalArgumentException(EVERY_DOCUMENT_REJECTED);
    }
    Set<String> fieldNames = new TreeSet<>();
    for (SolrInputDocument document : accepted) {
      fieldNames.addAll(document.getFieldNames());
    }
    return IndexingResult.parsed(
        collection,
        format,
        documents.size(),
        accepted.size(),
        fieldNames.stream().limit(MAX_REPORTED_FIELDS).toList(),
        fieldNames.size());
  }

  /**
   * Indexes documents into a Solr collection in one update request that also carries the soft
   * commit, as {@link #forward} does for CSV and XML, so the documents are searchable when this
   * returns.
   *
   * <p>If Solr rejects the request with a 400 (a document with an unknown field or a value of the
   * wrong type), the documents are retried one at a time so the valid ones are still indexed, then
   * committed. A retried document that Solr answers with a 400 is skipped. Any other failure, of
   * the batch or of a retried document (unknown collection, auth, 5xx, Solr unreachable), says
   * nothing about the documents, so it propagates unchanged.
   *
   * @param collection the name of the Solr collection to index into
   * @param documents the documents to index
   * @return the documents Solr accepted, in input order: all of them when the batch succeeds
   * @throws SolrServerException if Solr cannot be reached or the commit fails
   * @throws IOException if there are I/O errors during communication
   */
  public List<SolrInputDocument> indexDocuments(
      String collection, List<SolrInputDocument> documents)
      throws SolrServerException, IOException {
    UpdateRequest request = new UpdateRequest();
    request.add(documents);
    request.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, true, true);
    try {
      request.process(solrClient, collection);
      return List.copyOf(documents);
    } catch (SolrException e) {
      requireBadRequest(e);
      logger.warn("Solr rejected the batch, retrying documents individually", e);
    }

    List<SolrInputDocument> accepted = new ArrayList<>();
    for (SolrInputDocument document : documents) {
      try {
        solrClient.add(collection, document);
        accepted.add(document);
      } catch (SolrException e) {
        requireBadRequest(e);
        logger.debug("Solr rejected an individual document", e);
      }
    }
    solrClient.commit(collection, false, true, true);
    return List.copyOf(accepted);
  }

  /**
   * Rethrows a Solr failure unless it is a 400, the only answer that means Solr rejected the
   * documents themselves.
   *
   * @param failure the failure Solr reported
   * @throws SolrException {@code failure} itself, when its code is not 400
   */
  private static void requireBadRequest(SolrException failure) {
    if (failure.code() != SolrException.ErrorCode.BAD_REQUEST.code) {
      throw failure;
    }
  }

  /** MCP tool name of {@link #indexJsonDocuments}. */
  private static final String INDEX_JSON_TOOL = "index-json-documents";

  /** MCP tool name of {@link #indexCsvDocuments}. */
  private static final String INDEX_CSV_TOOL = "index-csv-documents";

  /** MCP tool name of {@link #indexXmlDocuments}. */
  private static final String INDEX_XML_TOOL = "index-xml-documents";

  /** MCP tool name of {@link #indexMarkdownDocuments}. */
  private static final String INDEX_MARKDOWN_TOOL = "index-markdown-documents";

  /**
   * The input formats the {@code index-data} prompt accepts, each mapped to the MCP tool that
   * indexes it and that tool's payload parameter. The tool names are the same constants the
   * {@code @McpTool} annotations use, so renaming a tool cannot leave the prompt pointing at a tool
   * that no longer exists.
   */
  private enum IndexFormat {
    JSON(INDEX_JSON_TOOL, "documents", "the documents as a JSON array of objects, not as a string"),
    CSV(INDEX_CSV_TOOL, "csv", "the CSV text"),
    XML(INDEX_XML_TOOL, "xml", "the XML text"),
    MARKDOWN(
        INDEX_MARKDOWN_TOOL,
        "documents",
        "the markdown documents, one string per document, each optionally starting with YAML front matter");

    /** Alias accepted for {@link #MARKDOWN}. */
    private static final String MARKDOWN_ALIAS = "md";

    private final String toolName;
    private final String paramName;
    private final String payload;

    /**
     * @param toolName the MCP tool that indexes this format
     * @param paramName the tool's payload parameter name, which is the Java parameter name the MCP
     *     input schema is generated from
     * @param payload prose describing what to pass for {@code paramName}
     */
    IndexFormat(String toolName, String paramName, String payload) {
      this.toolName = toolName;
      this.paramName = paramName;
      this.payload = payload;
    }

    /** The canonical keyword, so the prompt reads "markdown" even for the {@code md} alias. */
    String keyword() {
      return name().toLowerCase(Locale.ROOT);
    }

    String toolName() {
      return toolName;
    }

    String paramName() {
      return paramName;
    }

    String payload() {
      return payload;
    }

    /**
     * Resolves a format keyword, ignoring case and surrounding whitespace.
     *
     * @throws IllegalArgumentException if {@code format} names no supported format
     */
    static IndexFormat parse(String format) {
      String normalized = format.trim().toLowerCase(Locale.ROOT);
      if (normalized.equals(MARKDOWN_ALIAS)) {
        return MARKDOWN;
      }
      for (IndexFormat candidate : values()) {
        if (candidate.keyword().equals(normalized)) {
          return candidate;
        }
      }
      List<String> keywords = new ArrayList<>();
      for (IndexFormat candidate : values()) {
        keywords.add(candidate.keyword());
      }
      throw new IllegalArgumentException(
          "format must be one of " + String.join("/", keywords) + ", got: " + format);
    }
  }

  /**
   * MCP prompt that guides the client through indexing documents: verify the target schema, pick
   * the right indexing tool for the input format, and confirm the result.
   *
   * @param collection target Solr collection name
   * @param format document format; one of {@code json}, {@code csv}, {@code xml}, or {@code
   *     markdown} (alias {@code md})
   * @param sample optional small sample of the input document(s) to ground field-shape decisions;
   *     may be {@code null} or blank
   * @return the prompt text instructing the model how to index the documents
   */
  @PreAuthorize(McpAuthorities.READ)
  @McpPrompt(
      name = INDEX_DATA_PROMPT,
      title = "Index documents into a Solr collection",
      description = INDEX_DATA_PROMPT_DESCRIPTION)
  public GetPromptResult indexDataPrompt(
      @McpArg(
              name = PromptArguments.COLLECTION,
              description = PromptArguments.COLLECTION_DESCRIPTION,
              required = true)
          String collection,
      @McpArg(
              name = "format",
              description = "Document format: 'json', 'csv', 'xml', or 'markdown'",
              required = true)
          String format,
      @McpArg(
              name = "sample",
              description =
                  "Optional small sample of the input document(s) to ground field-shape decisions",
              required = false)
          @Nullable String sample) {
    IndexFormat indexFormat = IndexFormat.parse(format);
    String sampleSection =
        sample == null || sample.isBlank()
            ? "   - No sample was provided. If the user has not pasted the documents yet, ask for them (or a representative subset) before indexing."
            : "   - Sample input:\n\n     ```\n" + sample.strip().indent(5) + "     ```";
    return McpResults.prompt(
        INDEX_DATA_PROMPT_DESCRIPTION,
        Role.USER,
        """
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
				   - The tool commits at the end and returns `collection` and `format`. For JSON and
				     markdown it also returns `documentsSubmitted`, `documentsIndexed` (lower when Solr
				     rejected some documents), `fieldNames` (up to 50 field names of the indexed
				     documents, in sort order) and `fieldCount` (how many there are); it fails if Solr
				     rejects every document. For CSV and XML Solr accepts or rejects the whole payload
				     and reports no count, so step 4 is where you learn it.
				   - On error, read the message carefully: an "unknown field" error means the schema is
				     missing a field — go back to step 1 and run `design-schema`. A parse error means
				     the input format does not match the chosen tool — fix the payload and retry.

				4. Verify the count.
				   - Call `check-health` on `%s` and confirm the reported doc count increased by the
				     expected amount, OR call `search` with `query=*:*` and `rows=0` and read
				     `numFound`.

				Next step suggestion: once data is indexed, the `search-collection` prompt drives
				searching it.
				"""
            .formatted(
                indexFormat.keyword(),
                collection,
                collection,
                sampleSection,
                indexFormat.toolName(),
                collection,
                indexFormat.paramName(),
                indexFormat.payload(),
                collection));
  }
}
