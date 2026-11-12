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
package org.apache.solr.mcp.server.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.judge.JevJudgeInput.ToolCall;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Runs a real {@link ChatClient} tool loop over a scripted model, so the advisor sees tool calls
 * the way it does in an eval.
 */
class ToolCallAuditAdvisorTest {

  private static final String DEVNEXUS =
      """
			{"collection": "devnexus"}
			""";

  @Test
  void recordsEveryToolCallInOrder() {
    var model =
        new ScriptedChatModel(
            toolCall("list-collections", "{}"),
            toolCall("search", DEVNEXUS),
            answer("There are three workshops."));
    ChatClient chatClient =
        ChatClient.builder(model)
            .defaultTools(
                tool("list-collections", "[\"devnexus\"]"), tool("search", "{\"numFound\": 3}"))
            .defaultAdvisors(new ToolCallAuditAdvisor())
            .build();

    var response = chatClient.prompt("List the workshops.").call().chatClientResponse();

    assertThat(ToolCallAuditAdvisor.toolCalls(response))
        .containsExactly(
            new ToolCall("list-collections", "{}", "[\"devnexus\"]"),
            new ToolCall("search", DEVNEXUS, "{\"numFound\": 3}"));
  }

  @Test
  void recordsNoToolCallsWhenTheModelAnswersDirectly() {
    var model = new ScriptedChatModel(answer("I can't tell without searching."));
    ChatClient chatClient =
        ChatClient.builder(model)
            .defaultTools(tool("search", "{}"))
            .defaultAdvisors(new ToolCallAuditAdvisor())
            .build();

    var response = chatClient.prompt("List the workshops.").call().chatClientResponse();

    assertThat(ToolCallAuditAdvisor.toolCalls(response)).isEmpty();
  }

  private static ChatResponse toolCall(String tool, String arguments) {
    var call = new AssistantMessage.ToolCall(tool + "-id", "function", tool, arguments);
    AssistantMessage message =
        AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  private static ChatResponse answer(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ToolCallback tool(String name, String result) {
    ToolDefinition definition =
        DefaultToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        return result;
      }
    };
  }

  /** Returns its responses in order, one per model call. */
  private static final class ScriptedChatModel implements ChatModel {

    private final Deque<ChatResponse> responses;

    ScriptedChatModel(ChatResponse... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      return responses.removeFirst();
    }

    @Override
    public ChatOptions getOptions() {
      return ToolCallingChatOptions.builder().build();
    }
  }
}
