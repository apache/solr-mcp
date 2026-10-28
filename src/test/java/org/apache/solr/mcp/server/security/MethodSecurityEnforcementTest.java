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
package org.apache.solr.mcp.server.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.apache.solr.mcp.server.indexing.IndexingService;
import org.apache.solr.mcp.server.schema.SchemaService;
import org.apache.solr.mcp.server.search.SearchService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Probe: is {@code @PreAuthorize} actually enforced, or merely present?
 *
 * <p>{@code McpToolRegistrationTest#everyMcpEndpointRequiresItsScope} asserts the annotation is
 * declared on every MCP entry point with its expected {@link McpAuthorities} constant. That is a
 * static check — it cannot tell whether {@link MethodSecurityConfiguration} is wired such that the
 * annotation has any runtime effect. If the profile gate or the {@code http.security.enabled}
 * property condition stopped matching, every annotation would silently become a no-op and the
 * static test would still pass.
 *
 * <p>This test runs in the {@code http} profile with security left at its default (enabled) and
 * invokes secured methods through the Spring proxy under various security contexts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MethodSecurityEnforcementTest {

  @Autowired private CollectionService collectionService;
  @Autowired private IndexingService indexingService;
  @Autowired private SchemaService schemaService;
  @Autowired private SearchService searchService;

  /**
   * With an entirely empty SecurityContext, Spring Security raises {@link
   * AuthenticationCredentialsNotFoundException} (an {@code AuthenticationException}) rather than
   * {@code AccessDeniedException} — the latter is for an authenticated principal lacking authority.
   * Asserting the broad {@code SecurityException}-free supertype would pass for the wrong reason,
   * so this pins the specific type.
   */
  @Test
  void unauthenticatedCallToSecuredToolIsRejected() {
    assertThrows(
        AuthenticationCredentialsNotFoundException.class,
        () -> collectionService.listCollections(),
        "list-collections carries @PreAuthorize(McpAuthorities.READ) and was called with no "
            + "authentication, so method security must reject it.");
  }

  /**
   * When an authenticated user has no granted scopes/authorities, calls to secured tools must be
   * denied with {@link AccessDeniedException}. This ensures un-scoped tokens are not granted
   * access.
   */
  @Test
  @WithMockUser(authorities = {})
  void authenticatedCallWithNoScopesIsDenied() {
    assertThrows(
        AccessDeniedException.class,
        () -> collectionService.listCollections(),
        "A token with no solr:* scopes must be denied access to list-collections.");
  }

  /**
   * A user with {@code solr:read} authority can invoke read tools (like {@code list-collections}),
   * but is denied mutation tools (like {@code create-collection} and {@code index-json-documents}).
   */
  @Test
  @WithMockUser(authorities = "SCOPE_solr:read")
  void readScopeAllowsReadToolsAndDeniesWriteAndAdminTools() {
    List<String> collections =
        assertDoesNotThrow(
            () -> collectionService.listCollections(),
            "list-collections is gated by McpAuthorities.READ and was called with SCOPE_solr:read, so it must succeed.");
    assertNotNull(collections);

    assertThrows(
        AccessDeniedException.class,
        () -> collectionService.createCollection("denied-col", null, null, null),
        "create-collection requires solr:admin scope and must reject a caller with only solr:read.");

    assertThrows(
        AccessDeniedException.class,
        () -> indexingService.indexJsonDocuments("denied-col", List.of()),
        "index-json-documents requires solr:write scope and must reject a caller with only solr:read.");
  }

  /**
   * A user with {@code solr:write} authority can invoke indexing tools (like {@code
   * index-json-documents}), but is denied schema modification tools (like {@code add-fields}) and
   * read tools (like {@code search}).
   */
  @Test
  @WithMockUser(authorities = "SCOPE_solr:write")
  void writeScopeAllowsWriteToolsAndDeniesAdminAndReadTools() {
    assertThrows(
        AccessDeniedException.class,
        () -> schemaService.addFields("test-col", List.of(Map.of("name", "f", "type", "string"))),
        "add-fields requires solr:admin scope and must reject a caller with only solr:write.");

    assertThrows(
        AccessDeniedException.class,
        () -> searchService.search("test-col", "*:*", null, null, null, null, null),
        "search requires solr:read scope and must reject a caller with only solr:write.");
  }

  /**
   * A user with {@code solr:admin} authority can invoke admin tools, but is denied read tools (like
   * {@code search}), verifying that scopes are non-hierarchical.
   */
  @Test
  @WithMockUser(authorities = "SCOPE_solr:admin")
  void adminScopeDoesNotImplyReadScope() {
    assertThrows(
        AccessDeniedException.class,
        () -> searchService.search("test-col", "*:*", null, null, null, null, null),
        "solr:admin does not imply solr:read, so search must reject a caller with only solr:admin.");
  }
}
