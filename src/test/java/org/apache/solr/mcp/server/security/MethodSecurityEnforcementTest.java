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

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Probe: is {@code @PreAuthorize} actually enforced, or merely present?
 *
 * <p>
 * {@code McpToolRegistrationTest#everyMcpEndpointIsPreAuthorized} asserts the
 * annotation is declared on every MCP entry point. That is a static check — it
 * cannot tell whether {@link MethodSecurityConfiguration} is wired such that
 * the annotation has any runtime effect. If the profile gate or the
 * {@code http.security.enabled} property condition stopped matching, every
 * annotation would silently become a no-op and the static test would still
 * pass.
 *
 * <p>
 * This test runs in the {@code http} profile with security left at its default
 * (enabled) and invokes a secured method through the Spring proxy with an empty
 * SecurityContext. Enforcement means an {@link AccessDeniedException}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisabledInNativeImage
class MethodSecurityEnforcementTest {

	@Autowired
	private CollectionService collectionService;

	/**
	 * With an entirely empty SecurityContext, Spring Security raises
	 * {@link AuthenticationCredentialsNotFoundException} (an
	 * {@code AuthenticationException}) rather than {@code AccessDeniedException} —
	 * the latter is for an authenticated principal lacking authority. Asserting the
	 * broad {@code SecurityException}-free supertype would pass for the wrong
	 * reason, so this pins the specific type.
	 */
	@Test
	void unauthenticatedCallToSecuredToolIsRejected() {
		assertThrows(AuthenticationCredentialsNotFoundException.class, () -> collectionService.listCollections(),
				"list-collections carries @PreAuthorize(\"isAuthenticated()\") and was called with no "
						+ "authentication, so method security must reject it. Succeeding means the annotation "
						+ "is decorative: @EnableMethodSecurity is not in effect for this context.");
	}
}
