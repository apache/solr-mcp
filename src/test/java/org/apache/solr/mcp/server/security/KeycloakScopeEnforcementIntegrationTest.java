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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves scoped authorization end to end: a real Keycloak token, validated by the real {@code
 * http}-profile filter chain ({@code McpServerOAuth2Configurer}), mapped to {@code SCOPE_*}
 * authorities, and enforced by {@code @PreAuthorize} on the MCP tools.
 *
 * <p>The neighbouring tests each cover one half. {@link JwtAuthoritiesMappingTest} calls the
 * converter bean directly and {@link MethodSecurityEnforcementTest} uses {@code @WithMockUser}, so
 * neither would notice if the bearer-token filter stopped using the converter, or if the shipped
 * realm stopped issuing the scopes the tools demand.
 *
 * <p>The realm under test is the shipped {@code keycloak/solr-mcp-realm.json}, with only the
 * audience mapper's resource URI rewritten to this test's server port — the server validates {@code
 * aud} against its own {@code /mcp} URL, and a random port cannot match the realm's {@code 8080}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class KeycloakScopeEnforcementIntegrationTest {

  /** Same image as compose.yaml, so the shipped realm is exercised on the version it ships with. */
  private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0";

  private static final int KEYCLOAK_PORT = 8080;

  /**
   * createTempFile is owner-only; Keycloak runs as a different user and must be able to read it.
   */
  private static final int REALM_FILE_MODE = 0644;

  private static final String REALM_RESOURCE = "keycloak/solr-mcp-realm.json";

  /** The resource URI baked into the shipped realm's audience mapper. */
  private static final String SHIPPED_AUDIENCE = "http://localhost:8080/mcp";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static final int SERVER_PORT = freePort();

  @Container
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(KEYCLOAK_IMAGE)
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          .withCopyFileToContainer(
              MountableFile.forHostPath(realmWithAudienceFor(SERVER_PORT), REALM_FILE_MODE),
              "/opt/keycloak/data/import/solr-mcp-realm.json")
          .withCommand("start-dev", "--import-realm")
          .withExposedPorts(KEYCLOAK_PORT)
          .waitingFor(
              Wait.forHttp("/realms/solr-mcp/.well-known/openid-configuration")
                  .forPort(KEYCLOAK_PORT)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  @DynamicPropertySource
  static void securedServer(DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> SERVER_PORT);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUri());
  }

  private static String issuerUri() {
    return "http://"
        + KEYCLOAK.getHost()
        + ":"
        + KEYCLOAK.getMappedPort(KEYCLOAK_PORT)
        + "/realms/solr-mcp";
  }

  /** A token carrying only the default scope must reach read tools and be refused admin tools. */
  @Test
  void readOnlyTokenReachesReadToolsButNotAdminTools() throws Exception {
    String token = accessToken("");

    assertTrue(
        scopesOf(token).contains("solr:read"), "realm must grant solr:read by default: " + token);
    assertFalse(
        scopesOf(token).contains("solr:admin"),
        "solr:admin must be opt-in, not a default client scope");

    ToolResult list = callTool(token, "list-collections", "{}");
    assertEquals(200, list.status(), "a solr:read token must pass the filter chain");
    assertFalse(list.denied(), "list-collections needs solr:read: " + list.body());

    ToolResult create = callTool(token, "create-collection", "{\"name\":\"scope_denied\"}");
    assertTrue(create.denied(), "create-collection needs solr:admin: " + create.body());
  }

  /** Requesting the admin scope must unlock admin tools without implying read. */
  @Test
  void adminScopeUnlocksAdminToolsAndDoesNotImplyRead() throws Exception {
    String token = accessToken("solr:admin");
    assertFalse(scopesOf(token).contains("solr:write"), "only the requested scopes are granted");

    ToolResult create = callTool(token, "create-collection", "{\"name\":\"scope_allowed\"}");
    assertFalse(create.denied(), "solr:admin must pass the gate: " + create.body());
  }

  @Test
  void requestWithoutTokenIsRejectedByTheFilterChain() throws Exception {
    ToolResult anonymous = callTool(null, "list-collections", "{}");
    assertTrue(
        anonymous.status() == 401 || anonymous.denied(),
        "anonymous tools/call must not reach list-collections: " + anonymous.body());
  }

  // --- helpers -------------------------------------------------------------

  private record ToolResult(int status, String body) {
    /** Denied at the filter chain (401/403) or by method security inside the tool call. */
    boolean denied() {
      return status == 401
          || status == 403
          || body.toLowerCase().contains("access denied")
          || body.toLowerCase().contains("access is denied");
    }
  }

  private static String accessToken(String extraScopes) throws Exception {
    String form =
        "grant_type=client_credentials&client_id=solr-mcp-service&client_secret=dev-only-not-a-secret"
            + (extraScopes.isEmpty()
                ? ""
                : "&scope=" + URLEncoder.encode(extraScopes, StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(issuerUri() + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  private static String scopesOf(String jwt) {
    byte[] payload = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
    JsonNode claims = JSON.readTree(payload);
    return claims.get("scope").asString();
  }

  private ToolResult callTool(String token, String tool, String argumentsJson) throws Exception {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool
            + "\",\"arguments\":"
            + argumentsJson
            + "}}";
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + SERVER_PORT + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response =
        HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new ToolResult(response.statusCode(), response.body());
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path realmWithAudienceFor(int serverPort) {
    try {
      Path realm = Files.createTempFile("solr-mcp-realm", ".json");
      String json =
          Files.readString(Path.of(REALM_RESOURCE), StandardCharsets.UTF_8)
              .replace(SHIPPED_AUDIENCE, "http://localhost:" + serverPort + "/mcp");
      Files.writeString(realm, json, StandardCharsets.UTF_8);
      realm.toFile().deleteOnExit();
      return realm;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
