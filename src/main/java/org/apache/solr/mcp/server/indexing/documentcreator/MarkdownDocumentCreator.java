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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Utility class for processing markdown documents and converting them to
 * SolrInputDocument objects.
 *
 * <p>
 * Unlike the structured formats (JSON, CSV, XML), markdown is a prose format,
 * so this creator extracts searchable structure from the document rather than
 * mapping fields one-to-one. Parsing is performed with the CommonMark library,
 * which is lightweight and reflection-free (GraalVM native-image safe).
 *
 * <p>
 * <strong>Field Extraction Rules:</strong>
 *
 * <ul>
 * <li><strong>YAML Front Matter</strong>: Each front matter entry becomes a
 * document field with a sanitized name. Entries with multiple values become
 * multi-valued fields.
 * <li><strong>id</strong>: Taken from the {@code id} front matter entry when
 * present, otherwise derived deterministically from a SHA-256 hash of the input
 * so that re-indexing the same markdown overwrites the same document (keeping
 * the operation idempotent).
 * <li><strong>title</strong>: Taken from the {@code title} front matter entry
 * when present, otherwise from the first level-1 heading.
 * <li><strong>headings</strong>: Multi-valued field containing the text of
 * every heading, preserving the document outline for searching.
 * <li><strong>content</strong>: The plain text of the document body (front
 * matter excluded), suitable for full-text search.
 * </ul>
 *
 * <p>
 * <strong>Example Transformation:</strong>
 *
 * <pre>{@code
 * Input markdown:
 * ---
 * author: Jane Doe
 * tags: [search, solr]
 * ---
 * # Getting Started
 * ## Installation
 * Run the installer.
 *
 * Output document:
 * {author:"Jane Doe", tags:["search","solr"], title:"Getting Started",
 *  headings:["Getting Started","Installation"], content:"Getting Started\nInstallation\nRun the installer."}
 * }</pre>
 *
 * @see SolrInputDocument
 * @see FieldNameSanitizer#sanitizeFieldName(String)
 */
@Component
public class MarkdownDocumentCreator implements SolrDocumentCreator {

	private static final int MAX_INPUT_SIZE_BYTES = 10 * 1024 * 1024;

	/** Solr field holding the document's unique key. */
	public static final String FIELD_ID = "id";

	/** Solr field holding the document title. */
	public static final String FIELD_TITLE = "title";

	/** Multi-valued Solr field holding the text of every heading. */
	public static final String FIELD_HEADINGS = "headings";

	/** Solr field holding the plain text body of the document. */
	public static final String FIELD_CONTENT = "content";

	private final Parser parser;

	private final TextContentRenderer textContentRenderer;

	public MarkdownDocumentCreator() {
		List<Extension> extensions = List.of(YamlFrontMatterExtension.create());
		this.parser = Parser.builder().extensions(extensions).build();
		this.textContentRenderer = TextContentRenderer.builder().build();
	}

	/**
	 * Creates a SolrInputDocument from a markdown string.
	 *
	 * <p>
	 * The whole input is treated as a single document: front matter entries map to
	 * fields, the title is resolved from front matter or the first level-1 heading,
	 * all heading texts are collected into a multi-valued {@code headings} field,
	 * and the plain text body is stored in {@code content}.
	 *
	 * @param markdown
	 *            markdown string, optionally starting with YAML front matter
	 * @return a single-element list containing the created document, or an empty
	 *         list if the input is blank
	 * @throws DocumentProcessingException
	 *             if the input exceeds the size limit or parsing fails
	 */
	@Override
	public List<SolrInputDocument> create(String markdown) throws DocumentProcessingException {
		if (markdown.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_SIZE_BYTES) {
			throw new DocumentProcessingException(
					"Input too large: exceeds maximum size of " + MAX_INPUT_SIZE_BYTES + " bytes");
		}

		if (markdown.trim().isEmpty()) {
			return List.of();
		}

		Node document;
		try {
			document = parser.parse(markdown);
		} catch (RuntimeException e) {
			throw new DocumentProcessingException("Failed to parse markdown document", e);
		}

		SolrInputDocument doc = new SolrInputDocument();

		addFrontMatterFields(document, doc);

		// Solr's default schema requires a unique key. A content-derived id keeps
		// re-indexing of the same markdown idempotent (same input, same document)
		if (doc.getFieldValue(FIELD_ID) == null) {
			doc.addField(FIELD_ID, contentHash(markdown));
		}

		HeadingCollector headingCollector = new HeadingCollector();
		document.accept(headingCollector);
		for (String heading : headingCollector.headings) {
			doc.addField(FIELD_HEADINGS, heading);
		}

		// Front matter title wins; otherwise fall back to the first level-1 heading
		if (doc.getFieldValue(FIELD_TITLE) == null && headingCollector.firstTopLevelHeading != null) {
			doc.addField(FIELD_TITLE, headingCollector.firstTopLevelHeading);
		}

		String content = textContentRenderer.render(document).trim();
		if (!content.isEmpty()) {
			doc.addField(FIELD_CONTENT, content);
		}

		return List.of(doc);
	}

