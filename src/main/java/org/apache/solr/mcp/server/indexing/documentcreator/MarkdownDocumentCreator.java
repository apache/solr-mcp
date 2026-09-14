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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.solr.common.SolrInputDocument;
import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

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

	/** Opens and closes a YAML front matter block. */
	private static final String DELIMITER = "---";

	/** A line that starts a YAML mapping entry, e.g. {@code id: netflix-001}. */
	private static final Pattern YAML_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*\\s*:");

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

	private final Yaml yaml;

	public MarkdownDocumentCreator() {
		List<Extension> extensions = List.of(YamlFrontMatterExtension.create());
		// Source spans let the front matter block be sliced out verbatim for SnakeYAML
		this.parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
		// No implicit resolvers: every scalar stays the text as written (2026-01-01
		// is not a Date, 8.4 is not a Double); Solr's schema guessing types them.
		LoaderOptions options = new LoaderOptions();
		this.yaml = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(),
				options, new Resolver() {
					@Override
					protected void addImplicitResolvers() {
					}
				});
		this.textContentRenderer = TextContentRenderer.builder().build();
	}

	/**
	 * Creates SolrInputDocuments from a markdown string.
	 *
	 * <p>
	 * A record is one front matter block and the body that follows it: front matter
	 * entries map to fields, the title is resolved from front matter or the first
	 * level-1 heading, all heading texts are collected into a multi-valued
	 * {@code headings} field, and the plain text body is stored in {@code content}.
	 *
	 * <p>
	 * A file may hold several records, so that one markdown file can carry a
	 * dataset rather than only a single document. Splitting is deliberately
	 * conservative — see {@link #splitRecords(String)} — so any input that is not
	 * unambiguously several records yields exactly one document, as it always has.
	 *
	 * @param markdown
	 *            markdown string, optionally starting with YAML front matter
	 * @return one document per record, in file order, or an empty list if the input
	 *         is blank
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

		List<SolrInputDocument> documents = new ArrayList<>();
		for (String record : splitRecords(markdown)) {
			documents.add(createOne(record));
		}
		return List.copyOf(documents);
	}

	/** Creates the single document described by one record. */
	private SolrInputDocument createOne(String markdown) throws DocumentProcessingException {
		Node document;
		try {
			document = parser.parse(markdown);
		} catch (RuntimeException e) {
			throw new DocumentProcessingException("Failed to parse markdown document", e);
		}

		SolrInputDocument doc = new SolrInputDocument();

		addFrontMatterFields(markdown, document, doc);

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

		return doc;
	}

	/**
	 * Splits a markdown file into records, each starting with its own front matter
	 * block.
	 *
	 * <p>
	 * Markdown has no record separator of its own, and {@code ---} already means
	 * two other things — a thematic break, and a setext heading underline. The rule
	 * is therefore narrow on purpose, so that a document which is not a dataset can
	 * never be split apart:
	 *
	 * <ul>
	 * <li>the file must itself open with a front matter block, so ordinary prose
	 * containing a thematic break is never considered;
	 * <li>a boundary is a {@code ---} line preceded by a blank line and followed by
	 * a YAML key, so a thematic break followed by prose is not a boundary;
	 * <li>that block must be closed by a later {@code ---} line, so an unterminated
	 * block is not a boundary either.
	 * </ul>
	 *
	 * <p>
	 * The remaining ambiguity is a document that both opens with front matter and
	 * uses a thematic break immediately followed by a {@code key: value} line; that
	 * one splits when it should not.
	 *
	 * @param markdown
	 *            the whole file
	 * @return the records in file order; a single-element list when the input is
	 *         not unambiguously several records
	 */
	private static List<String> splitRecords(String markdown) {
		String[] lines = markdown.split("\n", -1);
		if (lines.length == 0 || !DELIMITER.equals(lines[0].strip())) {
			return List.of(markdown);
		}

		List<Integer> boundaries = new ArrayList<>();
		for (int i = 1; i < lines.length; i++) {
			if (isRecordStart(lines, i)) {
				boundaries.add(i);
			}
		}
		if (boundaries.isEmpty()) {
			return List.of(markdown);
		}

		List<String> records = new ArrayList<>();
		int start = 0;
		for (int boundary : boundaries) {
			records.add(join(lines, start, boundary));
			start = boundary;
		}
		records.add(join(lines, start, lines.length));
		return List.copyOf(records);
	}

	/**
	 * A record starts at a {@code ---} line that is preceded by a blank line,
	 * followed by a YAML key, and closed by a later {@code ---} line.
	 */
	private static boolean isRecordStart(String[] lines, int index) {
		if (!DELIMITER.equals(lines[index].strip()) || !lines[index - 1].isBlank()) {
			return false;
		}
		if (index + 1 >= lines.length || !YAML_KEY.matcher(lines[index + 1]).find()) {
			return false;
		}
		for (int i = index + 2; i < lines.length; i++) {
			if (DELIMITER.equals(lines[i].strip())) {
				return true;
			}
		}
		return false;
	}

	private static String join(String[] lines, int from, int to) {
		return String.join("\n", Arrays.asList(lines).subList(from, to));
	}

	/**
	 * Parses the YAML front matter with SnakeYAML and adds each entry as a field:
	 * scalars as their text, sequences as multi-valued fields, nested mappings
	 * flattened with underscores. The block is then unlinked so the rendered body
	 * contains only the document text.
	 */
	private void addFrontMatterFields(String markdown, Node document, SolrInputDocument doc) {
		Node firstChild = document.getFirstChild();
		if (!(firstChild instanceof YamlFrontMatterBlock block)) {
			return;
		}
		List<String> lines = new ArrayList<>();
		for (SourceSpan span : block.getSourceSpans()) {
			lines.add(markdown.substring(span.getInputIndex(), span.getInputIndex() + span.getLength()));
		}
		// The first and last spans are the --- delimiters
		String text = String.join("\n", lines.subList(1, Math.max(1, lines.size() - 1)));
		Object data;
		try {
			data = yaml.load(text);
		} catch (RuntimeException e) {
			throw new DocumentProcessingException("Failed to parse YAML front matter", e);
		}
		if (data instanceof Map<?, ?> entries) {
			entries.forEach(
					(key, value) -> addValue(doc, FieldNameSanitizer.sanitizeFieldName(String.valueOf(key)), value));
		}
		block.unlink();
	}

	private static void addValue(SolrInputDocument doc, String fieldName, Object value) {
		switch (value) {
			case null -> {
			}
			case Map<?, ?> nested -> nested.forEach(
					(key, inner) -> addValue(doc, FieldNameSanitizer.sanitizeFieldName(fieldName + "_" + key), inner));
			case Iterable<?> values -> values.forEach(element -> addValue(doc, fieldName, element));
			default -> {
				String text = String.valueOf(value);
				if (!text.isEmpty()) {
					doc.addField(fieldName, text);
				}
			}
		}
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
