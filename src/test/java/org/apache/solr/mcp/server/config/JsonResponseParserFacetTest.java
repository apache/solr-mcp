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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for how {@link JsonResponseParser} decodes Solr's facet payloads.
 *
 * <p>
 * SolrJ's {@code QueryResponse.getFacetFields()} casts each facet field's value
 * to {@link NamedList}. Solr's JSON writer emits a facet field as a flat array
 * ({@code json.nl=flat}), and crucially emits an <em>empty</em> facet as
 * {@code []} — indistinguishable, by shape alone, from an ordinary empty list
 * such as {@code "collections": []}. Deciding by shape therefore cannot be
 * correct for both; the parser has to use the enclosing key as context.
 */
class JsonResponseParserFacetTest {

	private static final String EMPTY_FACET_RESPONSE = """
			{
			  "responseHeader": { "status": 0, "QTime": 3 },
			  "response": { "numFound": 0, "start": 0, "docs": [] },
			  "facet_counts": {
			    "facet_queries": {},
			    "facet_fields": { "platform": [] },
			    "facet_ranges": {},
			    "facet_intervals": {},
			    "facet_heatmaps": {}
			  }
			}
			""";

	private static final String POPULATED_FACET_RESPONSE = """
			{
			  "responseHeader": { "status": 0, "QTime": 3 },
			  "response": { "numFound": 27, "start": 0, "docs": [] },
			  "facet_counts": {
			    "facet_queries": {},
			    "facet_fields": { "platform": ["Netflix", 20, "HBO Max", 7] },
			    "facet_ranges": {},
			    "facet_intervals": {},
			    "facet_heatmaps": {}
			  }
			}
			""";

	private static final String EMPTY_COLLECTION_LIST_RESPONSE = """
			{
			  "responseHeader": { "status": 0, "QTime": 1 },
			  "collections": []
			}
			""";

	private NamedList<Object> parse(String json) {
		return new JsonResponseParser(new ObjectMapper())
				.processResponse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "UTF-8");
	}

	private Object facetField(NamedList<Object> response, String field) {
		NamedList<?> facetCounts = (NamedList<?>) response.get("facet_counts");
		NamedList<?> facetFields = (NamedList<?>) facetCounts.get("facet_fields");
		return facetFields.get(field);
	}

	@Test
	void facetFieldWithNoBucketsParsesAsNamedList() {
		Object platform = facetField(parse(EMPTY_FACET_RESPONSE), "platform");

		// SolrJ casts this to NamedList; a List here throws ClassCastException
		// for any faceted query whose filter happens to match zero documents.
		NamedList<?> buckets = assertInstanceOf(NamedList.class, platform,
				"An empty facet field must decode as a NamedList, not a List");
		assertEquals(0, buckets.size(), "An empty facet field has no buckets");
	}

	@Test
	void facetFieldWithBucketsParsesAsNamedList() {
		Object platform = facetField(parse(POPULATED_FACET_RESPONSE), "platform");

		NamedList<?> buckets = assertInstanceOf(NamedList.class, platform,
				"A populated facet field must decode as a NamedList");
		assertEquals(2, buckets.size(), "Two facet buckets were returned");
		assertEquals(20, buckets.get("Netflix"), "Bucket counts survive decoding");
		assertEquals(7, buckets.get("HBO Max"), "Bucket counts survive decoding");
	}

	@Test
	void emptyTopLevelArrayStaysAList() {
		Object collections = parse(EMPTY_COLLECTION_LIST_RESPONSE).get("collections");

		// CollectionService.listCollections() casts this to List<String>. Treating
		// every empty array as a NamedList would move the ClassCastException here,
		// breaking list-collections against an empty cluster.
		List<?> names = assertInstanceOf(List.class, collections,
				"An empty non-facet array must stay a List so list-collections keeps working");
		assertEquals(0, names.size(), "No collections exist");
	}
}
