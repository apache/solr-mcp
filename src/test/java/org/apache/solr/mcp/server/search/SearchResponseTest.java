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
package org.apache.solr.mcp.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchResponseTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The {@code search} tool's output schema types {@code maxScore} as a number and does not require
   * it, so a missing score must be left out: a JSON {@code null} fails the MCP SDK's output
   * validation and turns the call into an error.
   */
  @Test
  void nullMaxScoreIsLeftOutOfTheJson() {
    SearchResponse response = new SearchResponse(0, 0, null, List.of(), List.of());

    JsonNode json = JSON.valueToTree(response);

    assertFalse(json.has("maxScore"), () -> "maxScore must be absent: " + json);
    assertEquals(0, json.get("numFound").asLong());
  }
}