	/**
	 * Extracts YAML front matter entries into document fields and unlinks the front
	 * matter block so it is excluded from the rendered body content.
	 */
	private void addFrontMatterFields(Node document, SolrInputDocument doc) {
		YamlFrontMatterVisitor frontMatterVisitor = new YamlFrontMatterVisitor();
		document.accept(frontMatterVisitor);

		frontMatterVisitor.getData().forEach((key, values) -> {
			String fieldName = FieldNameSanitizer.sanitizeFieldName(key);
			for (String value : flattenFlowSequences(values)) {
				if (!value.isEmpty()) {
					doc.addField(fieldName, value);
				}
			}
		});

		// The front matter block is metadata, not body text: remove it so the
		// TextContentRenderer output contains only the document body
		Node firstChild = document.getFirstChild();
		if (firstChild instanceof CustomBlock) {
			firstChild.unlink();
		}
	}

	/**
	 * Expands simple YAML flow sequences into individual values.
	 *
	 * <p>
	 * The CommonMark front matter extension parses block-style lists
	 * ({@code - item}) into multiple values but passes flow-style lists
	 * ({@code [a, b, c]}) through as a single literal string. Flow style is common
	 * for tags in real-world markdown (Jekyll, Hugo), so split it here to produce
	 * the same multi-valued field either way. Values containing commas inside
	 * quotes are not supported and are kept as-is.
	 */
	private static List<String> flattenFlowSequences(List<String> values) {
		List<String> result = new ArrayList<>(values.size());
		for (String value : values) {
			String trimmed = value.trim();
			if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains("\"")
					&& !trimmed.contains("'")) {
				for (String element : trimmed.substring(1, trimmed.length() - 1).split(",")) {
					result.add(element.trim());
				}
			} else {
				result.add(value);
			}
		}
		return result;
	}

	private static String contentHash(String markdown) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(markdown.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed to be available on every Java platform
			throw new IllegalStateException("SHA-256 MessageDigest not available", e);
		}
	}

	/**
	 * AST visitor collecting heading texts and the first level-1 heading for use as
	 * a title fallback.
	 */
	private static final class HeadingCollector extends AbstractVisitor {

		private final List<String> headings = new ArrayList<>();

		@Nullable private String firstTopLevelHeading;

		@Override
		public void visit(Heading heading) {
			String text = collectText(heading).trim();
			if (!text.isEmpty()) {
				headings.add(text);
				if (firstTopLevelHeading == null && heading.getLevel() == 1) {
					firstTopLevelHeading = text;
				}
			}
			visitChildren(heading);
		}

		private static String collectText(Node node) {
			StringBuilder builder = new StringBuilder();
			appendText(node, builder);
			return builder.toString();
		}

		private static void appendText(Node node, StringBuilder builder) {
			if (node instanceof Text text) {
				builder.append(text.getLiteral());
			} else if (node instanceof Code code) {
				builder.append(code.getLiteral());
			}
			for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
				appendText(child, builder);
			}
		}
	}
}
