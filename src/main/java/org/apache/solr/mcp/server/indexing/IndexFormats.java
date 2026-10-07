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

import java.util.Locale;

/**
 * Maps a caller-supplied format keyword or file extension to the canonical
 * format name the document creators understand.
 */
final class IndexFormats {

	static final String UNKNOWN_FORMAT = "Cannot determine the file format. Supply format=json, csv, xml or markdown.";

	private IndexFormats() {
	}

	/**
	 * Returns {@code json}, {@code csv}, {@code xml} or {@code markdown}.
	 *
	 * @param keyword
	 *            a format keyword or file extension, any case, surrounding
	 *            whitespace ignored
	 * @throws IllegalArgumentException
	 *             if the keyword is blank or not a supported format
	 */
	static String normalize(String keyword) {
		return switch (keyword.trim().toLowerCase(Locale.ROOT)) {
			case "json" -> "json";
			case "csv" -> "csv";
			case "xml" -> "xml";
			case "md", "markdown" -> "markdown";
			default -> throw new IllegalArgumentException(UNKNOWN_FORMAT);
		};
	}
}
