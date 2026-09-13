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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every format rejects blank input the same way, in the creator itself, with
 * one message shape. Null is not a case here: the creators are
 * {@code @NullMarked}, so a null argument is a contract violation of the
 * caller, not an input to be validated.
 */
class DocumentCreatorBlankInputTest {

	static Stream<Arguments> blankInputs() {
		return Stream
				.of(Arguments.of(Named.of("JSON", new JsonDocumentCreator(new ObjectMapper())), "JSON"),
						Arguments.of(Named.of("CSV", new CsvDocumentCreator()), "CSV"),
						Arguments.of(Named.of("XML", new XmlDocumentCreator()), "XML"),
						Arguments.of(Named.of("Markdown", new MarkdownDocumentCreator()), "Markdown"))
				.flatMap(creator -> Stream.of(Named.of("empty", ""), Named.of("whitespace", "   \n\t  "))
						.map(input -> Arguments.of(creator.get()[0], creator.get()[1], input)));
	}

	@ParameterizedTest
	@MethodSource("blankInputs")
	void blankInputIsRejectedWithTheFormatName(SolrDocumentCreator creator, String format, String input) {
		assertThatThrownBy(() -> creator.create(input)).isInstanceOf(DocumentProcessingException.class)
				.hasMessage(format + " input cannot be empty");
	}
}
