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

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final int SOLR_PORT = 8983;

	private static final String SOLR_IMAGE_PROPERTY = "solr.test.image";

	/**
	 * The Solr image under test: pinned in gradle/libs.versions.toml as
	 * test-image-solr; -Dsolr.test.image overrides it for a single run (e.g. the
	 * Solr compatibility matrix in CI).
	 */
	public static String solrImage() {
		return System.getProperty(SOLR_IMAGE_PROPERTY, "solr:9.9.0-slim");
	}

	/**
	 * Major version of the Solr image under test, read from its tag
	 * ({@code solr:10-slim} is 10, {@code solr:8.11-slim} is 8), for tests whose
	 * expected outcome differs by Solr version.
	 */
	public static int solrMajorVersion() {
		String tag = DockerImageName.parse(solrImage()).getVersionPart();
		return Integer.parseInt(tag.split("[.-]", 2)[0]);
	}

	@Bean
	SolrContainer solr() {
		return new SolrContainer(DockerImageName.parse(solrImage()));
	}

	@Bean
	DynamicPropertyRegistrar propertiesRegistrar(SolrContainer solr) {
		return registry -> registry.add("solr.url",
				() -> "http://" + solr.getHost() + ":" + solr.getMappedPort(SOLR_PORT) + "/solr/");
	}
}
