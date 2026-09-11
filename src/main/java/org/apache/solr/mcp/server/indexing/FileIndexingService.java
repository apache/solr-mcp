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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Local STDIO file ingestion. The connected client can ingest any regular file
 * readable by this process; operating-system permissions and container mounts
 * define the boundary. This service is never registered in HTTP mode.
 */
@Service
@Observed
@Profile("stdio & !http")
@ConditionalOnNotWebApplication
public class FileIndexingService {

	private static final Logger logger = LoggerFactory.getLogger(FileIndexingService.class);

	private final IndexingService indexingService;

	/**
	 * Creates the local file-ingestion adapter.
	 *
	 * @param indexingService
	 *            the indexing pipeline
	 */
	public FileIndexingService(IndexingService indexingService) {
		this.indexingService = indexingService;
	}

	/**
	 * Java convenience entry point for JSON files; MCP clients use
	 * {@code index-file}.
	 *
	 * @param collection
	 *            target collection
	 * @param path
	 *            local file path
	 * @return indexed document counts and field names
	 */
	@PreAuthorize("isAuthenticated()")
	public String indexJsonFile(String collection, String path) {
		return indexFile(collection, path, "json");
	}

	/**
	 * Streams a local file into Solr without returning its contents.
	 *
	 * @param collection
	 *            target collection with a prepared schema
	 * @param path
	 *            absolute path or path relative to the server working directory
	 * @param format
	 *            optional format override; otherwise inferred from the extension
	 * @return indexed document counts and field names
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-file",
			annotations = @McpTool.McpAnnotations(idempotentHint = true),
			description = "Index a local UTF-8 JSON, CSV, XML or Markdown file without sending its contents through the model. "
					+ "Available only in local STDIO mode. No ingest-root setting or file-size cap. "
					+ "Structured records stream in batches; Markdown remains one document. "
					+ "The path must be readable by the MCP server, not a URL or remote-client-only path. "
					+ "Reuse the path for another collection. Failures can leave partially indexed data; verify counts before retrying. "
					+ IndexingService.SCHEMA_FIRST_GUIDANCE)
	public String indexFile(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(
					description = "Local server file path, absolute or relative to its working directory; no URLs or ~ expansion") String path,
			@McpToolParam(
					description = "Optional format: json, csv, xml, markdown or md; defaults to the file extension",
					required = false) String format) {
		if (collection == null || collection.isBlank()) {
			throw new IllegalArgumentException("Provide a non-empty collection name.");
		}
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("Provide a local file path on the MCP server.");
		}
		Path file = resolveFile(path);
		String selectedFormat = resolveFormat(file, format);
		try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return indexingService.indexFileDocuments(collection, input, selectedFormat);
		} catch (DocumentProcessingException e) {
			logger.debug("Could not parse file for indexing", e);
			throw new IllegalArgumentException("Cannot parse the file as UTF-8 " + selectedFormat
					+ ". Check its syntax and format. Some documents may already be indexed; verify the collection before retrying.");
		} catch (SolrServerException | SolrException e) {
			logger.warn("File indexing failed for collection {}", collection, e);
			throw new IllegalStateException("Solr could not complete file indexing. Check collection availability and "
					+ "field types with get-schema, then verify the indexed count before retrying; some documents may already be indexed.");
		} catch (IOException | SecurityException e) {
			logger.debug("File read or indexing I/O failed", e);
			throw new IllegalStateException(
					"Cannot finish file indexing. Check that the UTF-8 file remains readable and "
							+ "Solr is available; some documents may already be indexed. Verify the collection before retrying.");
		}
	}

	private static String resolveFormat(Path file, String format) {
		String name = file.getFileName().toString();
		String selected = format == null || format.isBlank() ? name.substring(name.lastIndexOf('.') + 1) : format;
		return switch (selected.trim().toLowerCase(Locale.ROOT)) {
			case "json" -> "json";
			case "csv" -> "csv";
			case "xml" -> "xml";
			case "md", "markdown" -> "markdown";
			default -> throw new IllegalArgumentException(
					"Cannot determine the file format. Supply format=json, csv, xml or markdown.");
		};
	}

	private static Path resolveFile(String path) {
		try {
			Path file = Path.of(path).toRealPath();
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalArgumentException("The path must refer to a regular file, not a directory or device.");
			}
			return file;
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("Invalid file path. Use a local server-side path, not a URL.");
		} catch (IOException | SecurityException e) {
			logger.debug("Could not read file for indexing", e);
			throw new IllegalArgumentException(
					"Cannot read the file. Check that it exists and is readable on the MCP server; "
							+ "for Docker, mount the data directory and use its container path.");
		}
	}
}
