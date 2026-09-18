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
package org.apache.solr.mcp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.util.ReflectionUtils;

/**
 * Unit tests for the optional HTTP Basic Authentication wiring performed by
 * {@link SolrConfig}.
 *
 * <p>
 * Verifies that credentials are only attached to the SolrJ client when both
 * {@code username} and {@code password} are provided, and that the resulting
 * {@code Authorization} header string matches the expected Basic scheme
 * encoding.
 */
@JsonTest
// Reflects into SolrJ's private basicAuthAuthorizationStr field, which is not
// registered for reflection under GraalVM's closed-world model. The basic-auth
// wiring logic itself is fully covered by the JVM test run.
@DisabledInNativeImage
class SolrConfigAuthTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void noCredentialsWhenBothMissing() throws Exception {
		assertNull(authorizationHeaderFor(null, null));
	}

	@ParameterizedTest
	@CsvSource({"alice,", ",secret", "'',secret"})
	void noCredentialsWhenEitherValueIsMissingOrBlank(String username, String password) throws Exception {
		assertNull(authorizationHeaderFor(username, password));
	}

	@Test
	void credentialsAppliedWhenBothProvided() throws Exception {
		String header = authorizationHeaderFor("alice", "s3cret");
		String expected = "Basic "
				+ Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
		assertEquals(expected, header);
	}

	private @org.jspecify.annotations.Nullable String authorizationHeaderFor(
			@org.jspecify.annotations.Nullable String username, @org.jspecify.annotations.Nullable String password)
			throws Exception {
		SolrConfigurationProperties properties = new SolrConfigurationProperties("http://localhost:8983/solr/",
				username, password);
		SolrConfig config = new SolrConfig();
		try (SolrClient client = config.solrClient(properties, new JsonResponseParser(objectMapper))) {
			HttpJdkSolrClient httpClient = assertInstanceOf(HttpJdkSolrClient.class, client);
			Field field = ReflectionUtils.findField(httpClient.getClass(), "basicAuthAuthorizationStr");
			assertNotNull(field, "SolrJ field 'basicAuthAuthorizationStr' not found");
			ReflectionUtils.makeAccessible(field);
			return (String) ReflectionUtils.getField(field, httpClient);
		}
	}
}
