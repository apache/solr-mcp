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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.judge.JevJudgeInput.ToolCall;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The code checks on a run: did the model call the expected tool, avoid the forbidden ones, and
 * pass the expected arguments?
 */
final class ScenarioScorer {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

  private ScenarioScorer() {}

  /** Returns one line per failed check, or nothing if the run passed. */
  static List<String> failures(Scenario scenario, List<ToolCall> toolCalls) {
    List<String> failures = new ArrayList<>();

    Optional<ToolCall> last = lastCallTo(scenario.tool(), toolCalls);
    if (last.isEmpty()) {
      failures.add("tool-selected: " + scenario.tool() + " was not called");
    }

    for (String name : forbiddenToolsCalled(scenario, toolCalls)) {
      failures.add("no-forbidden-tool: " + name + " was called");
    }

    if (last.isPresent()) {
      Map<String, Object> actual = JSON.readValue(last.get().arguments(), ARGUMENTS);
      scenario
          .args()
          .forEach(
              (name, expected) -> {
                if (!argMatches(expected, actual.get(name))) {
                  failures.add(
                      "args-match: "
                          + name
                          + " was "
                          + actual.get(name)
                          + ", expected "
                          + expected);
                }
              });
    }

    return failures;
  }

  /**
   * A scalar matches by its text, so {@code 10} matches {@code "10"}. A list matches if every
   * expected entry is in the actual list, in any order and ignoring case and double quotes: {@code
   * session_type:"workshop"} matches the same documents as {@code session_type:Workshop} on a
   * schemaless text field.
   */
  static boolean argMatches(Object expected, @Nullable Object actual) {
    if (expected instanceof List<?> entries) {
      return actual instanceof List<?> list && containsAll(list, entries);
    }
    return actual != null && String.valueOf(expected).equals(String.valueOf(actual));
  }

  /**
   * The last call is the one the answer is built from; earlier ones may be discovery, such as a
   * facet search for the values a field holds.
   */
  private static Optional<ToolCall> lastCallTo(String tool, List<ToolCall> toolCalls) {
    return toolCalls.stream()
        .filter(call -> call.name().equals(tool))
        .reduce((earlier, later) -> later);
  }

  private static List<String> forbiddenToolsCalled(Scenario scenario, List<ToolCall> toolCalls) {
    return toolCalls.stream()
        .map(ToolCall::name)
        .filter(scenario.forbiddenTools()::contains)
        .distinct()
        .toList();
  }

  private static boolean containsAll(List<?> actual, List<?> expected) {
    return expected.stream()
        .allMatch(
            entry ->
                actual.stream().anyMatch(item -> unquoted(item).equalsIgnoreCase(unquoted(entry))));
  }

  private static String unquoted(@Nullable Object value) {
    return String.valueOf(value).replace("\"", "");
  }
}
