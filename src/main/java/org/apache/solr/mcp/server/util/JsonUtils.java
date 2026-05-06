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
package org.apache.solr.mcp.server.util;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Utility class for JSON serialization operations.
 *
 * <p>
 * Provides common JSON conversion methods used throughout the MCP server,
 * particularly for MCP Resource responses that need to return JSON strings.
 *
 * @version 0.0.1
 * @since 0.0.1
 */
public final class JsonUtils {

	private JsonUtils() {
		// Utility class - prevent instantiation
	}

	/**
	 * Converts an object to its JSON string representation.
	 *
	 * <p>
	 * Used by MCP Resource methods that need to return serialized JSON responses.
	 * On serialization failure, returns an error JSON object.
	 *
	 * @param objectMapper
	 *            the Jackson ObjectMapper for serialization
	 * @param obj
	 *            the object to serialize
	 * @return JSON string representation, or error JSON on failure
	 */
	public static String toJson(ObjectMapper objectMapper, Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (JacksonException e) {
			return "{\"error\": \"Failed to serialize response\"}";
		}
	}
}
