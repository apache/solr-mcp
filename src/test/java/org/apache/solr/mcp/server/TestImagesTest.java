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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

/**
 * Guards the Testcontainers image pins. Every image the tests start must carry
 * an explicit, non-floating tag so a run is reproducible and an image bump is a
 * reviewed change to {@code gradle/libs.versions.toml}, never a silent pull of
 * whatever {@code latest} means today.
 */
class TestImagesTest {

	@Test
	void solrImageIsPinnedToAnExactTag() {
		DockerImageName image = DockerImageName.parse(TestImages.pinned(TestImages.SOLR_PROPERTY));

		assertEquals("solr", image.getRepository());
		assertPinned(image);
	}

	@Test
	void lgtmImageIsPinnedToAnExactTag() {
		DockerImageName image = DockerImageName.parse(TestImages.pinned(TestImages.LGTM_PROPERTY));

		assertEquals("grafana/otel-lgtm", image.getRepository());
		assertPinned(image);
	}

	private static void assertPinned(DockerImageName image) {
		String tag = image.getVersionPart();
		assertFalse(tag.isBlank(), "image must carry an explicit tag: " + image);
		assertFalse("latest".equals(tag), "image must not float on latest: " + image);
		assertTrue(tag.matches("\\d+\\.\\d+\\.\\d+.*"), "tag must be an exact release, not a moving minor: " + image);
	}
}
