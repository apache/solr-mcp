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
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.collection.CollectionService;
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
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
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

	/**
	 * The necessary counterpart to
	 * {@link #unauthenticatedCallToSecuredToolIsRejected()}.
	 *
	 * <p>
	 * A rejection test on its own cannot distinguish "correctly denies anonymous
	 * callers" from "denies every caller". Both produce the same green result, so a
	 * gate that were wedged permanently shut would look identical to a working one.
	 * That gap is not hypothetical: a secured tool call was for a time believed
	 * broken — reported as denying even valid tokens — and no test existed that
	 * could have contradicted it. The claim turned out to be false, but only a live
	 * server proved so.
	 *
	 * <p>
	 * {@code @WithMockUser} installs an authenticated principal before the method
	 * runs and clears it afterwards, so the {@code ThreadLocal} context cannot leak
	 * into {@link #unauthenticatedCallToSecuredToolIsRejected()} and make that test
	 * order-dependent.
	 */
	@Test
	@WithMockUser
	void authenticatedCallToSecuredToolSucceeds() {
		List<String> collections = assertDoesNotThrow(() -> collectionService.listCollections(),
				"list-collections is gated by @PreAuthorize(\"isAuthenticated()\") and was called with an "
						+ "authenticated principal, so method security must permit it. An AccessDeniedException "
						+ "here means the gate rejects everyone, not just anonymous callers — the annotation "
						+ "would be denying valid callers rather than enforcing a boundary.");

		assertNotNull(collections, "an authorized list-collections call must return a result, not null");
	}
}
