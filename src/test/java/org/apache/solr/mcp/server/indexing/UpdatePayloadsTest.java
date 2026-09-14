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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.junit.jupiter.api.Test;

/**
 * CSV and XML go to Solr's own update handlers; these tests pin the two things
 * the server still does with them: the guard that keeps the XML tool to
 * {@code <add>} blocks, and the count and field names reported back.
 */
class UpdatePayloadsTest {

	@Test
	void xmlAddBlockIsSummarised() {
		UpdatePayloads.Summary summary = UpdatePayloads.inspectXml("""
				<add>
				  <doc><field name="id">1</field><field name="genres">a</field><field name="genres">b</field></doc>
				  <doc><field name="id">2</field><field name="title">T</field></doc>
				</add>
				""");

		assertThat(summary.documents()).isEqualTo(2);
		assertThat(summary.distinctFieldNames()).containsExactly("genres", "id", "title");
	}

	@Test
	void xmlCommandsOtherThanAddAreRejected() {
		for (String command : List.of("<delete><query>*:*</query></delete>", "<commit/>", "<optimize/>", "<rollback/>",
				"<shows><show><id>x</id></show></shows>")) {
			assertThatThrownBy(() -> UpdatePayloads.inspectXml(command)).as(command)
					.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("<add>");
		}
	}

	@Test
	void xmlWithoutDocumentsIsRejected() {
		assertThatThrownBy(() -> UpdatePayloads.inspectXml("<add></add>"))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("no <doc>");
	}

	@Test
	void xmlEntitiesAreNotResolved() {
		String xxe = """
				<!DOCTYPE add [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
				<add><doc><field name="id">&xxe;</field></doc></add>
				""";
		assertThatThrownBy(() -> UpdatePayloads.inspectXml(xxe)).isInstanceOf(DocumentProcessingException.class);
	}

	@Test
	void malformedAndBlankXmlAreRejected() {
		assertThatThrownBy(() -> UpdatePayloads.inspectXml("<add><doc>"))
				.isInstanceOf(DocumentProcessingException.class);
		assertThatThrownBy(() -> UpdatePayloads.inspectXml("  ")).isInstanceOf(DocumentProcessingException.class)
				.hasMessage("XML input cannot be empty");
	}

	@Test
	void csvHeaderIsSanitizedInColumnOrderAndRowsAreCounted() {
		UpdatePayloads.Summary summary = UpdatePayloads.inspectCsv("""
				id,Show Title,genres,genres,product.price
				1,Alpha,a,b,9.99
				2,Beta,c,,8.5

				""");

		assertThat(summary.documents()).isEqualTo(2);
		assertThat(summary.fieldNames()).containsExactly("id", "show_title", "genres", "genres", "product_price");
		assertThat(summary.distinctFieldNames()).containsExactly("genres", "id", "product_price", "show_title");
	}

	@Test
	void blankCsvIsRejected() {
		assertThatThrownBy(() -> UpdatePayloads.inspectCsv("\n")).isInstanceOf(DocumentProcessingException.class)
				.hasMessage("CSV input cannot be empty");
	}
}
