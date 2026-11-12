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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.judge.JevJudgeInput.ToolCall;

class ScenarioScorerTest {

  private static final Scenario WORKSHOPS =
      new Scenario(
          "workshops-by-type",
          "List the workshops in the devnexus collection.",
          "search",
          List.of("get-collection-stats"),
          Map.of("collection", "devnexus", "filterQueries", List.of("session_type:Workshop")));

  private static final ToolCall WORKSHOP_SEARCH =
      call(
          "search",
          """
			{"collection": "devnexus", "filterQueries": ["session_type:Workshop"]}
			""");

  @Test
  void discoveryCallsBeforeTheExpectedToolAreFine() {
    var run =
        run(
            call("list-collections", "{}"),
            call(
                "get-schema",
                """
				{"collection": "devnexus"}
				"""),
            WORKSHOP_SEARCH);

    assertThat(ScenarioScorer.failures(WORKSHOPS, run)).isEmpty();
  }

  @Test
  void failsWhenTheExpectedToolIsNeverCalled() {
    var run = run(call("list-collections", "{}"));

    assertThat(ScenarioScorer.failures(WORKSHOPS, run))
        .containsExactly("tool-selected: search was not called");
  }

  @Test
  void failsOnAForbiddenCall() {
    var run =
        run(
            call(
                "get-collection-stats",
                """
				{"collection": "devnexus"}
				"""),
            WORKSHOP_SEARCH);

    assertThat(ScenarioScorer.failures(WORKSHOPS, run))
        .containsExactly("no-forbidden-tool: get-collection-stats was called");
  }

  @Test
  void argsAreCheckedOnTheLastCall() {
    var run =
        run(
            call(
                "search",
                """
				{"collection": "devnexus", "facetFields": ["session_type"], "rows": 0}
				"""),
            WORKSHOP_SEARCH);

    assertThat(ScenarioScorer.failures(WORKSHOPS, run)).isEmpty();
  }

  @Test
  void aWrongLastCallFailsEvenAfterARightOne() {
    var run =
        run(
            WORKSHOP_SEARCH,
            call(
                "search",
                """
				{"collection": "devnexus", "query": "workshop"}
				"""));

    assertThat(ScenarioScorer.failures(WORKSHOPS, run))
        .containsExactly("args-match: filterQueries was null, expected [session_type:Workshop]");
  }

  @Test
  void aFilterInTheQueryInsteadOfFilterQueriesFails() {
    var run =
        run(
            call(
                "search",
                """
				{"collection": "devnexus", "query": "session_type:Workshop", "filterQueries": []}
				"""));

    assertThat(ScenarioScorer.failures(WORKSHOPS, run))
        .containsExactly("args-match: filterQueries was [], expected [session_type:Workshop]");
  }

  @Test
  void filterQueriesMayHoldMoreEntriesInAnyCaseOrQuoted() {
    var run =
        run(
            call(
                "search",
                """
				{"collection": "devnexus", "filterQueries": ["day:\\"Day 0\\"", "session_type:\\"workshop\\""]}
				"""));

    assertThat(ScenarioScorer.failures(WORKSHOPS, run)).isEmpty();
  }

  @Test
  void scalarsMatchExactly() {
    var run =
        run(
            call(
                "search",
                """
				{"collection": "DevNexus", "filterQueries": ["session_type:Workshop"]}
				"""));

    assertThat(ScenarioScorer.failures(WORKSHOPS, run))
        .containsExactly("args-match: collection was DevNexus, expected devnexus");
  }

  @Test
  void aNumberMatchesItsText() {
    assertThat(ScenarioScorer.argMatches("10", 10)).isTrue();
    assertThat(ScenarioScorer.argMatches(10, "10")).isTrue();
    assertThat(ScenarioScorer.argMatches(10, null)).isFalse();
  }

  private static ToolCall call(String name, String arguments) {
    return new ToolCall(name, arguments, "{}");
  }

  private static List<ToolCall> run(ToolCall... calls) {
    return List.of(calls);
  }
}
