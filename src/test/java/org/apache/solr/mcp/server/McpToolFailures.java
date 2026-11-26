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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Arguments and assertions for calling every tool when it cannot succeed, shared by the
 * missing-collection check in {@link McpContractConformanceTestBase} and the Solr-unreachable check
 * in {@link McpSolrUnreachableTestBase}, on both protocols.
 *
 * <p>A failing tool must answer with a {@code tools/call} result whose {@code isError} is {@code
 * true}, never a JSON-RPC error, and the text it carries must not contain SolrJ's wording or any
 * URL. {@code check-health} is the one tool that reports a failure as its result instead: its
 * {@code reason} is one of the server's fixed values.
 */
public final class McpToolFailures {

  /** A collection that is never created. */
  public static final String MISSING_COLLECTION = "contract-missing-collection";

  /** SolrJ's own wording around a failure, which must never reach the client. */
  private static final List<String> FORBIDDEN_TEXT =
      List.of("Error from server at", "http://", "https://");

  private static final String CHECK_HEALTH = "check-health";

  private static final String COLLECTION_ARG = "collection";
  private static final String COLLECTIONS_ARG = "collections";
  private static final String DOCUMENTS_ARG = "documents";
  private static final String NAME = "name";
  private static final String IS_ERROR = "isError";

  /** The tools that name no collection, so a missing collection cannot make them fail. */
  public static final Set<String> TOOLS_WITHOUT_A_TARGET =
      Set.of("list-aliases", "list-collections");

  /**
   * Arguments that make each tool fail on a running Solr because what they name does not exist: a
   * collection for most tools, the target collection of {@code create-alias}, the alias of {@code
   * delete-alias} and the configset of {@code create-collection}.
   */
  public static final Map<String, Map<String, Object>> MISSING_TARGET_ARGUMENTS =
      Map.ofEntries(
          Map.entry(
              "add-field-types",
              Map.of(
                  COLLECTION_ARG,
                  MISSING_COLLECTION,
                  "fieldTypes",
                  List.of(Map.of(NAME, "contract_failure_text", "className", "solr.TextField")))),
          Map.entry(
              "add-fields",
              Map.of(
                  COLLECTION_ARG,
                  MISSING_COLLECTION,
                  "fields",
                  List.of(Map.of(NAME, "contract_failure_year", "type", "pint")))),
          Map.entry(CHECK_HEALTH, Map.of(COLLECTION_ARG, MISSING_COLLECTION)),
          Map.entry(
              "create-alias",
              Map.of(NAME, "contract-failure-alias", COLLECTIONS_ARG, List.of(MISSING_COLLECTION))),
          Map.entry(
              "create-collection",
              Map.of(
                  NAME, "contract-failure-collection", "configSet", "contract-missing-configset")),
          Map.entry("delete-alias", Map.of(NAME, "contract-missing-alias")),
          Map.entry("get-collection-stats", Map.of(COLLECTION_ARG, MISSING_COLLECTION)),
          Map.entry("get-schema", Map.of(COLLECTION_ARG, MISSING_COLLECTION)),
          Map.entry(
              "index-csv-documents",
              Map.of(COLLECTION_ARG, MISSING_COLLECTION, "csv", "id,title\nfailure-csv-1,CSV")),
          Map.entry(
              "index-json-documents",
              Map.of(
                  COLLECTION_ARG,
                  MISSING_COLLECTION,
                  DOCUMENTS_ARG,
                  List.of(Map.of("id", "failure-json-1", "title", "JSON")))),
          Map.entry(
              "index-markdown-documents",
              Map.of(
                  COLLECTION_ARG,
                  MISSING_COLLECTION,
                  DOCUMENTS_ARG,
                  List.of("---\nid: failure-md-1\n---\n# Markdown\n\nBody text."))),
          Map.entry(
              "index-xml-documents",
              Map.of(
                  COLLECTION_ARG,
                  MISSING_COLLECTION,
                  "xml",
                  "<add><doc><field name=\"id\">failure-xml-1</field></doc></add>")),
          Map.entry("list-fields", Map.of(COLLECTION_ARG, MISSING_COLLECTION)),
          Map.entry("search", Map.of(COLLECTION_ARG, MISSING_COLLECTION, "query", "*:*")));

  private McpToolFailures() {}

  /**
   * Arguments for calling {@code tool} when Solr cannot be reached: the missing-target arguments,
   * or none for a tool that takes none.
   */
  public static Map<String, Object> unreachableArguments(String tool) {
    return MISSING_TARGET_ARGUMENTS.getOrDefault(tool, Map.of());
  }

  /** The names in a {@code tools/list} result. */
  public static Set<String> toolNames(List<Map<String, Object>> tools) {
    Set<String> names = new TreeSet<>();
    for (Map<String, Object> tool : tools) {
      names.add((String) tool.get(NAME));
    }
    return names;
  }

  /**
   * Asserts that {@code result} reports a failure the way the {@code mcp-contracts} spec requires.
   * For {@code check-health} that is an unhealthy result with {@code expectedHealthReason}; for
   * every other tool it is an error result whose text names no URL and quotes none of SolrJ's
   * wording.
   *
   * @param tool the tool that was called
   * @param result the {@code tools/call} result object
   * @param expectedHealthReason the {@code HealthStatus.Reason} name {@code check-health} must give
   */
  @SuppressWarnings("unchecked")
  public static void assertReportsFailure(
      String tool, Map<String, Object> result, String expectedHealthReason) {
    if (CHECK_HEALTH.equals(tool)) {
      assertFalse(Boolean.TRUE.equals(result.get(IS_ERROR)), () -> tool + ": " + result);
      Map<?, ?> health =
          assertInstanceOf(Map.class, result.get("structuredContent"), () -> tool + ": " + result);
      assertEquals(Boolean.FALSE, health.get("healthy"), () -> tool + ": " + result);
      assertEquals(expectedHealthReason, health.get("reason"), () -> tool + ": " + result);
      return;
    }
    assertEquals(
        Boolean.TRUE, result.get(IS_ERROR), () -> tool + " must fail as a tool error: " + result);
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    assertNotNull(content, () -> tool + " must say why it failed: " + result);
    for (Map<String, Object> block : content) {
      String text = String.valueOf(block.get("text"));
      assertFalse(text.isBlank(), () -> tool + " must say why it failed: " + result);
      for (String forbidden : FORBIDDEN_TEXT) {
        assertFalse(
            text.contains(forbidden),
            () -> tool + ": the error text must not contain " + forbidden + ": " + text);
      }
    }
  }
}
