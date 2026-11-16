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
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springaicommunity.typesafe.judge.JevJudgeInput.ToolCall;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;

/**
 * Runs each scenario {@code eval.runs} times (default 5). Each run is its own test, so the report
 * shows a pass rate per scenario.
 */
@Tag("llm-eval")
@SpringBootTest(
    classes = EvalConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
@DisabledInAotMode
class ToolSelectionEval {

  @Autowired ChatClient chatClient;

  @TestFactory
  Stream<DynamicContainer> scenarios() {
    int runs = Integer.getInteger("eval.runs", 5);
    return Scenario.load().stream()
        .map(scenario -> dynamicContainer(scenario.id(), runs(scenario, runs)));
  }

  private Stream<DynamicTest> runs(Scenario scenario, int runs) {
    return IntStream.rangeClosed(1, runs)
        .mapToObj(run -> dynamicTest("run " + run, () -> check(scenario)));
  }

  private void check(Scenario scenario) {
    ChatClientResponse response = chatClient.prompt(scenario.prompt()).call().chatClientResponse();
    List<ToolCall> toolCalls = ToolCallAuditAdvisor.toolCalls(response);
    List<String> called =
        toolCalls.stream().map(call -> call.name() + " " + call.arguments()).toList();

    assertThat(ScenarioScorer.failures(scenario, toolCalls)).as("tool calls: %s", called).isEmpty();
  }
}
