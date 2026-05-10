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
package org.apache.solr.mcp.server.refguide;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class RefGuideServiceTest {

	@Mock(answer = Answers.RETURNS_DEEP_STUBS)
	private RestClient restClient;

	private RefGuideService refGuideService;

	private static final String SITEMAP_CONTENT = """
			<?xml version="1.0" encoding="UTF-8"?>
			<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
			<url><loc>https://solr.apache.org/guide/solr/latest/configuration-guide/circuit-breakers.html</loc></url>
			<url><loc>https://solr.apache.org/guide/solr/latest/indexing-guide/indexing-with-tika.html</loc></url>
			<url><loc>https://solr.apache.org/guide/solr/9_10/configuration-guide/circuit-breakers.html</loc></url>
			<url><loc>https://solr.apache.org/guide/solr/8_11/indexing-guide/indexing-with-tika.html</loc></url>
			</urlset>
			""";

	@BeforeEach
	void setUp() {
		refGuideService = new RefGuideService(restClient);
		lenient().when(restClient.get().uri(anyString()).retrieve().body(String.class)).thenReturn(SITEMAP_CONTENT);
	}

	@Test
	void testSearchRefGuide_LatestVersion() {
		// When
		List<String> results = refGuideService.searchRefGuide("circuit breakers", "latest");

		// Then
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.get(0).contains("/latest/"));
		assertTrue(results.get(0).contains("circuit-breakers.html"));
	}

	@Test
	void testSearchRefGuide_SpecificVersion() {
		// When
		List<String> results = refGuideService.searchRefGuide("circuit breakers", "9.10");

		// Then
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.get(0).contains("/9_10/"));
		assertTrue(results.get(0).contains("circuit-breakers.html"));
	}

	@Test
	void testSearchRefGuide_DefaultVersion() {
		// When
		List<String> results = refGuideService.searchRefGuide("tika", null);

		// Then
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.get(0).contains("/latest/"));
		assertTrue(results.get(0).contains("indexing-with-tika.html"));
	}

	@Test
	void testSearchRefGuide_MultipleWords() {
		// When
		List<String> results = refGuideService.searchRefGuide("indexing tika", "8.11");

		// Then
		assertNotNull(results);
		assertEquals(1, results.size());
		assertTrue(results.get(0).contains("/dist/lucene/solr/ref-guide/"));
		assertTrue(results.get(0).contains("apache-solr-ref-guide-8.11.pdf"));
	}

	@Test
	void testSearchRefGuide_VersionLessThanNine() {
		// When
		List<String> results = refGuideService.searchRefGuide("anything", "7.7");

		// Then
		assertNotNull(results);
		assertEquals(1, results.size());
		assertEquals("https://archive.apache.org/dist/lucene/solr/ref-guide/apache-solr-ref-guide-7.7.pdf",
				results.get(0));
	}

	@Test
	void testSearchRefGuide_NoResults() {
		// When
		List<String> results = refGuideService.searchRefGuide("nonexistent topic", "latest");

		// Then
		assertNotNull(results);
		assertTrue(results.isEmpty());
	}
}
