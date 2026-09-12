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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code solr.url} is validated where the binder hands it over, in the record's
 * compact constructor, so a misconfigured deployment dies at startup with an
 * actionable message instead of at first request with an opaque SolrJ error.
 *
 * <p>
 * {@code URI.create("localhost:8983")} happily parses as scheme
 * {@code localhost} with no host, and {@link SolrConfig} would normalize it by
 * string concatenation without noticing; that is the case that motivates the
 * check.
 */
class SolrConfigurationPropertiesTest {

	@ParameterizedTest
	@ValueSource(
			strings = {"http://localhost:8983", "http://localhost:8983/", "http://localhost:8983/solr",
					"http://localhost:8983/solr/", "https://solr.internal:8983/custom/solr/",
					"https://solr.example.com", "http://solr:8983/solr/"})
	void acceptsAbsoluteHttpUrlsWithAHost(String url) {
		assertThat(new SolrConfigurationProperties(url, null, null).url()).isEqualTo(url);
	}

	@ParameterizedTest
	@ValueSource(
			strings = {"localhost:8983", "solr.example.com", "/solr", "ftp://solr.example.com/solr", "file:///var/solr",
					"not a url", "http://", "", "   "})
	void rejectsUrlsSolrJCannotConnectTo(String url) {
		assertThatThrownBy(() -> new SolrConfigurationProperties(url, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("solr.url must be an absolute http or https URL including a host")
				.hasMessageContaining("http://localhost:8983/solr/");
	}

	/** The binder passes null when the property is absent altogether. */
	@Test
	void rejectsAMissingUrl() {
		assertThatThrownBy(() -> new SolrConfigurationProperties(null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("solr.url must be an absolute http or https URL including a host");
	}

	@Test
	@DisabledInNativeImage
	void bindingFailsAtStartupWhenTheSchemeIsMissing() {
		contextRunner().withPropertyValues("solr.url=localhost:8983").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context).getFailure().rootCause().hasMessageContaining("solr.url must be an absolute");
		});
	}

	@Test
	@DisabledInNativeImage
	void bindingSucceedsForAnAbsoluteUrl() {
		contextRunner().withPropertyValues("solr.url=http://localhost:8983/solr/")
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);
	}

	@EnableConfigurationProperties(SolrConfigurationProperties.class)
	static class PropertiesOnly {
	}
}
