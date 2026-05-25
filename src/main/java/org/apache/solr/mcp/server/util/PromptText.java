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

/** Shared text-shaping helpers for {@code @McpPrompt} method bodies. */
public final class PromptText {

	private PromptText() {
		// Utility class - prevent instantiation
	}

	/**
	 * Renders an "optional sample" section of a prompt body. When {@code content}
	 * is supplied, emits a bulleted line introducing the sample and an indented
	 * fenced code block holding it; when absent or blank, emits a different
	 * bulleted line asking the LLM to request the sample.
	 *
	 * @param content
	 *            the raw user-supplied content (may be {@code null} or blank)
	 * @param presentLead
	 *            the lead text for the "content provided" case
	 * @param absentLine
	 *            the full bullet line for the "no content" case
	 * @return a string ready to be interpolated into a Java text block
	 */
	public static String optionalCodeBlock(String content, String presentLead, String absentLine) {
		if (content == null || content.isBlank()) {
			return "   - " + absentLine;
		}
		String indented = content.strip().replace("\n", "\n     ");
		return "   - " + presentLead + "\n\n     ```\n     " + indented + "\n     ```";
	}
}
