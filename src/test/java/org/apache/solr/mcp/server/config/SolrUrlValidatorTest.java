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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the {@link SolrUrl} constraint both in isolation and through Spring
 * Boot's configuration-property binding, which is the path that actually
 * decides whether a misconfigured deployment fails at startup.
 */
class SolrUrlValidatorTest {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void openValidatorFactory() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@ParameterizedTest
	@ValueSource(
			strings = {"http://localhost:8983", "http://localhost:8983/", "http://localhost:8983/solr",
					"http://localhost:8983/solr/", "https://solr.internal:8983/custom/solr/",
					"https://solr.example.com"})
	void acceptsAbsoluteHttpUrlsWithAHost(String url) {
		assertThat(violations(url)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(
			strings = {"localhost:8983", "solr.example.com", "/solr", "ftp://solr.example.com/solr", "file:///var/solr",
					"not a url", "http://"})
	void rejectsUrlsSolrJCannotConnectTo(String url) {
		assertThat(violations(url)).extracting(ConstraintViolation::getMessage).containsExactly(
				"must be an absolute http or https URL including a host, " + "for example http://localhost:8983/solr/");
	}

	/**
	 * A blank URL must report only {@code @NotBlank}'s message. {@link SolrUrl}
	 * deliberately passes blank values through so a single mistake does not produce
	 * two overlapping messages.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"", "   "})
	void reportsBlankUrlOnlyThroughNotBlank(String url) {
		assertThat(violations(url)).extracting(ConstraintViolation::getMessage).containsExactly("must not be blank");
	}

	/**
	 * Disabled in native image because {@link ApplicationContextRunner} builds its
	 * {@code AssertableApplicationContext} with {@link java.lang.reflect.Proxy},
	 * and GraalVM cannot materialize a JDK dynamic proxy that was not registered at
	 * build time. This is a limitation of the test harness, not of the constraint —
	 * the JVM build covers this path, and the constraint itself is exercised
	 * natively by the parameterized tests above.
	 */
	@Test
	@DisabledInNativeImage
	void applicationContextFailsToStartWhenSolrUrlOmitsTheScheme() {
		contextRunner().withPropertyValues("solr.url=localhost:8983").run(context -> assertThat(context).hasFailed());
	}

	/** @see #applicationContextFailsToStartWhenSolrUrlOmitsTheScheme() */
	@Test
	@DisabledInNativeImage
	void applicationContextStartsWhenSolrUrlIsAbsolute() {
		contextRunner().withPropertyValues("solr.url=http://localhost:8983/solr/")
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static Set<ConstraintViolation<SolrConfigurationProperties>> violations(String url) {
		return validator.validate(new SolrConfigurationProperties(url, null, null));
	}

	private static ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner().withUserConfiguration(SolrPropertiesConfiguration.class);
	}

	@EnableConfigurationProperties(SolrConfigurationProperties.class)
	static class SolrPropertiesConfiguration {
	}
}
