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
package org.apache.solr.mcp.server.modern;

import static org.apache.solr.mcp.server.modern.ModernMcpRequests.PROTOCOL_VERSION;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.parse;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.request;
import static org.apache.solr.mcp.server.modern.ModernMcpRequests.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code @PreAuthorize} on tools and resources must keep working when the server runs on the
 * 2026-07-28 revision.
 *
 * <p>Method security reads the {@code SecurityContext} of the thread that invokes the tool. The
 * modern transport has its own request manager, so if it ever ran handlers on another thread an
 * authenticated caller would be rejected (and an anonymous one could never be told apart). This
 * test drives real HTTP requests through the secured filter chain: a stand-in for the bearer-token
 * filter authenticates requests that carry {@value #TEST_USER_HEADER}, and the same tool call must
 * fail without it and succeed with it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.docker.compose.enabled=false", "MCP_PROTOCOL=modern"})
@ActiveProfiles("http")
@Import({TestcontainersConfiguration.class, McpModernProtocolMethodSecurityTest.TestAuth.class})
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class McpModernProtocolMethodSecurityTest {

  static final String TEST_USER_HEADER = "X-Test-User";

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  private HttpResponse<String> listCollections(boolean authenticated) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Method", "tools/call")
            .header("Mcp-Name", "list-collections")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    request(
                        "tools/call", Map.of("name", "list-collections", "arguments", Map.of()))));
    if (authenticated) {
      builder.header(TEST_USER_HEADER, "alice");
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void anonymousCallToSecuredToolIsRejected() throws Exception {
    HttpResponse<String> response = listCollections(false);

    Map<String, Object> body = parse(response.body());
    boolean rejected =
        body.containsKey("error")
            || (body.get("result") instanceof Map<?, ?> r && Boolean.TRUE.equals(r.get("isError")));
    assertTrue(
        rejected,
        "list-collections carries @PreAuthorize(\"isAuthenticated()\"); an anonymous modern-protocol "
            + "call must not run it: "
            + response.body());
  }

  @Test
  void authenticatedCallToSecuredToolSucceeds() throws Exception {
    HttpResponse<String> response = listCollections(true);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        Boolean.FALSE,
        result(response.body()).get("isError"),
        "an authenticated caller must reach the tool; a rejection here means the modern transport "
            + "ran the tool without the request's SecurityContext: "
            + response.body());
  }

  /**
   * Authenticates any request carrying {@value #TEST_USER_HEADER}. Registered after the Spring
   * Security chain, exactly where a bearer-token filter's result would be visible to the
   * dispatcher, so the tool sees the context only if the transport invokes it on the request
   * thread.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class TestAuth {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> testUserFilter() {
      OncePerRequestFilter filter =
          new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
              String user = request.getHeader(TEST_USER_HEADER);
              if (user != null) {
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                            user, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
              }
              chain.doFilter(request, response);
            }
          };
      return new FilterRegistrationBean<>(filter);
    }
  }
}
