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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Tests verifying that Spring Boot 4.1.1 declarative JWT authority mapping properties configure
 * {@link JwtAuthenticationConverter} to extract custom claims and map them to {@code SCOPE_}
 * authorities without requiring custom converter code.
 */
class JwtAuthoritiesMappingTest {

  private static Set<String> getAuthorityStrings(JwtAuthenticationConverter converter, Jwt jwt) {
    AbstractAuthenticationToken token = converter.convert(jwt);
    assertNotNull(token, "Converted authentication token must not be null");
    return token.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toSet());
  }

  @Nested
  @SpringBootTest(properties = "spring.docker.compose.enabled=false")
  @ActiveProfiles("http")
  @TestPropertySource(
      properties =
          "spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions=[realm_access][roles]")
  class KeycloakRealmRolesTest {

    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    void mapsRealmAccessRolesToScopeAuthorities() {
      Jwt jwt =
          Jwt.withTokenValue("mock-keycloak-token")
              .header("alg", "none")
              .subject("user-1")
              .claim("realm_access", Map.of("roles", List.of("solr:read")))
              .build();

      Set<String> authorities = getAuthorityStrings(jwtAuthenticationConverter, jwt);
      assertTrue(
          authorities.contains("SCOPE_solr:read"),
          "Expected authorities to contain 'SCOPE_solr:read', got: " + authorities);
    }
  }

  @Nested
  @SpringBootTest(properties = "spring.docker.compose.enabled=false")
  @ActiveProfiles("http")
  @TestPropertySource(
      properties = "spring.security.oauth2.resourceserver.jwt.authorities-claim-name=permissions")
  class Auth0PermissionsTest {

    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    void mapsPermissionsClaimToScopeAuthorities() {
      Jwt jwt =
          Jwt.withTokenValue("mock-auth0-token")
              .header("alg", "none")
              .subject("user-2")
              .claim("permissions", List.of("solr:write"))
              .build();

      Set<String> authorities = getAuthorityStrings(jwtAuthenticationConverter, jwt);
      assertTrue(
          authorities.contains("SCOPE_solr:write"),
          "Expected authorities to contain 'SCOPE_solr:write', got: " + authorities);
    }
  }

  @Nested
  @SpringBootTest(properties = "spring.docker.compose.enabled=false")
  @ActiveProfiles("http")
  @TestPropertySource(
      properties =
          "spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions[0]={'solr:read','solr:write','solr:admin'}")
  class MigrationBridgeTest {

    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    void grantsAllThreeScopesToUnscopedToken() {
      Jwt jwt =
          Jwt.withTokenValue("mock-unscoped-token").header("alg", "none").subject("user-3").build();

      Set<String> authorities = getAuthorityStrings(jwtAuthenticationConverter, jwt);
      assertTrue(
          authorities.contains("SCOPE_solr:read"),
          "Expected authorities to contain 'SCOPE_solr:read', got: " + authorities);
      assertTrue(
          authorities.contains("SCOPE_solr:write"),
          "Expected authorities to contain 'SCOPE_solr:write', got: " + authorities);
      assertTrue(
          authorities.contains("SCOPE_solr:admin"),
          "Expected authorities to contain 'SCOPE_solr:admin', got: " + authorities);
    }
  }
}
