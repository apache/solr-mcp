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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonResponseParser}'s conversion of Solr's JSON wire
 * format into the {@link NamedList} tree SolrJ expects.
 *
 * <p>
 * The facet cases are regression coverage: SolrJ's {@link QueryResponse} casts
 * every {@code facet_counts/facet_fields} entry to a {@link NamedList}, so an
 * empty facet array must not be converted to a {@link java.util.List}.
 */
class JsonResponseParserTest {

	private final JsonResponseParser parser = new JsonResponseParser(new ObjectMapper());

	private NamedList<Object> parse(String json) {
		return parser.processResponse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "UTF-8");
	}

	@SuppressWarnings("unchecked")
	private static NamedList<Object> facetFields(NamedList<Object> response) {
		NamedList<Object> facetCounts = (NamedList<Object>) response.get("facet_counts");
		return (NamedList<Object>) facetCounts.get("facet_fields");
	}

	@Test
	@DisplayName("populated facet array converts to a NamedList of counts")
	void populatedFacetBecomesNamedList() {
		NamedList<Object> response = parse("""
				{"facet_counts":{"facet_fields":{"genre":["fantasy",10,"scifi",5]}}}
				""");

		Object genre = facetFields(response).get("genre");
		NamedList<Object> counts = assertInstanceOf(NamedList.class, genre);
		assertEquals(2, counts.size());
		assertEquals(10, counts.get("fantasy"));
		assertEquals(5, counts.get("scifi"));
	}

	@Test
	@DisplayName("empty facet array still converts to a NamedList, not a List")
	void emptyFacetBecomesEmptyNamedList() {
		// A facet on a field where nothing matched. The shape heuristic cannot
		// recognise [] as a flat NamedList, so position in the tree must decide.
		NamedList<Object> response = parse("""
				{"facet_counts":{"facet_fields":{"genre":[]}}}
				""");

		Object genre = facetFields(response).get("genre");
		NamedList<Object> counts = assertInstanceOf(NamedList.class, genre);
		assertEquals(0, counts.size());
	}

	@Test
	@DisplayName("empty array outside facet_fields stays a List")
	void emptyArrayElsewhereStaysList() {
		NamedList<Object> response = parse("""
				{"responseHeader":{"warnings":[]}}
				""");

		@SuppressWarnings("unchecked")
		NamedList<Object> header = (NamedList<Object>) response.get("responseHeader");
		assertInstanceOf(java.util.List.class, header.get("warnings"));
	}

	@Test
	@DisplayName("plain string array is not mistaken for a flat NamedList")
	void plainStringArrayStaysList() {
		NamedList<Object> response = parse("""
				{"responseHeader":{"fields":["col1","col2"]}}
				""");

		@SuppressWarnings("unchecked")
		NamedList<Object> header = (NamedList<Object>) response.get("responseHeader");
		assertInstanceOf(java.util.List.class, header.get("fields"));
	}

	@Test
	@DisplayName("QueryResponse can read facets when one field has zero matches")
	void queryResponseHandlesEmptyFacet() {
		// End-to-end guard: this is the cast that used to throw ClassCastException.
		NamedList<Object> response = parse("""
				{"responseHeader":{"status":0,"QTime":1},
				 "response":{"numFound":0,"start":0,"docs":[]},
				 "facet_counts":{"facet_fields":{"genre":[],"author":["asimov",3]}}}
				""");

		QueryResponse queryResponse = new QueryResponse();
		queryResponse.setResponse(response);

		assertEquals(2, queryResponse.getFacetFields().size());
		assertTrue(queryResponse.getFacetField("genre").getValues().isEmpty());
		assertEquals(1, queryResponse.getFacetField("author").getValues().size());
	}
}
