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

import java.io.StringReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;

/**
 * Keeps the XML indexing tool to Solr {@code <add>} blocks. Solr's update XML
 * grammar is a command language: {@code <delete>}, {@code <commit>},
 * {@code <optimize>} and {@code <rollback>} go to the same endpoint as
 * {@code <add>}, so a tool that forwarded blindly would let an indexing call
 * delete a collection. The payload itself is forwarded unchanged; Solr parses
 * it.
 */
final class SolrUpdateXml {

	private SolrUpdateXml() {
	}

	/**
	 * Reads {@code xml} with a hardened StAX parser (no DTD, no external entities)
	 * up to its root element and checks that the root is {@code <add>}.
	 *
	 * @throws DocumentProcessingException
	 *             if the input is blank, carries a DOCTYPE, has no root element, or
	 *             is rooted at anything but {@code <add>}
	 */
	static void requireAddBlock(String xml) {
		if (xml.isBlank()) {
			throw new DocumentProcessingException("XML input cannot be empty");
		}
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		try (var xmlReader = new ClosingReader(factory.createXMLStreamReader(new StringReader(xml)))) {
			// nextTag() skips the prolog, comments and whitespace and throws on
			// anything else before the root, a DOCTYPE included.
			xmlReader.reader().nextTag();
			String root = xmlReader.reader().getLocalName();
			if (!"add".equals(root)) {
				throw new DocumentProcessingException("XML input must be a Solr <add> block containing <doc>"
						+ " elements; <" + root + "> is not accepted by this tool");
			}
		} catch (XMLStreamException e) {
			throw new DocumentProcessingException("Failed to parse XML document", e);
		}
	}

	/** {@link XMLStreamReader} is not {@link AutoCloseable}; this makes it so. */
	private record ClosingReader(XMLStreamReader reader) implements AutoCloseable {
		@Override
		public void close() throws XMLStreamException {
			reader.close();
		}
	}
}
