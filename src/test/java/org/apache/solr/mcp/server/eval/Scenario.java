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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One prompt and what the model should do with it.
 *
 * @param id a short, unique name for reports
 * @param prompt the user's message
 * @param tool the tool the model should call
 * @param forbiddenTools confusable neighbours the model must not call
 * @param args the arguments the first call to {@code tool} must carry; a list value must be
 *     contained in the actual list
 */
record Scenario(
    String id, String prompt, String tool, List<String> forbiddenTools, Map<String, Object> args) {

  static final String RESOURCE = "/eval/scenarios.json";

  private static final TypeReference<List<Scenario>> SCENARIOS = new TypeReference<>() {};

  Scenario {
    forbiddenTools = Objects.requireNonNullElse(forbiddenTools, List.of());
    args = Objects.requireNonNullElse(args, Map.of());
  }

  static List<Scenario> load() {
    try (InputStream in =
        Objects.requireNonNull(Scenario.class.getResourceAsStream(RESOURCE), RESOURCE)) {
      JsonMapper json = JsonMapper.builder().build();
      return json.readValue(in, SCENARIOS);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
