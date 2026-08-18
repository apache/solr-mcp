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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Verifies that {@link SolrNativeHints.Registrar} registers the reflection and
 * resource hints the native image depends on.
 *
 * <p>
 * The hints are otherwise only exercised by {@code nativeTest -Pnative} (a full
 * GraalVM build), so an accidentally removed registration would surface as a
 * hard-to-diagnose native-only startup failure. This test pins the
 * registrations on the plain JVM path where a regression fails in seconds.
 */
class SolrNativeHintsTest {

	private final RuntimeHints hints = new RuntimeHints();

	@BeforeEach
	void registerHints() {
		new SolrNativeHints.Registrar().registerHints(hints, getClass().getClassLoader());
	}

	@Test
	void registersDefaultMetaProviderConstructorHint() {
		// Spring AI MCP instantiates DefaultMetaProvider reflectively in
		// MetaUtils.getMeta(); without this hint every Spring context refresh
		// fails in native image with "Required no-arg constructor not found".
		assertTrue(RuntimeHintsPredicates.reflection()
				.onType(TypeReference.of("org.springaicommunity.mcp.context.DefaultMetaProvider"))
				.withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS).test(hints));
	}

	@Test
	void registersSolrjResponseTypeHints() {
		assertTrue(RuntimeHintsPredicates.reflection().onType(QueryResponse.class)
				.withMemberCategories(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
						MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS)
				.test(hints));
	}

	@Test
	void registersMcpResponseRecordHints() {
		assertTrue(RuntimeHintsPredicates.reflection()
				.onType(TypeReference.of("org.apache.solr.mcp.server.search.SearchResponse"))
				.withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS).test(hints));
	}

	@Test
	void registersLogbackXmlResourceHint() {
		// Required so logback's pre-Spring initialization finds logback.xml and
		// stays silent on stdout (MCP STDIO framing).
		assertTrue(RuntimeHintsPredicates.resource().forResource("logback.xml").test(hints));
	}
}
