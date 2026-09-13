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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Resolves the Docker images the Testcontainers-based tests start.
 *
 * <p>
 * The pins live in {@code gradle/libs.versions.toml};
 * {@code processTestResources} expands them into
 * {@code test-images.properties}, and the Gradle test tasks also forward them
 * as system properties. A non-blank system property wins, so
 * {@code ./gradlew test -Dsolr.test.image=solr:9.4-slim} still drives the Solr
 * compatibility matrix. The resource fallback keeps class initialisers such as
 * {@code @Container static} fields working wherever the properties are not
 * forwarded, e.g. under {@code processTestAot}.
 */
public final class TestImages {

	public static final String SOLR_PROPERTY = "solr.test.image";
	public static final String LGTM_PROPERTY = "lgtm.test.image";

	private static final String RESOURCE = "/test-images.properties";

	private TestImages() {
	}

	/** @return the Solr image to start, e.g. {@code solr:9.9.0-slim} */
	public static String solr() {
		return resolve(SOLR_PROPERTY);
	}

	/**
	 * @return the Grafana LGTM image to start, e.g.
	 *         {@code grafana/otel-lgtm:0.33.0}
	 */
	public static String lgtm() {
		return resolve(LGTM_PROPERTY);
	}

	private static String resolve(String key) {
		String override = System.getProperty(key);
		if (override != null && !override.isBlank()) {
			return override.trim();
		}
		return pinned(key);
	}

	/** The catalog pin for {@code key}, ignoring any {@code -D} override. */
	static String pinned(String key) {
		String pinned = pins().getProperty(key, "").trim();
		if (pinned.isBlank() || pinned.startsWith("${")) {
			throw new IllegalStateException(key + " is not set. Run the tests through Gradle so " + RESOURCE
					+ " is expanded from gradle/libs.versions.toml, or pass -D" + key + "=<image:tag>.");
		}
		return pinned;
	}

	private static Properties pins() {
		Properties properties = new Properties();
		try (InputStream in = TestImages.class.getResourceAsStream(RESOURCE)) {
			if (in != null) {
				properties.load(in);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + RESOURCE, e);
		}
		return properties;
	}
}
