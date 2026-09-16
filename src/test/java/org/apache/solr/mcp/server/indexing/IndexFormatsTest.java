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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IndexFormatsTest {

	@ParameterizedTest
	@CsvSource({"json,json", "csv,csv", "xml,xml", "md,markdown", "markdown,markdown", "JSON,json", " Csv ,csv",
			"MD,markdown"})
	void normalizesKeywordsCaseInsensitivelyAndTrimmed(String keyword, String expected) {
		assertEquals(expected, IndexFormats.normalize(keyword));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "  ", "yaml", "txt", "html", "jsonl"})
	void rejectsUnknownOrBlankKeywords(String keyword) {
		var e = assertThrows(IllegalArgumentException.class, () -> IndexFormats.normalize(keyword));
		assertEquals("Cannot determine the file format. Supply format=json, csv, xml or markdown.", e.getMessage());
	}
}
