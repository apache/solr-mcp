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
 * Pins the CORS contract the MCP Inspector depends on.
 *
 * <p>
 * The Inspector's UI runs at {@code http://localhost:6274} and is the default
 * value of {@code mcp.cors.allowed-origins}. That default is a plain property:
 * narrowing it, reordering it, or setting {@code MCP_CORS_ALLOWED_ORIGINS=*}
 * silently stops the Inspector connecting, and no other test notices.
 *
 * <p>
 * The wildcard case is the trap. {@code setAllowedOrigins} is the strict API,
 * so {@code *} combined with {@code allowCredentials(true)} does not open the
 * server up — it rejects <em>every</em> origin, including the Inspector's, with
 * no warning logged. An operator reaching for {@code *} to "allow everything"
 * gets the opposite.
 *
 * <p>
 * This replays the exact preflight a browser sends on the Inspector's behalf
 * and asserts the response permits the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisabledInNativeImage
class McpInspectorCorsTest {

	/** The MCP Inspector UI origin, and the shipped default allowlist entry. */
	private static final String INSPECTOR_ORIGIN = "http://localhost:6274";

	@LocalServerPort
	private int port;

	private HttpResponse<String> preflight(String origin, String method, String requestHeaders) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", origin)
				.header("Access-Control-Request-Method", method);
		if (requestHeaders != null) {
			builder.header("Access-Control-Request-Headers", requestHeaders);
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void inspectorPreflightIsAllowed() throws Exception {
		HttpResponse<String> response = preflight(INSPECTOR_ORIGIN, "POST", "content-type,authorization");

		assertEquals(200, response.statusCode(),
				"The MCP Inspector cannot connect unless its origin passes preflight. Check that "
						+ "mcp.cors.allowed-origins still contains " + INSPECTOR_ORIGIN);
		assertEquals(INSPECTOR_ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
				"The specific origin must be echoed back; a wildcard is invalid alongside credentials");
		assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null),
				"The Inspector sends the bearer token as a credentialed request");
	}

	@Test
	void inspectorTransportMethodsAreAllowed() throws Exception {
		String allowed = preflight(INSPECTOR_ORIGIN, "POST", null).headers().firstValue("Access-Control-Allow-Methods")
				.orElse("");

		// Streamable HTTP: POST sends messages, GET opens the stream, DELETE ends
		// the session. Dropping any one breaks a different part of the transport.
		for (String method : new String[]{"GET", "POST", "DELETE"}) {
			assertTrue(allowed.contains(method),
					() -> "MCP Streamable HTTP needs " + method + "; Allow-Methods was: " + allowed);
		}
	}

	@Test
	void unknownOriginIsRejected() throws Exception {
		assertEquals(403, preflight("http://not-the-inspector.example", "POST", null).statusCode(),
				"Origins outside the allowlist must be refused, otherwise the allowlist is decorative");
	}
}
