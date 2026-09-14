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
import org.springframework.util.xml.StaxUtils;

/**
 * Keeps the XML indexing tool to Solr {@code <add>} blocks. Solr's update XML
 * grammar is a command language: {@code <delete>}, {@code <commit>},
 * {@code <optimize>} and {@code <rollback>} go to the same endpoint as
 * {@code <add>}, so a tool that forwarded blindly would let an indexing call
 * delete a collection. The payload itself is forwarded unchanged; Solr parses
 * it.
 */
final class SolrUpdateXml {

	/**
	 * Spring's defensive factory: DTD support off, external entities off, and a
	 * no-op {@code XMLResolver}. Shared because
	 * {@link XMLInputFactory#newFactory()} runs a {@code ServiceLoader} scan of the
	 * whole classpath on every call. Configured once here and never reconfigured,
	 * which is the sharing contract StAX requires — see Spring's own
	 * {@code XmlEventDecoder}, which holds this factory in a static field and reads
	 * from it concurrently.
	 */
	private static final XMLInputFactory INPUT_FACTORY = StaxUtils.createDefensiveInputFactory();

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
		try {
			XMLStreamReader reader = INPUT_FACTORY.createXMLStreamReader(new StringReader(xml));
			try {
				// nextTag() skips the prolog, comments and whitespace and throws on
				// anything else before the root, a DOCTYPE included.
				reader.nextTag();
				String root = reader.getLocalName();
				if (!"add".equals(root)) {
					throw new DocumentProcessingException("XML input must be a Solr <add> block containing <doc>"
							+ " elements; <" + root + "> is not accepted by this tool");
				}
			} finally {
				reader.close();
			}
		} catch (XMLStreamException e) {
			throw new DocumentProcessingException("Failed to parse XML document", e);
		}
	}
}
