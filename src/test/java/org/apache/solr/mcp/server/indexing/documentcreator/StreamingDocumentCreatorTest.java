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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class StreamingDocumentCreatorTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final IndexingDocumentCreator creator = creator(mapper);

	@TempDir
	Path directory;

	private static IndexingDocumentCreator creator(ObjectMapper mapper) {
		return new IndexingDocumentCreator(new XmlDocumentCreator(), new CsvDocumentCreator(),
				new JsonDocumentCreator(mapper), new MarkdownDocumentCreator());
	}

	@ParameterizedTest
	@MethodSource("mappingExamples")
	void preservesInlineFieldMapping(String format, String input) {
		List<SolrInputDocument> actual = new ArrayList<>();
		TrackingReader reader = new TrackingReader(input);
		creator.stream(reader, format, actual::add);
		assertThat(actual.stream().map(StreamingDocumentCreatorTest::fields).toList())
				.isEqualTo(inline(format, input).stream().map(StreamingDocumentCreatorTest::fields).toList());
		assertThat(reader.closed).isFalse();
	}

	static Stream<Arguments> mappingExamples() {
		return Stream.of(Arguments.of("json", """
				[{"ID":1,"User Name":{"First.Name":"Jane","age":42},"long":3000000000,
				"price":2.5,"active":true,"missing":null,"tags":["a",2,null,{},[1]],
				"empty":[],"ignored":[{"a":1}]},{"id":2}]
				"""), Arguments.of("json", "{\"id\":\"one\",\"üser-name\":\"é\"}"),
				Arguments.of("json", "[1,null,[],{}, {\"id\":2}]"), Arguments.of("json", "[]"), Arguments.of("csv", """
						ID,User Name,quote,empty,Extra
						1,"Jane, Doe","a ""quote"" inside",,true
						2,"two
						lines",42
						3, spaces ,value,,x,ignored
						"""), Arguments.of("csv", "id,Name\n"), Arguments.of("csv", "Name,Name\nfirst,second\n"),
				Arguments.of("xml",
						"<book id='123'><title>Title</title><author><name>Jane</name>"
								+ "<tag>a</tag><tag>b</tag></author><empty/></book>"),
				Arguments.of("xml",
						"<books ignored='true'>root text<book id='1'><title>A</title></book>"
								+ "<book id='2'><title>B</title></book>tail</books>"),
				Arguments.of("xml",
						"<root><first>a</first><second>b</second><third>c</third>"
								+ "<second>d</second><empty/><last>e</last></root>"),
				Arguments.of("xml", "<root><first/><first/><second>value</second></root>"),
				Arguments.of("xml",
						"<root> before &amp; &#65; <![CDATA[not indexed]]> after<!-- split -->"
								+ "comment<?test instruction?>end<child> a <![CDATA[ignored]]> b </child>tail</root>"),
				Arguments.of("xml",
						"<n:root xmlns:n='urn:test' n:attr='yes'><n:child>one</n:child>"
								+ "<n:child>two</n:child></n:root>"),
				Arguments.of("xml", "<n:root xmlns:n='urn:test' n:attr='yes'><n:child>one</n:child></n:root>"),
				Arguments.of("xml", "<root xmlns='urn:test'><child>one</child></root>"),
				Arguments.of("xml", "<undeclared:root undeclared:attr='x'>value</undeclared:root>"),
				Arguments.of("xml", "<?xml version='1.0'?><!-- before --><root/> <!-- after -->"),
				Arguments.of("markdown", """
						---
						id: custom-id
						title: Front matter title
						author-name: Jane
						tags: [solr, search]
						categories:
						  - first
						  - second
						---
						# Heading *one*
						## Heading `two`
						A **body** with [a link](https://example.invalid).
						"""), Arguments.of("markdown", "# Generated ID\r\n\r\nUnicode é 日本語 and `code`.\r\n"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "csv", "xml"})
	void emitsRecordsIncrementallyAcrossFilesLargerThanTenMiB(String format) {
		String payload = "x".repeat(512);
		String prefix = switch (format) {
			case "json" -> "[";
			case "csv" -> "value\n";
			default -> "<records>";
		};
		String record = switch (format) {
			case "json" -> "{\"value\":\"" + payload + "\"},";
			case "csv" -> payload + "\n";
			default -> "<record><value>" + payload + "</value></record>";
		};
		String suffix = switch (format) {
			case "json" -> "{\"value\":\"" + payload + "\"}]";
			case "csv" -> payload + "\n";
			default -> "<record><value>" + payload + "</value></record></records>";
		};
		AtomicInteger delivered = new AtomicInteger();
		RepeatingReader reader = new RepeatingReader(prefix, record, suffix, 22000, delivered);
		assertThat(reader.length).isGreaterThan(10 * 1024 * 1024);
		creator.stream(reader, format, doc -> {
			assertThat(doc.getFieldValue(format.equals("xml") ? "record_value" : "value")).isEqualTo(payload);
			delivered.incrementAndGet();
		});
		assertThat(delivered).hasValue(22001);
		assertThat(reader.position).isEqualTo(reader.length);
		assertThat(reader.closed).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "csv", "xml", "markdown"})
	void acceptsFileAndSingleRecordLargerThanInlineLimit(String format) throws IOException {
		// JSON also exceeds Jackson's default 20,000,000-character string limit.
		String value = "x".repeat(format.equals("json") ? 21 * 1024 * 1024 : 11 * 1024 * 1024);
		String input = switch (format) {
			case "json" -> "[{\"value\":\"" + value + "\"}]";
			case "csv" -> "value\n\"" + value + "\"\n";
			case "xml" -> "<record><value>" + value + "</value></record>";
			default -> "# Title\n\n" + value;
		};
		Path file = directory.resolve("large." + format);
		Files.writeString(file, input, StandardCharsets.UTF_8);
		assertThat(Files.size(file)).isGreaterThan(10 * 1024 * 1024);
		AtomicInteger delivered = new AtomicInteger();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			creator.stream(reader, format, doc -> {
				String field = switch (format) {
					case "xml" -> "record_value";
					case "markdown" -> "content";
					default -> "value";
				};
				assertThat(doc.getFieldValue(field)).isEqualTo(format.equals("markdown") ? "Title\n" + value : value);
				delivered.incrementAndGet();
			});
		}
		assertThat(delivered).hasValue(1);
		assertThatThrownBy(() -> inline(format, input)).isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("large");
	}

	@Test
	void acceptsLargeMarkdownFrontMatterWithoutYamlCodepointLimit() {
		String value = "x".repeat(11 * 1024 * 1024);
		List<SolrInputDocument> documents = new ArrayList<>();
		creator.stream(new StringReader("---\nmetadata: " + value + "\n---\n# Body"), "markdown", documents::add);
		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("metadata")).isEqualTo(value);
		assertThat(documents.getFirst().getFieldValue("content")).isEqualTo("Body");
	}

	@Test
	void fileConstraintsDoNotChangeSharedInlineMapper() {
		mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(100).build());
		IndexingDocumentCreator constrained = creator(mapper);
		String input = "{\"value\":\"" + "x".repeat(10000) + "\"}";
		List<SolrInputDocument> documents = new ArrayList<>();
		constrained.stream(new StringReader(input), "json", documents::add);
		assertThat(documents).hasSize(1);
		assertThat(mapper.getFactory().streamReadConstraints().getMaxStringLength()).isEqualTo(100);
		assertThatThrownBy(() -> constrained.createSchemalessDocumentsFromJson(input))
				.isInstanceOf(DocumentProcessingException.class);
	}

	@ParameterizedTest
	@MethodSource("malformedInputs")
	void propagatesParseFailuresWithoutClosingReader(String format, String input) {
		TrackingReader reader = new TrackingReader(input);
		assertThatThrownBy(() -> creator.stream(reader, format, doc -> {
		})).isInstanceOf(DocumentProcessingException.class);
		assertThat(reader.closed).isFalse();
	}

	static Stream<Arguments> malformedInputs() {
		return Stream.of(Arguments.of("json", ""), Arguments.of("json", "  "), Arguments.of("json", "null"),
				Arguments.of("json", "true"), Arguments.of("json", "["), Arguments.of("json", "[{\"id\":1},"),
				Arguments.of("json", "{\"id\":1} garbage"), Arguments.of("json", "{} {}"),
				Arguments.of("json", "[{}] []"), Arguments.of("csv", ""),
				Arguments.of("csv", "id,name\n1,\"unterminated"), Arguments.of("xml", ""),
				Arguments.of("xml", "<root>"), Arguments.of("xml", "<root><child/></wrong>"),
				Arguments.of("xml", "<root/> <another/>"), Arguments.of("markdown", " \r\n\t"));
	}

	@ParameterizedTest
	@MethodSource("malformedTails")
	void emitsEarlierRecordBeforeReportingMalformedTail(String format, String input, String idField) {
		List<SolrInputDocument> documents = new ArrayList<>();
		TrackingReader reader = new TrackingReader(input);
		assertThatThrownBy(() -> creator.stream(reader, format, documents::add))
				.isInstanceOf(DocumentProcessingException.class);
		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue(idField)).isEqualTo("first");
		assertThat(reader.closed).isFalse();
	}

	static Stream<Arguments> malformedTails() {
		return Stream.of(Arguments.of("json", "[{\"id\":\"first\"},{\"id\":", "id"),
				Arguments.of("csv", "id\nfirst\n\"unterminated", "id"),
				Arguments.of("xml", "<records><record id='first'/><record><broken>", "id_attr"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "csv", "xml", "markdown"})
	void consumerFailuresPropagateUnchangedAndStopParsing(String format) {
		for (RuntimeException failure : List.of(new IllegalArgumentException("consumer"),
				new UncheckedIOException(new IOException("consumer")), new DocumentProcessingException("consumer"))) {
			TrackingReader reader = new TrackingReader(simpleInput(format));
			AtomicInteger delivered = new AtomicInteger();
			RuntimeException actual = assertThrows(RuntimeException.class, () -> creator.stream(reader, format, doc -> {
				delivered.incrementAndGet();
				throw failure;
			}));
			assertThat(actual).isSameAs(failure);
			assertThat(delivered).hasValue(1);
			assertThat(reader.closed).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "csv", "xml", "markdown"})
	void readerFailuresPropagateWithoutClosingReader(String format) {
		IOException failure = new IOException("read failure");
		TrackingReader reader = new TrackingReader(simpleInput(format)) {
			@Override
			public int read(char[] buffer, int offset, int length) throws IOException {
				throw failure;
			}
		};
		assertThatThrownBy(() -> creator.stream(reader, format, doc -> {
		})).isInstanceOf(DocumentProcessingException.class).hasRootCause(failure);
		assertThat(reader.closed).isFalse();
	}

	@ParameterizedTest
	@ValueSource(
			strings = {"<!DOCTYPE root>", "<!DOCTYPE root [<!ENTITY value 'expanded'>]>",
					"<!DOCTYPE root SYSTEM 'http://127.0.0.1:9/no-network'>",
					"<!DOCTYPE root [<!ENTITY % remote SYSTEM 'http://127.0.0.1:9/no-network'>%remote;]>"})
	void rejectsEveryDoctypeBeforeEmittingDocuments(String doctype) {
		AtomicInteger delivered = new AtomicInteger();
		assertThatThrownBy(() -> creator.stream(new StringReader(doctype + "<root>text</root>"), "xml",
				doc -> delivered.incrementAndGet())).isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("DTD");
		assertThat(delivered).hasValue(0);
	}

	@Test
	void rejectsExternalFileEntities() throws IOException {
		Path secret = directory.resolve("secret.txt");
		Files.writeString(secret, "not-for-indexing");
		String xml = "<!DOCTYPE root [<!ENTITY value SYSTEM '" + secret.toUri() + "'>]><root>&value;</root>";
		assertThatThrownBy(() -> creator.stream(new StringReader(xml), "xml", doc -> {
			throw new AssertionError("Must not emit entity content");
		})).isInstanceOf(DocumentProcessingException.class).hasMessageContaining("DTD");
	}

	@Test
	void doesNotResolveXInclude() throws IOException {
		Path secret = directory.resolve("included.txt");
		Files.writeString(secret, "not-for-indexing");
		String xml = "<root xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='" + secret.toUri()
				+ "' parse='text'/></root>";
		List<SolrInputDocument> documents = new ArrayList<>();
		creator.stream(new StringReader(xml), "xml", documents::add);
		assertThat(documents).hasSize(1);
		assertThat(fields(documents.getFirst())).isEqualTo(fields(inline("xml", xml).getFirst()));
		assertThat(documents.toString()).doesNotContain("not-for-indexing");
	}

	@Test
	void rejectsUnsupportedFormatWithoutReadingOrClosing() {
		TrackingReader reader = new TrackingReader("unused");
		assertThatThrownBy(() -> creator.stream(reader, "yaml", doc -> {
		})).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yaml");
		assertThat(reader.closed).isFalse();
	}

	private static String simpleInput(String format) {
		return switch (format) {
			case "json" -> "[{\"id\":1},{\"id\":2}]";
			case "csv" -> "id\n1\n2\n";
			case "xml" -> "<records><record id='1'/><record id='2'/></records>";
			default -> "# Markdown";
		};
	}

	private List<SolrInputDocument> inline(String format, String input) {
		return switch (format) {
			case "json" -> creator.createSchemalessDocumentsFromJson(input);
			case "csv" -> creator.createSchemalessDocumentsFromCsv(input);
			case "xml" -> creator.createSchemalessDocumentsFromXml(input);
			default -> creator.createSchemalessDocumentsFromMarkdown(input);
		};
	}

	private static Map<String, Collection<Object>> fields(SolrInputDocument doc) {
		Map<String, Collection<Object>> fields = new LinkedHashMap<>();
		for (String name : doc.getFieldNames()) {
			fields.put(name, doc.getFieldValues(name));
		}
		return fields;
	}

	private static class TrackingReader extends StringReader {

		boolean closed;

		TrackingReader(String input) {
			super(input);
		}

		@Override
		public void close() {
			closed = true;
			super.close();
		}
	}

	private static final class RepeatingReader extends Reader {

		private final String prefix;

		private final String record;

		private final String suffix;

		private final int repetitions;

		private final AtomicInteger delivered;

		private final long length;

		private long position;

		private boolean closed;

		private RepeatingReader(String prefix, String record, String suffix, int repetitions, AtomicInteger delivered) {
			this.prefix = prefix;
			this.record = record;
			this.suffix = suffix;
			this.repetitions = repetitions;
			this.delivered = delivered;
			this.length = prefix.length() + (long) repetitions * record.length() + suffix.length();
		}

		@Override
		public int read(char[] buffer, int offset, int requested) {
			if (requested == 0) {
				return 0;
			}
			if (position == length) {
				return -1;
			}
			// Permit bounded parser read-ahead, but never whole-file buffering or
			// collecting documents before invoking the consumer.
			assertThat(position).isLessThanOrEqualTo((delivered.get() + 2L) * record.length() + 32768);
			int count = (int) Math.min(Math.min(requested, 257), length - position);
			for (int i = 0; i < count; i++, position++) {
				long bodyPosition = position - prefix.length();
				if (bodyPosition < 0) {
					buffer[offset + i] = prefix.charAt((int) position);
				} else if (bodyPosition < (long) repetitions * record.length()) {
					buffer[offset + i] = record.charAt((int) (bodyPosition % record.length()));
				} else {
					buffer[offset + i] = suffix.charAt((int) (bodyPosition - (long) repetitions * record.length()));
				}
			}
			return count;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
