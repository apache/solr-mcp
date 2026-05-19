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

/**
 * Canonical names of the {@code @McpPrompt} endpoints exposed by this server.
 *
 * <p>
 * Centralised so that prompt bodies that reference sibling prompts ("then
 * invoke the {@code design-schema} prompt …") and the {@code @McpPrompt(name)}
 * declarations share a single source of truth and cannot drift apart.
 */
public final class PromptNames {

	private PromptNames() {
		// Constants holder - prevent instantiation
	}

	/** Read-only walkthrough: list and characterise existing collections. */
	public static final String EXPLORE_COLLECTIONS = "explore-collections";

	/**
	 * Guided setup of a new Solr collection. Uses a distinct verb from the
	 * {@code create-collection} tool to avoid name collision in MCP clients that
	 * surface both.
	 */
	public static final String SETUP_COLLECTION = "setup-collection";

	/** Read-only schema introspection walkthrough. */
	public static final String VIEW_SCHEMA = "view-schema";

	/**
	 * Mutating schema-design walkthrough: add field types and fields for a
	 * described dataset.
	 */
	public static final String DESIGN_SCHEMA = "design-schema";

	/** Walkthrough for indexing documents in JSON / CSV / XML format. */
	public static final String INDEX_DATA = "index-data";

	/**
	 * Walkthrough for translating a natural-language question into a Solr search.
	 */
	public static final String SEARCH_COLLECTION = "search-collection";
}
