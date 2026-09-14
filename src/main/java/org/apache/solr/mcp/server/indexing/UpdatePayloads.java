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

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.apache.solr.mcp.server.indexing.documentcreator.FieldNameSanitizer;

/**
 * Inspects CSV and XML payloads before they are forwarded, unchanged, to Solr's
 * own update handlers. Solr parses these formats itself; the server only needs
 * two things from the payload before handing it over: what to report back (how
 * many documents, which field names), and for XML a guard on the command type,
 * because Solr's update XML grammar also carries {@code <delete>},
 * {@code <commit>} and {@code <optimize>}, which an indexing tool must never
 * forward.
 */
final class UpdatePayloads {

	/** What a payload will index: the document count and the field names. */
	record Summary(int documents, List<String> fieldNames) {
		Set<String> distinctFieldNames() {
			return new TreeSet<>(fieldNames);
		}
	}

	private UpdatePayloads() {
	}

	/**
	 * Checks that {@code xml} is a Solr {@code <add>} block and counts its
	 * documents and field names with a hardened StAX read (no DTD, no external
	 * entities), without building a document model.
	 *
	 * @throws DocumentProcessingException
	 *             if the input is blank, malformed, not rooted at {@code <add>}, or
	 *             carries no {@code <doc>}
	 */
	static Summary inspectXml(String xml) {
		if (xml.isBlank()) {
			throw new DocumentProcessingException("XML input cannot be empty");
		}
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		int documents = 0;
		List<String> fieldNames = new ArrayList<>();
		try {
			XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
			try {
				int depth = 0;
				while (reader.hasNext()) {
					int event = reader.next();
					if (event == XMLStreamConstants.START_ELEMENT) {
						depth++;
						String name = reader.getLocalName();
						if (depth == 1 && !"add".equals(name)) {
							throw new DocumentProcessingException(
									"XML input must be a Solr <add> block containing <doc>" + " elements; <" + name
											+ "> is not accepted by this tool");
						}
						if (depth == 2 && "doc".equals(name)) {
							documents++;
						}
						if ("field".equals(name)) {
							String fieldName = reader.getAttributeValue(null, "name");
							if (fieldName != null && !fieldName.isBlank()) {
								fieldNames.add(fieldName);
							}
						}
					} else if (event == XMLStreamConstants.END_ELEMENT) {
						depth--;
					}
				}
			} finally {
				reader.close();
			}
		} catch (XMLStreamException e) {
			throw new DocumentProcessingException("Failed to parse XML document", e);
		}
		if (documents == 0) {
			throw new DocumentProcessingException("XML input contains no <doc> elements");
		}
		return new Summary(documents, fieldNames);
	}

	/**
	 * Reads the CSV header and counts the data rows. The header names are sanitized
	 * for Solr and returned in column order, repeats included, so they can be
	 * passed to Solr's CSV handler as {@code fieldnames}; Solr then applies one
	 * name per column and repeated names become multi-valued fields.
	 *
	 * @throws DocumentProcessingException
	 *             if the input is blank, has no header, or cannot be parsed
	 */
	static Summary inspectCsv(String csv) {
		if (csv.isBlank()) {
			throw new DocumentProcessingException("CSV input cannot be empty");
		}
		try (CSVParser parser = new CSVParser(new StringReader(csv),
				CSVFormat.Builder.create().setHeader().setTrim(true).build())) {
			List<String> fieldNames = new ArrayList<>();
			for (String header : parser.getHeaderNames()) {
				fieldNames.add(FieldNameSanitizer.sanitizeFieldName(header));
			}
			if (fieldNames.isEmpty()) {
				throw new DocumentProcessingException("CSV input cannot be empty");
			}
			int documents = 0;
			for (CSVRecord record : parser) {
				if (record.size() > 0) {
					documents++;
				}
			}
			return new Summary(documents, fieldNames);
		} catch (IOException | IllegalArgumentException | IllegalStateException e) {
			throw new DocumentProcessingException("Failed to parse CSV document", e);
		}
	}
}
