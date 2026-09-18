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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BuildInfoReader}'s Docker image name resolution.
 *
 * <p>
 * The {@code solr.mcp.docker.image.tag.suffix} handling is otherwise exercised
 * only by {@code dockerIntegrationTest}, which regular CI never runs — that gap
 * is how PR #139 could silently drop the suffix and break the native image
 * matrix. These tests pin the contract in the default {@code test} task.
 */
class BuildInfoReaderTest {

	private static final String SUFFIX_PROPERTY = "solr.mcp.docker.image.tag.suffix";

	private String previousSuffix;

	@BeforeEach
	void captureSuffixProperty() {
		previousSuffix = System.getProperty(SUFFIX_PROPERTY);
		System.clearProperty(SUFFIX_PROPERTY);
	}

	@AfterEach
	void restoreSuffixProperty() {
		if (previousSuffix == null) {
			System.clearProperty(SUFFIX_PROPERTY);
		} else {
			System.setProperty(SUFFIX_PROPERTY, previousSuffix);
		}
	}

	@Test
	void plainImageNameWhenSuffixUnset() {
		assertEquals(BuildInfoReader.getArtifact() + ":" + BuildInfoReader.getVersion(),
				BuildInfoReader.getDockerImageName());
	}

	@Test
	void appendsSuffixPropertyToImageName() {
		System.setProperty(SUFFIX_PROPERTY, "-native-stdio");
		assertEquals(BuildInfoReader.getArtifact() + ":" + BuildInfoReader.getVersion() + "-native-stdio",
				BuildInfoReader.getDockerImageName());
	}
}
