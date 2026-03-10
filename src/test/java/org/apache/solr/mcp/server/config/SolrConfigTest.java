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

import static org.junit.jupiter.api.Assertions.*;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SolrConfigTest {

	@Autowired
	private SolrClient solrClient;

	@Autowired
	SolrContainer solrContainer;

	@Autowired
	private SolrConfigurationProperties properties;

	@Test
	void testSolrClientConfiguration() {
		// Verify that the SolrClient is properly configured
		assertNotNull(solrClient);

		// Verify that the SolrClient is using the correct URL
		// Note: SolrConfig normalizes the URL to have trailing slash, but
		// HttpJdkSolrClient removes
		// it
		var httpSolrClient = assertInstanceOf(HttpJdkSolrClient.class, solrClient);
		String expectedUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr";
		assertEquals(expectedUrl, httpSolrClient.getBaseURL());
	}

	@Test
	void testSolrConfigurationProperties() {
		// Verify that the properties are correctly loaded
		assertNotNull(properties);
		assertNotNull(properties.url());
		assertEquals("http://" + solrContainer.getHost() + ":" + solrContainer.getMappedPort(8983) + "/solr/",
				properties.url());
	}

}
