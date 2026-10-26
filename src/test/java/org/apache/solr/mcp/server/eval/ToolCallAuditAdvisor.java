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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springaicommunity.typesafe.judge.JevJudgeInput.ToolCall;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;

/**
 * Records every tool call the model makes during a request, with its arguments and result. Read
 * them back with {@link #toolCalls(ChatClientResponse)}.
 *
 * <p>It observes the tool-calling loop from inside, ordered after {@link ToolCallingAdvisor}, so it
 * runs on every iteration. Each iteration's request carries the turn so far, so when the model
 * answers without asking for another tool, that request holds every tool call and the {@link
 * ToolResponseMessage} that answered it.
 */
final class ToolCallAuditAdvisor implements CallAdvisor {

  static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 100;

  private static final String TOOL_CALLS = ToolCallAuditAdvisor.class.getName() + ".toolCalls";

  @Override
  public String getName() {
    return ToolCallAuditAdvisor.class.getSimpleName();
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);
    if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
      return response;
    }
    return response
        .mutate()
        .context(TOOL_CALLS, toolCalls(request.prompt().getInstructions()))
        .build();
  }

  /** The tool calls this advisor recorded for a response, in order. */
  @SuppressWarnings("unchecked")
  static List<ToolCall> toolCalls(ChatClientResponse response) {
    return (List<ToolCall>)
        Objects.requireNonNull(
            response.context().get(TOOL_CALLS), "ToolCallAuditAdvisor is not on this ChatClient");
  }

  /**
   * Pairs each tool call the model asked for with the result that answered it. In the history, a
   * call is a tool call on an {@link AssistantMessage}, and its result is the {@link ToolResponse}
   * with the same id in a later {@link ToolResponseMessage}.
   */
  private static List<ToolCall> toolCalls(List<Message> history) {
    Map<String, String> resultById = new HashMap<>();
    for (Message message : history) {
      if (message instanceof ToolResponseMessage toolResponses) {
        for (ToolResponse response : toolResponses.getResponses()) {
          resultById.put(response.id(), response.responseData());
        }
      }
    }
    List<ToolCall> toolCalls = new ArrayList<>();
    for (Message message : history) {
      if (message instanceof AssistantMessage assistant) {
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
          String result = resultById.getOrDefault(call.id(), "");
          toolCalls.add(new ToolCall(call.name(), call.arguments(), result));
        }
      }
    }
    return toolCalls;
  }
}
