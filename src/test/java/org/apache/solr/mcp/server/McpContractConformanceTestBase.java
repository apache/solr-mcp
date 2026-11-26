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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks every tool that publishes an {@code outputSchema} against the contract rules of the {@code
 * mcp-contracts} spec, once per protocol.
 *
 * <p>Each such tool is called against a real Solr. The call must succeed, which means the MCP SDK's
 * own check of {@code structuredContent} against the published schema passed; the schema must
 * describe an object, which the SDK does not check; the first text block must hold the same JSON,
 * for clients that read only text; and the result must carry no {@code success} or {@code message}
 * status property, since a failure is reported as an error result instead.
 *
 * <p>Like Spring AI, which publishes MCP output schemas open to additional properties, this test
 * does not close them: a result can only carry an undeclared property through a
 * {@code @JsonAnyGetter} or a {@code Map} component, and the records are written without the one
 * and with maps only where the contract is open.
 *
 * <p>Subclasses supply the transport: {@link #listTools()} and {@link #callTool(String, Map)} work
 * on the raw JSON-RPC shapes, so both protocols are checked against the same expectations.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class McpContractConformanceTestBase {

  protected static final String COLLECTION = "contract-conformance";

  /** The alias {@code delete-alias} deletes, created up front so that test can run alone. */
  private static final String ALIAS_TO_DELETE = "contract-alias-to-delete";

  protected static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Arguments for calling each tool that publishes an output schema. A tool that starts publishing
   * one needs an entry here, or {@link #everyToolWithAnOutputSchemaHasSampleArguments()} fails.
   */
  private static final Map<String, Map<String, Object>> SAMPLE_ARGUMENTS =
      Map.ofEntries(
          Map.entry(
              "add-field-types",
              Map.of(
                  "collection",
                  COLLECTION,
                  "fieldTypes",
                  List.of(
                      Map.of(
                          "name",
                          "contract_text",
                          "className",
                          "solr.TextField",
                          "analyzer",
                          Map.of(
                              "tokenizer",
                              Map.of("className", "solr.StandardTokenizerFactory")))))),
          Map.entry(
              "add-fields",
              Map.of(
                  "collection",
                  COLLECTION,
                  "fields",
                  List.of(Map.of("name", "contract_year", "type", "pint")))),
          Map.entry("check-health", Map.of("collection", COLLECTION)),
          Map.entry(
              "create-alias", Map.of("name", "contract-alias", "collections", List.of(COLLECTION))),
          Map.entry("create-collection", Map.of("name", COLLECTION + "-created")),
          Map.entry("delete-alias", Map.of("name", ALIAS_TO_DELETE)),
          Map.entry("get-collection-stats", Map.of("collection", COLLECTION)),
          Map.entry("get-schema", Map.of("collection", COLLECTION)),
          Map.entry("search", Map.of("collection", COLLECTION, "query", "*:*")));

  /** The {@code tools} array of a {@code tools/list} result. */
  protected abstract List<Map<String, Object>> listTools() throws Exception;

  /** The result object of a {@code tools/call}. */
  protected abstract Map<String, Object> callTool(String name, Map<String, Object> arguments)
      throws Exception;

  @BeforeAll
  void createCollectionAndAlias() throws Exception {
    Map<String, Object> created = callTool("create-collection", Map.of("name", COLLECTION));
    assertNotEquals(Boolean.TRUE, created.get("isError"), () -> "create-collection: " + created);
    Map<String, Object> aliased =
        callTool(
            "create-alias", Map.of("name", ALIAS_TO_DELETE, "collections", List.of(COLLECTION)));
    assertNotEquals(Boolean.TRUE, aliased.get("isError"), () -> "create-alias: " + aliased);
  }

  @Test
  void everyOutputSchemaDescribesAnObject() throws Exception {
    Map<String, Map<String, Object>> schemas = outputSchemas();
    assertTrue(schemas.containsKey("check-health"), "check-health must publish an outputSchema");
    for (String listTool : List.of("list-aliases", "list-collections", "list-fields")) {
      assertFalse(
          schemas.containsKey(listTool),
          () -> listTool + " returns a list, so it must not publish an object-only outputSchema");
    }
    for (Map.Entry<String, Map<String, Object>> schema : schemas.entrySet()) {
      assertEquals(
          "object",
          schema.getValue().get("type"),
          () ->
              schema.getKey() + ": an outputSchema must describe an object: " + schema.getValue());
    }
  }

  @Test
  void everyToolWithAnOutputSchemaHasSampleArguments() throws Exception {
    for (String tool : outputSchemas().keySet()) {
      assertTrue(
          SAMPLE_ARGUMENTS.containsKey(tool),
          () -> tool + " publishes an outputSchema but has no SAMPLE_ARGUMENTS entry");
    }
  }

  @TestFactory
  Stream<DynamicTest> everyToolWithAnOutputSchemaReturnsStructuredContentAndItsText()
      throws Exception {
    return outputSchemas().keySet().stream()
        .map(tool -> DynamicTest.dynamicTest(tool, () -> assertConforms(tool)));
  }

  /**
   * Calls {@code tool} and checks its result. A successful call is itself the schema check: the MCP
   * SDK validates {@code structuredContent} against the published {@code outputSchema} on every
   * call and turns a mismatch into an error result.
   */
  @SuppressWarnings("unchecked")
  private void assertConforms(String tool) throws Exception {
    Map<String, Object> result = callTool(tool, SAMPLE_ARGUMENTS.get(tool));
    assertNotEquals(Boolean.TRUE, result.get("isError"), () -> tool + " failed: " + result);

    Map<?, ?> structured =
        assertInstanceOf(
            Map.class,
            result.get("structuredContent"),
            () -> tool + " must return object structuredContent");
    for (String statusFlag : List.of("success", "message")) {
      assertFalse(
          structured.containsKey(statusFlag),
          () -> tool + ": a successful result carries no " + statusFlag + " flag: " + structured);
    }

    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    assertNotNull(content, () -> tool + " must also return text content");
    Map<String, Object> first = content.getFirst();
    assertEquals("text", first.get("type"), () -> tool + ": first content block must be text");
    assertEquals(
        JSON.valueToTree(structured),
        JSON.readTree((String) first.get("text")),
        () -> tool + ": the text block must hold the same JSON as structuredContent");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> outputSchemas() throws Exception {
    Map<String, Map<String, Object>> schemas = new TreeMap<>();
    for (Map<String, Object> tool : listTools()) {
      if (tool.get("outputSchema") instanceof Map<?, ?> schema && !schema.isEmpty()) {
        schemas.put((String) tool.get("name"), (Map<String, Object>) schema);
      }
    }
    return schemas;
  }
}
