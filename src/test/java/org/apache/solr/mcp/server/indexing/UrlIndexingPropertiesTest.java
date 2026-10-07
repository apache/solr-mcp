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

class UrlIndexingPropertiesTest {

	@Test
	void acceptsTheDefaults() {
		assertDoesNotThrow(() -> new UrlIndexingProperties(List.of("*"), Duration.ofSeconds(10), Duration.ofSeconds(30),
				Duration.ofMinutes(5), 4));
	}

	@Test
	void rejectsANonPositiveTotalTimeout() {
		var e = assertThrows(IllegalArgumentException.class, () -> new UrlIndexingProperties(List.of("*"),
				Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ZERO, 4));
		assertEquals("solr.index-url.total-timeout must be positive", e.getMessage());
	}

	@Test
	void rejectsFewerThanOneConcurrentFetch() {
		var e = assertThrows(IllegalArgumentException.class, () -> new UrlIndexingProperties(List.of("*"),
				Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(5), 0));
		assertEquals("solr.index-url.max-concurrent-fetches must be at least 1", e.getMessage());
	}
}
