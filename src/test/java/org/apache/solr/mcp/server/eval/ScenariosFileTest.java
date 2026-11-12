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
import org.junit.jupiter.api.Test;

class ScenariosFileTest {

  @Test
  void parsesWithUniqueIds() {
    List<Scenario> scenarios = Scenario.load();

    assertThat(scenarios).isNotEmpty();
    assertThat(scenarios).extracting(Scenario::id).doesNotHaveDuplicates();
  }

  @Test
  void everyScenarioNamesAPromptAndATool() {
    assertThat(Scenario.load())
        .allSatisfy(
            scenario -> {
              assertThat(scenario.prompt()).isNotBlank();
              assertThat(scenario.tool()).isNotBlank();
              assertThat(scenario.forbiddenTools()).doesNotContain(scenario.tool());
            });
  }
}
