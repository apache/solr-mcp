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
package org.apache.solr.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.StatusUtil;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the logging setup to Spring Boot's conventions.
 *
 * <p>
 * Exactly one logback configuration ships, {@code logback-spring.xml}, and Boot
 * finds it by convention. A standard-location file ({@code logback.xml}) would
 * be applied by logback itself before Boot starts and would then make
 * {@code AbstractLoggingSystem.initializeWithConventions()} stop there, never
 * loading the {@code -spring} variant and silently dropping every
 * {@code <springProfile>} appender. Boot's reference documentation:
 * {@code <springProfile>} <em>"cannot be used in the standard logback.xml file
 * because it is loaded too early"</em>.
 *
 * <p>
 * The one thing that must happen before Boot exists is silencing logback's own
 * status output. In a native image logback cannot read its version from the
 * manifest, raises a {@code |-WARN}, and {@code LogbackServiceProvider} then
 * prints the whole status list to stdout - which STDIO reserves for JSON-RPC.
 * {@code LogbackServiceProvider} skips that print whenever a status listener is
 * installed, and {@code ContextInitializer.autoConfig()} installs one from the
 * {@code logback.statusListenerClass} system property. {@link Main} sets that
 * property first thing, before anything can touch {@code LoggerFactory}.
 *
 * @see <a href=
 *      "https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.custom-log-configuration">Spring
 *      Boot - Custom Log Configuration</a>
 * @see <a href=
 *      "https://logback.qos.ch/manual/configuration.html#dumpingStatusData">Logback
 *      - status data</a>
 */
class LoggingConfigurationTest {

	/** Locations logback's {@code ContextInitializer} scans on its own. */
	private static final String[] STANDARD_LOGBACK_LOCATIONS = {"logback-test.xml", "logback-test.groovy",
			"logback.groovy", "logback.xml"};

	private static final String SPRING_VARIANT = "logback-spring.xml";

	private String previousStatusListenerClass;

	@BeforeEach
	void rememberStatusListenerProperty() {
		previousStatusListenerClass = System.getProperty(Main.LOGBACK_STATUS_LISTENER_PROPERTY);
		System.clearProperty(Main.LOGBACK_STATUS_LISTENER_PROPERTY);
	}

	@AfterEach
	void restoreStatusListenerProperty() {
		if (previousStatusListenerClass == null) {
			System.clearProperty(Main.LOGBACK_STATUS_LISTENER_PROPERTY);
		} else {
			System.setProperty(Main.LOGBACK_STATUS_LISTENER_PROPERTY, previousStatusListenerClass);
		}
	}

	/** The configuration carrying the per-profile appenders must ship. */
	@Test
	void springVariantIsPresentOnTheClasspath() {
		assertThat(getClass().getClassLoader().getResource(SPRING_VARIANT))
				.as("%s carries the per-profile appenders and must ship on the classpath", SPRING_VARIANT).isNotNull();
	}

	/**
	 * A standard-location file would be found first by
	 * {@code initializeWithConventions()} and would disable the {@code -spring}
	 * variant.
	 */
	@Test
	void noStandardLocationLogbackFileShipsOnTheClasspath() {
		for (String location : STANDARD_LOGBACK_LOCATIONS) {
			assertThat(getClass().getClassLoader().getResource(location))
					.as("%s must not ship: Spring Boot would initialize from it and never load %s, "
							+ "silently dropping every <springProfile> appender", location, SPRING_VARIANT)
					.isNull();
		}
	}

	/**
	 * With no standard-location file there is nothing to steer Boot past, so
	 * {@code logging.config} stays unset and Boot resolves {@code -spring} by
	 * convention. The property remains available to operators via the environment.
	 */
	@Test
	void bootResolvesTheSpringVariantByConvention() {
		assertThat(applicationProperties().getProperty("logging.config"))
				.as("logging.config is an operator override for an external file, not a way to pick between "
						+ "classpath files; leave it unset and let Boot find %s by convention", SPRING_VARIANT)
				.isNull();
	}

	/**
	 * The status listener has to be in place before logback prints its status list,
	 * i.e. before the first {@code LoggerFactory} touch. The only code that runs
	 * that early is {@code main()}.
	 */
	@Test
	void mainInstallsAStatusListenerLogbackHonoursDuringItsOwnInitialization() {
		Main.silenceLogbackStatusOutput();

		LoggerContext context = new LoggerContext();
		StatusListenerConfigHelper.installIfAsked(context);

		assertThat(StatusUtil.contextHasStatusListener(context))
				.as("LogbackServiceProvider prints the status list to stdout unless a listener is installed; "
						+ "%s must name one", Main.LOGBACK_STATUS_LISTENER_PROPERTY)
				.isTrue();
	}

	/** An operator debugging logback itself keeps their {@code -D} override. */
	@Test
	void mainDoesNotOverrideAnOperatorSuppliedStatusListener() {
		String operatorChoice = OnConsoleStatusListener.class.getName();
		System.setProperty(Main.LOGBACK_STATUS_LISTENER_PROPERTY, operatorChoice);

		Main.silenceLogbackStatusOutput();

		assertThat(System.getProperty(Main.LOGBACK_STATUS_LISTENER_PROPERTY)).isEqualTo(operatorChoice);
	}

	private Properties applicationProperties() {
		Properties properties = new Properties();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
			assertThat(in).as("application.properties must be on the classpath").isNotNull();
			properties.load(in);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return properties;
	}
}
