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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;

/**
 * Unwraps the MCP result types the server's prompts, resources and completions return, asserting
 * the parts every one of them must have on the way.
 */
public final class McpResultAssertions {

  private McpResultAssertions() {}

  /**
   * The text of a prompt result, after asserting it has a description and exactly one {@code user}
   * text message.
   */
  public static String promptText(GetPromptResult result) {
    assertNotNull(result.description(), "A prompt result must carry its description");
    assertFalse(result.description().isBlank(), "A prompt result must carry its description");
    assertEquals(1, result.messages().size(), "A prompt result holds one message");
    assertEquals(
        Role.USER, result.messages().getFirst().role(), "Prompt instructions are a user message");
    return assertInstanceOf(TextContent.class, result.messages().getFirst().content()).text();
  }

  /**
   * The JSON of a resource result, after asserting it holds exactly one {@code application/json}
   * text content for {@code uri}.
   */
  public static String resourceJson(ReadResourceResult result, String uri) {
    assertEquals(1, result.contents().size(), "A resource result holds one content");
    TextResourceContents contents =
        assertInstanceOf(TextResourceContents.class, result.contents().getFirst());
    assertEquals(uri, contents.uri());
    assertEquals("application/json", contents.mimeType());
    return contents.text();
  }

  /** The values of a completion result. */
  public static List<String> completionValues(CompleteResult result) {
    return result.completion().values();
  }
}
