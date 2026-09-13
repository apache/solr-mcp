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
import java.util.List;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("http")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class McpInspectorCorsTest {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	/** The MCP Inspector UI origin, and the shipped default allowlist entry. */
	private static final String INSPECTOR_ORIGIN = "http://localhost:6274";

	/** Single path the MCP Streamable HTTP transport routes through. */
	private static final String MCP_ENDPOINT = "/mcp";

	/**
	 * Methods the Streamable HTTP transport needs: POST sends messages, GET opens
	 * the stream, DELETE ends the session. Dropping any one breaks a different part
	 * of the transport.
	 */
	private static final List<HttpMethod> TRANSPORT_METHODS = List.of(HttpMethod.GET, HttpMethod.POST,
			HttpMethod.DELETE);

	@LocalServerPort
	private int port;

	private HttpResponse<String> preflight(String origin, HttpMethod method, String requestHeaders) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MCP_ENDPOINT))
				.method(HttpMethod.OPTIONS.name(), HttpRequest.BodyPublishers.noBody())
				.header(HttpHeaders.ORIGIN, origin).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method.name());
		if (requestHeaders != null) {
			builder.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
		}
		return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void inspectorPreflightIsAllowed() throws Exception {
		HttpResponse<String> response = preflight(INSPECTOR_ORIGIN, HttpMethod.POST,
				HttpHeaders.CONTENT_TYPE + "," + HttpHeaders.AUTHORIZATION);

		assertEquals(HttpStatus.OK.value(), response.statusCode(),
				"The MCP Inspector cannot connect unless its origin passes preflight. Check that "
						+ "mcp.cors.allowed-origins still contains " + INSPECTOR_ORIGIN);
		assertEquals(INSPECTOR_ORIGIN,
				response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN).orElse(null),
				"The specific origin must be echoed back; a wildcard is invalid alongside credentials");
		assertEquals(Boolean.TRUE.toString(),
				response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS).orElse(null),
				"The Inspector sends the bearer token as a credentialed request");
	}

	@Test
	void inspectorTransportMethodsAreAllowed() throws Exception {
		String allowed = preflight(INSPECTOR_ORIGIN, HttpMethod.POST, null).headers()
				.firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS).orElse("");

		for (HttpMethod method : TRANSPORT_METHODS) {
			assertTrue(allowed.contains(method.name()),
					() -> "MCP Streamable HTTP needs " + method + "; Allow-Methods was: " + allowed);
		}
	}

	@Test
	void unknownOriginIsRejected() throws Exception {
		assertEquals(HttpStatus.FORBIDDEN.value(),
				preflight("http://not-the-inspector.example", HttpMethod.POST, null).statusCode(),
				"Origins outside the allowlist must be refused, otherwise the allowlist is decorative");
	}
}
