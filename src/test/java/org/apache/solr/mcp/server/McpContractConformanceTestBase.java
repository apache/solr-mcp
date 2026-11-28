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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>Every tool that names a collection, alias or configset is also called with one that does not
 * exist, and must fail as a tool error ({@link McpToolFailures#assertReportsFailure}); {@link
 * McpSolrUnreachableTestBase} does the same with Solr unreachable.
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

  /** The application's mapper, from the {@code @SpringBootTest} context each subclass starts. */
  @Autowired protected JsonMapper jsonMapper;

  private static final String COLLECTION_ARG = "collection";
  private static final String COLLECTIONS_ARG = "collections";
  private static final String DOCUMENTS_ARG = "documents";
  private static final String NAME = "name";

  /** The {@code HealthStatus.Reason} {@code check-health} gives for a missing collection. */
  private static final String COLLECTION_NOT_FOUND = "COLLECTION_NOT_FOUND";

  /** The tools that return a list, so they publish no outputSchema. */
  private static final Set<String> LIST_TOOLS =
      Set.of("list-aliases", "list-collections", "list-fields");

  /**
   * Arguments for calling each tool that publishes an output schema. Every tool outside {@link
   * #LIST_TOOLS} needs an entry here, or {@link #everyToolButTheListToolsPublishesAnOutputSchema()}
   * fails.
   */
  private static final Map<String, Map<String, Object>> SAMPLE_ARGUMENTS =
      Map.ofEntries(
          Map.entry(
              "add-field-types",
              Map.of(
                  COLLECTION_ARG,
                  COLLECTION,
                  "fieldTypes",
                  List.of(
                      Map.of(
                          NAME,
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
                  COLLECTION_ARG,
                  COLLECTION,
                  "fields",
                  List.of(Map.of(NAME, "contract_year", "type", "pint")))),
          Map.entry("check-health", Map.of(COLLECTION_ARG, COLLECTION)),
          Map.entry(
              "create-alias", Map.of(NAME, "contract-alias", COLLECTIONS_ARG, List.of(COLLECTION))),
          Map.entry("create-collection", Map.of(NAME, COLLECTION + "-created")),
          Map.entry("delete-alias", Map.of(NAME, ALIAS_TO_DELETE)),
          Map.entry("get-collection-stats", Map.of(COLLECTION_ARG, COLLECTION)),
          Map.entry("get-schema", Map.of(COLLECTION_ARG, COLLECTION)),
          Map.entry(
              "index-csv-documents",
              Map.of(COLLECTION_ARG, COLLECTION, "csv", "id,title\ncontract-csv-1,Contract CSV")),
          Map.entry(
              "index-json-documents",
              Map.of(
                  COLLECTION_ARG,
                  COLLECTION,
                  DOCUMENTS_ARG,
                  List.of(Map.of("id", "contract-json-1", "title", "Contract JSON")))),
          Map.entry(
              "index-markdown-documents",
              Map.of(
                  COLLECTION_ARG,
                  COLLECTION,
                  DOCUMENTS_ARG,
                  List.of("---\nid: contract-md-1\n---\n# Contract Markdown\n\nBody text."))),
          Map.entry(
              "index-xml-documents",
              Map.of(
                  COLLECTION_ARG,
                  COLLECTION,
                  "xml",
                  "<add><doc><field name=\"id\">contract-xml-1</field></doc></add>")),
          Map.entry("search", Map.of(COLLECTION_ARG, COLLECTION, "query", "*:*")));

  /** The {@code tools} array of a {@code tools/list} result. */
  protected abstract List<Map<String, Object>> listTools() throws Exception;

  /** The result object of a {@code tools/call}. */
  protected abstract Map<String, Object> callTool(String name, Map<String, Object> arguments)
      throws Exception;

  @BeforeAll
  void createCollectionAndAlias() throws Exception {
    Map<String, Object> created = callTool("create-collection", Map.of(NAME, COLLECTION));
    assertNotEquals(Boolean.TRUE, created.get("isError"), () -> "create-collection: " + created);
    Map<String, Object> aliased =
        callTool(
            "create-alias", Map.of(NAME, ALIAS_TO_DELETE, COLLECTIONS_ARG, List.of(COLLECTION)));
    assertNotEquals(Boolean.TRUE, aliased.get("isError"), () -> "create-alias: " + aliased);
  }

  @Test
  void everyOutputSchemaDescribesAnObject() throws Exception {
    for (Map.Entry<String, Map<String, Object>> schema : outputSchemas().entrySet()) {
      assertEquals(
          "object",
          schema.getValue().get("type"),
          () ->
              schema.getKey() + ": an outputSchema must describe an object: " + schema.getValue());
    }
  }

  /**
   * Every tool that returns a record publishes an output schema, and has sample arguments to call
   * it with, so a tool that loses {@code generateOutputSchema} fails here instead of silently
   * dropping out of the dynamic tests. The list tools publish none, because a 2025-11-25 {@code
   * outputSchema} must describe an object.
   */
  @Test
  void everyToolButTheListToolsPublishesAnOutputSchema() throws Exception {
    Set<String> recordTools = new TreeSet<>();
    for (Map<String, Object> tool : listTools()) {
      recordTools.add((String) tool.get(NAME));
    }
    assertTrue(recordTools.containsAll(LIST_TOOLS), () -> "missing list tools: " + recordTools);
    recordTools.removeAll(LIST_TOOLS);

    assertEquals(recordTools, outputSchemas().keySet(), "tools publishing an outputSchema");
    assertEquals(recordTools, new TreeSet<>(SAMPLE_ARGUMENTS.keySet()), "SAMPLE_ARGUMENTS tools");
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
        jsonMapper.valueToTree(structured),
        jsonMapper.readTree((String) first.get("text")),
        () -> tool + ": the text block must hold the same JSON as structuredContent");
  }

  /**
   * Every tool but the two that name nothing has arguments for the missing-target check, so a new
   * tool cannot silently skip it.
   */
  @Test
  void everyToolThatNamesATargetHasMissingTargetArguments() throws Exception {
    Set<String> targetTools = McpToolFailures.toolNames(listTools());
    assertTrue(
        targetTools.containsAll(McpToolFailures.TOOLS_WITHOUT_A_TARGET),
        () -> "missing tools: " + targetTools);
    targetTools.removeAll(McpToolFailures.TOOLS_WITHOUT_A_TARGET);
    assertEquals(
        targetTools,
        new TreeSet<>(McpToolFailures.MISSING_TARGET_ARGUMENTS.keySet()),
        "MISSING_TARGET_ARGUMENTS tools");
  }

  @TestFactory
  Stream<DynamicTest> everyToolCalledOnAMissingTargetReturnsAToolError() {
    return new TreeSet<>(McpToolFailures.MISSING_TARGET_ARGUMENTS.keySet())
        .stream()
            .map(
                tool ->
                    DynamicTest.dynamicTest(
                        tool,
                        () ->
                            McpToolFailures.assertReportsFailure(
                                tool,
                                callTool(tool, McpToolFailures.MISSING_TARGET_ARGUMENTS.get(tool)),
                                COLLECTION_NOT_FOUND,
                                McpToolFailures.missingTargetText(tool))));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> outputSchemas() throws Exception {
    Map<String, Map<String, Object>> schemas = new TreeMap<>();
    for (Map<String, Object> tool : listTools()) {
      if (tool.get("outputSchema") instanceof Map<?, ?> schema && !schema.isEmpty()) {
        schemas.put((String) tool.get(NAME), (Map<String, Object>) schema);
      }
    }
    return schemas;
  }
}
