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
package org.apache.solr.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSON array literal into the typed {@code documents} argument of the
 * {@code index-json-documents} tool, so tests can keep readable JSON text
 * blocks while the tool takes parsed objects.
 */
public final class TestDocuments {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private TestDocuments() {
	}

	/**
	 * Parses a JSON array of objects.
	 *
	 * @param json
	 *            a JSON array literal
	 * @return one map per document
	 */
	public static List<Map<String, Object>> json(String json) {
		try {
			return OBJECT_MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
			});
		} catch (java.io.IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
