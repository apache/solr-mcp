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
package org.apache.solr.mcp.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.solr.client.solrj.request.SolrQuery;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SortClause} conversion to the SolrJ representation.
 */
class SortClauseTest {

	@Test
	void convertsFieldAndOrder() {
		SolrQuery.SortClause solr = new SortClause("price", "desc").toSolrSortClause();
		assertEquals("price", solr.getItem());
		assertEquals(SolrQuery.ORDER.desc, solr.getOrder());
	}

	@Test
	void normalizesOrderCase() {
		assertEquals(SolrQuery.ORDER.desc, new SortClause("price", "DESC").toSolrSortClause().getOrder());
	}

	@Test
	void defaultsMissingOrderToAscending() {
		assertEquals(SolrQuery.ORDER.asc, new SortClause("price", null).toSolrSortClause().getOrder());
		assertEquals(SolrQuery.ORDER.asc, new SortClause("price", " ").toSolrSortClause().getOrder());
	}

	@Test
	void rejectsInvalidOrderNamingTheValue() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new SortClause("price", "descending").toSolrSortClause());
		assertTrue(e.getMessage().contains("descending"));
		assertTrue(e.getMessage().contains("price"));
	}

	@Test
	void rejectsMissingField() {
		assertThrows(IllegalArgumentException.class, () -> new SortClause(null, "asc").toSolrSortClause());
		assertThrows(IllegalArgumentException.class, () -> new SortClause(" ", "asc").toSolrSortClause());
	}
}
