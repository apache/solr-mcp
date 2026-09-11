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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Guards the two-phase logging setup, which has a trap at each end.
 *
 * <p>
 * <b>Phase 1 - logback's own initialization.</b> {@code ContextInitializer}
 * runs on the first {@code LoggerFactory} touch, before Spring Boot exists, and
 * only ever looks at the standard locations ({@code logback-test.xml},
 * {@code logback.xml}). It has never heard of {@code logback-spring.xml}. With
 * no standard-location file it falls back to {@code BasicConfigurator} and, in
 * a native image, prints its whole {@code |-INFO} status list to stdout - which
 * corrupts the MCP STDIO JSON-RPC stream. Hence {@code logback.xml}, carrying a
 * {@code NopStatusListener} and no appenders.
 *
 * <p>
 * <b>Phase 2 - Spring Boot.</b> {@code AbstractLoggingSystem.initialize()}
 * resolves {@code logging.config} first; only when it is empty does it fall
 * through to {@code initializeWithConventions()}, which finds the
 * standard-location {@code logback.xml}, reinitializes from it and
 * <em>returns</em> - never loading the {@code -spring} variant, and silently
 * dropping every {@code <springProfile>} appender with it. Boot's reference
 * documentation states the rule directly: {@code <springProfile>} <em>"cannot
 * be used in the standard logback.xml file because it is loaded too
 * early"</em>.
 *
 * <p>
 * So the two files are only safe together while
 * {@code logging.config=classpath:logback-spring.xml} is set. Remove the
 * property and HTTP mode loses its CONSOLE and OTEL appenders; remove
 * {@code logback.xml} and the native STDIO image stops speaking MCP. Each test
 * below pins one half of that.
 *
 * @see <a href=
 *      "https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.custom-log-configuration">Spring
 *      Boot - Custom Log Configuration</a>
 */
class LoggingConfigurationTest {

	/**
	 * Locations {@code ContextInitializer} scans, and that Spring Boot's
	 * convention-based resolution would short-circuit on.
	 */
	private static final String[] STANDARD_LOGBACK_LOCATIONS = {"logback-test.xml", "logback-test.groovy",
			"logback.groovy", "logback.xml"};

	private static final String SPRING_VARIANT = "logback-spring.xml";

	/** The configuration carrying the per-profile appenders must ship. */
	@Test
	void springVariantIsPresentOnTheClasspath() {
		assertThat(getClass().getClassLoader().getResource(SPRING_VARIANT))
				.as("%s carries the per-profile appenders and must ship on the classpath", SPRING_VARIANT).isNotNull();
	}

	/**
	 * Phase 2: a standard-location file is only allowed while
	 * {@code logging.config} points past it.
	 */
	@Test
	void standardLocationFileIsPairedWithAnExplicitLoggingConfig() {
		String shadowing = firstStandardLocationOnClasspath();
		if (shadowing == null) {
			return;
		}

		String loggingConfig = applicationProperties().getProperty("logging.config");

		assertThat(loggingConfig).as(
				"%s is on the classpath. Spring Boot only skips it when logging.config is set; otherwise "
						+ "AbstractLoggingSystem.initializeWithConventions() reinitializes from it and returns, and "
						+ "%s is never loaded - every <springProfile> appender is silently dropped",
				shadowing, SPRING_VARIANT).isNotNull().contains(SPRING_VARIANT);
	}

	/**
	 * Phase 1: whatever logback loads before Spring Boot must not be able to write
	 * to stdout, which STDIO reserves for JSON-RPC.
	 */
	@Test
	void standardLocationFileDeclaresNoAppenders() {
		String earlyConfig = firstStandardLocationOnClasspath();
		if (earlyConfig == null) {
			return;
		}

		assertThat(stripXmlComments(read(earlyConfig)))
				.as("%s is applied by logback before Spring Boot and before any profile is known, so an appender here "
						+ "reaches stdout in STDIO mode and corrupts the MCP JSON-RPC framing. Appenders belong in "
						+ "%s, inside a <springProfile> block", earlyConfig, SPRING_VARIANT)
				.doesNotContain("<appender");
	}

	/**
	 * The STDIO profile must not export an empty console pattern. Spring Boot
	 * copies {@code logging.pattern.console} into the JVM-wide
	 * {@code CONSOLE_LOG_PATTERN} system property (first writer wins), and logback
	 * rejects an empty pattern with {@code Empty or null pattern} instead of
	 * silencing output. Spring Framework 7 pauses a test's ApplicationContext on
	 * context switch, which stops Boot's logging lifecycle bean and makes the next
	 * context re-initialise logback under its own profile - so a stdio-profile test
	 * running first would break every later http-profile context in the same JVM.
	 * STDIO stays silent because {@code logback-spring.xml} declares no appenders
	 * for it, not because of the pattern.
	 */
	@Test
	void stdioProfileDoesNotExportAnEmptyConsolePattern() {
		String pattern = properties("application-stdio.properties").getProperty("logging.pattern.console");
		if (pattern != null) {
			assertThat(pattern).as("application-stdio.properties sets logging.pattern.console to an empty value. Boot "
					+ "exports it JVM-wide as CONSOLE_LOG_PATTERN (first writer wins) and logback rejects an empty "
					+ "pattern, breaking any http-profile context started later in the same JVM").isNotBlank();
		}
	}

	private String firstStandardLocationOnClasspath() {
		for (String location : STANDARD_LOGBACK_LOCATIONS) {
			if (getClass().getClassLoader().getResource(location) != null) {
				return location;
			}
		}
		return null;
	}

	private Properties applicationProperties() {
		return properties("application.properties");
	}

	private Properties properties(String resource) {
		Properties properties = new Properties();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertThat(in).as("%s must be on the classpath", resource).isNotNull();
			properties.load(in);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return properties;
	}

	private String read(String resource) {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertThat(in).as("%s must be readable from the classpath", resource).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String stripXmlComments(String xml) {
		return xml.replaceAll("(?s)<!--.*?-->", "");
	}
}
