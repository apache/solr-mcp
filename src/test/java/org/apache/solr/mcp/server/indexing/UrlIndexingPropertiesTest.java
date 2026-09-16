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
package org.apache.solr.mcp.server.indexing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class UrlIndexingPropertiesTest {

	private static final String RANGE = "solr.index-url.max-bytes must be between 1 byte and 2 GB";

	@Test
	void acceptsTheDefaultCap() {
		var properties = properties(DataSize.ofMegabytes(10));
		assertEquals(10L * 1024 * 1024, properties.maxBytes().toBytes());
	}

	@Test
	void acceptsTheBounds() {
		assertDoesNotThrow(() -> properties(DataSize.ofBytes(1)));
		assertDoesNotThrow(() -> properties(DataSize.ofBytes(Integer.MAX_VALUE - 1)));
	}

	@Test
	void rejectsZeroBecauseThereIsNoUnlimited() {
		var e = assertThrows(IllegalArgumentException.class, () -> properties(DataSize.ofBytes(0)));
		assertEquals(RANGE, e.getMessage());
	}

	@Test
	void rejectsACapThatDoesNotFitAnInt() {
		var e = assertThrows(IllegalArgumentException.class, () -> properties(DataSize.ofGigabytes(3)));
		assertEquals(RANGE, e.getMessage());
	}

	private static UrlIndexingProperties properties(DataSize maxBytes) {
		return new UrlIndexingProperties(List.of("*"), Duration.ofSeconds(10), Duration.ofSeconds(30), maxBytes);
	}
}
