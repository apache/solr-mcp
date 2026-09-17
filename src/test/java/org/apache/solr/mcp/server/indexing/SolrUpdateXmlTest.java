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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.junit.jupiter.api.Test;

/**
 * Solr's update XML grammar carries {@code <delete>}, {@code <commit>} and
 * {@code <optimize>} on the same endpoint as {@code <add>}. These tests pin the
 * one thing the server does with an XML payload before forwarding it: refuse
 * anything that is not an {@code <add>} block.
 */
class SolrUpdateXmlTest {

	@Test
	void addBlockIsAccepted() {
		assertThatCode(() -> SolrUpdateXml.requireAddBlock("""
				<?xml version="1.0" encoding="UTF-8"?>
				<!-- shows -->
				<add>
				  <doc><field name="id">1</field><field name="genres">a</field><field name="genres">b</field></doc>
				</add>
				""")).doesNotThrowAnyException();
	}

	@Test
	void commandsOtherThanAddAreRejected() {
		for (String command : List.of("<delete><query>*:*</query></delete>", "<commit/>", "<optimize/>", "<rollback/>",
				"<shows><show><id>x</id></show></shows>")) {
			assertThatThrownBy(() -> SolrUpdateXml.requireAddBlock(command)).as(command)
					.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("<add>");
		}
	}

	@Test
	void doctypeIsRejectedSoEntitiesCanNeverResolve() {
		String xxe = """
				<!DOCTYPE add [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
				<add><doc><field name="id">&xxe;</field></doc></add>
				""";
		assertThatThrownBy(() -> SolrUpdateXml.requireAddBlock(xxe)).isInstanceOf(DocumentProcessingException.class);
	}

	@Test
	void blankAndUnparseableInputAreRejected() {
		assertThatThrownBy(() -> SolrUpdateXml.requireAddBlock("  ")).isInstanceOf(DocumentProcessingException.class)
				.hasMessage("XML input cannot be empty");
		assertThatThrownBy(() -> SolrUpdateXml.requireAddBlock("not xml at all"))
				.isInstanceOf(DocumentProcessingException.class);
	}
}
