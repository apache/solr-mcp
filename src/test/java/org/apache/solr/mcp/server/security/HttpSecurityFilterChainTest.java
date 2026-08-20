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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins the anonymous-access boundary of the {@code http} filter chain.
 *
 * <p>
 * {@link HttpSecurityConfiguration} deliberately splits the actuator: probes
 * stay open so load balancers and orchestrators can reach them, while every
 * other endpoint requires authentication — otherwise an unauthenticated caller
 * could read the dependency tree from {@code /actuator/sbom/application} or
 * scrape metrics that map the tool surface.
 *
 * <p>
 * That decision is a one-line {@code requestMatchers} rule. Widening it to
 * {@code permitAll()} would expose all of the above and break no other test, so
 * this asserts both halves: health open, everything else closed.
 *
 * <p>
 * No issuer is configured here, which is the point — with OAuth2 unwired the
 * chain must still deny anonymous access rather than fall open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisabledInNativeImage
class HttpSecurityFilterChainTest {

	@LocalServerPort
	private int port;

	private int statusOf(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
	}

	@Test
	void healthProbeIsAnonymouslyReachable() throws Exception {
		assertEquals(200, statusOf("/actuator/health"),
				"/actuator/health must stay open for liveness and readiness probes");
	}

	/**
	 * Denial here is 403, not 401: with no issuer configured there is no
	 * authentication entry point to challenge with, so Spring Security rejects
	 * rather than prompting. Wiring an issuer turns the same request into a 401
	 * carrying {@code WWW-Authenticate: Bearer}. Both are correct denials, so these
	 * accept either — what must never happen is a 200.
	 */
	private void assertDenied(String path, String why) throws Exception {
		int status = statusOf(path);
		assertTrue(status == 401 || status == 403, why + " — expected 401 or 403, got " + status);
	}

	@Test
	void sbomEndpointRequiresAuthentication() throws Exception {
		assertDenied("/actuator/sbom/application",
				"/actuator/sbom/application exposes the full dependency tree and must not be anonymous");
	}

	@Test
	void metricsEndpointRequiresAuthentication() throws Exception {
		assertDenied("/actuator/metrics", "/actuator/metrics maps the tool surface and must not be anonymous");
	}
}
