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

import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import org.springframework.util.MimeTypeUtils;

/**
 * Builds the MCP result types the server's prompts, resources and completions return.
 *
 * <p>Returning these types, instead of letting the framework wrap a {@code String} or a list, keeps
 * what the client receives explicit: a prompt carries its description and the role of its speaker
 * (the framework's {@code String} wrapping always uses {@code assistant} and no description), a
 * resource states its URI and MIME type, and a completion reports how many values matched.
 */
public final class McpResults {

  private McpResults() {
    // Utility class - prevent instantiation
  }

  /**
   * A prompt result holding one text message.
   *
   * <p>MCP's {@code role} names the speaker of a message. {@link Role#USER} is the person using the
   * client: a request or instructions for the model, which is what the server's prompts are. {@link
   * Role#ASSISTANT} is the model's own turn, which clients insert as if the model had already said
   * it; use it only for a message the model should treat as its own earlier reply.
   *
   * @param description the prompt's description, as declared on its {@code @McpPrompt}
   * @param role the speaker of the message
   * @param text the message text
   * @return the prompt result
   */
  public static GetPromptResult prompt(String description, Role role, String text) {
    return new GetPromptResult(
        description, List.of(new PromptMessage(role, new TextContent(text))));
  }

  /**
   * A resource result holding one JSON text content.
   *
   * @param uri the concrete URI that was read, with any template variables filled in
   * @param json the resource's JSON
   * @return the resource result
   */
  public static ReadResourceResult jsonResource(String uri, String json) {
    return new ReadResourceResult(
        List.of(new TextResourceContents(uri, MimeTypeUtils.APPLICATION_JSON_VALUE, json)));
  }

  /**
   * A completion result showing at most {@code limit} of {@code matches}, with the total number of
   * matches and whether some were left out.
   *
   * @param matches every value that matches, in the order to show them
   * @param limit the largest number of values to return
   * @return the completion result
   */
  public static CompleteResult completion(List<String> matches, int limit) {
    List<String> shown = matches.size() > limit ? matches.subList(0, limit) : matches;
    return new CompleteResult(
        new CompleteCompletion(List.copyOf(shown), matches.size(), matches.size() > limit));
  }
}
