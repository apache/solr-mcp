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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Opt-in JSON ingestion from an operator-controlled directory on the MCP
 * server. File contents use the same parsing, batching and commit path as
 * inline JSON.
 */
@Service
@Observed
public class FileIndexingService {

	private static final Logger logger = LoggerFactory.getLogger(FileIndexingService.class);

	private static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

	private final IndexingService indexingService;

	private final String ingestRoot;

	/**
	 * Creates the file-ingestion boundary around the existing indexing pipeline.
	 *
	 * @param indexingService
	 *            the existing JSON indexing pipeline
	 * @param ingestRoot
	 *            operator-configured directory; blank disables file reads
	 */
	public FileIndexingService(IndexingService indexingService, @Value("${solr.mcp.ingest.root:}") String ingestRoot) {
		this.indexingService = indexingService;
		this.ingestRoot = ingestRoot;
	}

	/**
	 * Reads a permitted JSON file and indexes it without returning its contents.
	 *
	 * @param collection
	 *            target collection with a prepared schema
	 * @param path
	 *            absolute server-side path or path relative to the ingest root
	 * @return the same count and indexed-field summary as inline JSON ingestion
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-json-file",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index a UTF-8 JSON file without sending its contents through the model. "
					+ "Requires the operator to set SOLR_MCP_INGEST_ROOT; disabled by default. "
					+ "The path must be on the MCP server's filesystem under that directory (maximum 10 MiB), "
					+ "not a URL or a file only available on a remote client. Reuse the path for another collection. "
					+ IndexingService.SCHEMA_FIRST_GUIDANCE)
	public String indexJsonFile(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(
					description = "JSON file path on the MCP server, absolute or relative to SOLR_MCP_INGEST_ROOT; no URLs or ~ expansion") String path) {
		if (ingestRoot.isBlank()) {
			throw new IllegalArgumentException(
					"File ingestion is disabled. Ask the operator to set SOLR_MCP_INGEST_ROOT "
							+ "to a dedicated data directory, or use index-json-documents with inline JSON.");
		}
		if (collection == null || collection.isBlank()) {
			throw new IllegalArgumentException("Provide a non-empty collection name.");
		}
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException(
					"Provide a JSON file path on the MCP server under SOLR_MCP_INGEST_ROOT.");
		}
		String json = readJsonFile(path);
		try {
			return indexingService.indexJsonDocuments(collection, json);
		} catch (DocumentProcessingException e) {
			logger.debug("Could not parse JSON file for indexing", e);
			throw new IllegalArgumentException("The file must contain a JSON object or array of objects. "
					+ "Check the JSON syntax and retry; no documents were sent to Solr.");
		} catch (SolrServerException | IOException | SolrException e) {
			logger.warn("File indexing failed for collection {}", collection, e);
			throw new IllegalStateException("Solr could not complete file indexing. Check collection availability and "
					+ "field types with get-schema, then verify the indexed count before retrying; some documents may already be indexed.");
		}
	}

	private String readJsonFile(String path) {
		try {
			Path root = Path.of(ingestRoot).toRealPath();
			Path candidate = root.resolve(path);
			Path file = candidate.toRealPath();
			if (!file.startsWith(root)) {
				throw new IllegalArgumentException(
						"The file must be inside SOLR_MCP_INGEST_ROOT; symlinks outside it are not allowed.");
			}
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalArgumentException(
						"The path must refer to a regular JSON file, not a directory or device.");
			}
			if (Files.size(file) > MAX_FILE_SIZE_BYTES) {
				throw new IllegalArgumentException(
						"The JSON file exceeds the 10 MiB limit. Split it into smaller files.");
			}
			try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
				byte[] bytes = input.readNBytes(MAX_FILE_SIZE_BYTES + 1);
				if (bytes.length > MAX_FILE_SIZE_BYTES) {
					throw new IllegalArgumentException(
							"The JSON file exceeds the 10 MiB limit. Split it into smaller files.");
				}
				return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			}
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("Invalid file path. Use a local server-side path, not a URL.");
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("The JSON file must be UTF-8 encoded.");
		} catch (IOException | SecurityException e) {
			logger.debug("Could not read JSON file for indexing", e);
			throw new IllegalArgumentException(
					"Cannot read the JSON file. Check that the file and SOLR_MCP_INGEST_ROOT "
							+ "exist and are readable on the MCP server; for Docker, mount the data directory and use its container path.");
		}
	}
}
