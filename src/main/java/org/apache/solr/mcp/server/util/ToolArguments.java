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

/**
 * Validation of arguments arriving at MCP tool boundaries.
 *
 * <p>
 * Every {@code @McpTool} method that takes a collection name calls
 * {@link #requireCollection(String)} first, so a client that omits the argument
 * or sends an empty string gets one identical, actionable message instead of a
 * per-service variant or an opaque downstream failure.
 *
 * <p>
 * <strong>Why a runtime check in null-marked code:</strong> the server is
 * {@code @NullMarked} (see {@code org.apache.solr.mcp.server.package-info}) and
 * NullAway runs as a build error, but that analysis only binds callers the
 * compiler can see. MCP tool methods are invoked reflectively by the Spring AI
 * annotation runtime, which resolves each parameter with a plain lookup against
 * the request's argument map and passes the result straight through — a missing
 * or null JSON value therefore reaches the method as {@code null} no matter
 * what the annotations declare. {@code @McpToolParam(required = true)} only
 * marks the parameter required in the advertised JSON schema; the server does
 * not validate incoming arguments against it. These checks are the trust
 * boundary, not redundant defensive coding.
 */
public final class ToolArguments {

	/** Message used whenever a collection name is missing, empty, or blank. */
	public static final String BLANK_COLLECTION_NAME_ERROR = "Collection name cannot be null or empty";

	private ToolArguments() {
	}

	/**
	 * Rejects a missing, empty, or whitespace-only collection name.
	 *
	 * @param collection
	 *            the collection name supplied by the MCP client
	 * @throws IllegalArgumentException
	 *             if {@code collection} is null or contains only whitespace
	 */
	public static void requireCollection(String collection) {
		if (collection == null || collection.isBlank()) {
			throw new IllegalArgumentException(BLANK_COLLECTION_NAME_ERROR);
		}
	}
}
