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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.solr.client.solrj.response.ResponseParser;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;

/**
 * SolrJ {@link ResponseParser} that requests JSON wire format ({@code wt=json})
 * and converts the Solr JSON response into the {@link NamedList} object tree
 * that SolrJ's typed response classes
 * ({@link org.apache.solr.client.solrj.response.QueryResponse},
 * {@link org.apache.solr.client.solrj.response.LukeResponse}, etc.) expect
 * internally.
 *
 * <p>
 * This allows the application to keep all existing SolrJ response handling
 * unchanged while moving off the JavaBin ({@code wt=javabin}) wire format.
 *
 * <p>
 * <strong>Structural conversions:</strong>
 * <ul>
 * <li>JSON objects → {@link NamedList} (preserving key order)</li>
 * <li>JSON objects containing {@code numFound} + {@code docs} →
 * {@link SolrDocumentList}</li>
 * <li>JSON arrays with alternating {@code [String, non-String, ...]} pairs →
 * {@link NamedList} (Solr's {@code json.nl=flat} facet encoding)</li>
 * <li>All other JSON arrays → {@link List}</li>
 * <li>JSON integers → {@link Integer} or {@link Long} (by value size)</li>
 * <li>JSON decimals → {@link Double}</li>
 * <li>JSON booleans → {@link Boolean}</li>
 * <li>JSON strings → {@link String}</li>
 * </ul>
 *
 * <p>
 * <strong>Flat NamedList detection:</strong> Solr serializes facet counts as
 * flat arrays using {@code json.nl=flat} (the default). An array is treated as
 * a flat NamedList when every even-indexed element is a {@link String} and
 * every odd-indexed element is a non-{@link String} value. This reliably
 * distinguishes {@code ["term", 5, "term2", 3]} (facet NamedList) from
 * {@code ["col1", "col2"]} (plain string list).
 *
 * <p>
 * <strong>Why shape alone is not enough:</strong> an <em>empty</em> array is
 * ambiguous. Solr writes a facet with no buckets as {@code []}, and also writes
 * an ordinary empty list — {@code "collections": []} from the Collections API —
 * as {@code []}. The first must decode to a {@link NamedList} because SolrJ's
 * {@code QueryResponse.getFacetFields()} casts to one; the second must stay a
 * {@link List} because {@code CollectionAdminResponse} callers cast to that. No
 * rule based on the array can satisfy both, so the parser uses the enclosing
 * key instead: arrays directly inside {@code facet_fields},
 * {@code facet_queries} and {@code facet_intervals} are always NamedLists,
 * empty or not, and every other array falls back to the shape heuristic above.
 *
 * <p>
 * <strong>Known gap:</strong> {@code facet_ranges} nests its flat list one
 * level deeper, under a {@code counts} key, so an empty range facet would still
 * decode as a {@link List}. The {@code search} tool does not expose range
 * faceting, so nothing currently reaches that path.
 */
class JsonResponseParser extends ResponseParser {

	/**
	 * Response keys whose immediate children are always NamedLists of counts,
	 * regardless of how many buckets came back. Solr writes each child as a flat
	 * array under {@code json.nl=flat}, and writes an empty one as {@code []} —
	 * which is shape-identical to an ordinary empty list such as
	 * {@code "collections": []}. Only the enclosing key distinguishes them.
	 */
	private static final Set<String> FACET_CONTAINERS = Set.of("facet_fields", "facet_queries", "facet_intervals");

	private final ObjectMapper mapper;

	JsonResponseParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public String getWriterType() {
		return "json";
	}

	@Override
	public Collection<String> getContentTypes() {
		// Some Solr endpoints (notably /admin/ping and some standalone-mode handlers)
		// return JSON-encoded bodies with Content-Type: text/plain. Accept it so SolrJ
		// does not reject otherwise-valid responses.
		return List.of(MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE);
	}

	@Override
	public NamedList<Object> processResponse(InputStream body, String encoding) {
		try {
			return toNamedList(mapper.readTree(body));
		} catch (IOException e) {
			throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Failed to parse Solr JSON response", e);
		}
	}

	private SimpleOrderedMap<Object> toNamedList(JsonNode objectNode) {
		SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();
		objectNode.fields()
				.forEachRemaining(entry -> result.add(entry.getKey(),
						FACET_CONTAINERS.contains(entry.getKey()) && entry.getValue().isObject()
								? toFacetContainer(entry.getValue())
								: convertValue(entry.getValue())));
		return result;
	}

	/**
	 * Converts a facet container, forcing every array child to a NamedList.
	 *
	 * <p>
	 * Inside these containers an array is a flat NamedList by definition, so the
	 * {@link #isFlatNamedList} heuristic is neither needed nor safe: it rejects
	 * empty arrays, which would hand SolrJ an {@code ArrayList} where
	 * {@code QueryResponse.getFacetFields()} casts to {@code NamedList}.
	 */
	private SimpleOrderedMap<Object> toFacetContainer(JsonNode containerNode) {
		SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();
		containerNode.fields().forEachRemaining(entry -> result.add(entry.getKey(),
				entry.getValue().isArray() ? flatArrayToNamedList(entry.getValue()) : convertValue(entry.getValue())));
		return result;
	}

	private @Nullable Object convertValue(JsonNode node) {
		if (node.isNull())
			return null;
		if (node.isBoolean())
			return node.booleanValue();
		if (node.isTextual())
			return node.textValue();
		if (node.isInt())
			return node.intValue();
		if (node.isLong())
			return node.longValue();
		if (node.isDouble() || node.isFloat())
			return node.floatValue();
		if (node.isObject())
			return convertObject(node);
		if (node.isArray())
			return convertArray(node);
		return node.asText();
	}

	private Object convertObject(JsonNode node) {
		// Detect a Solr query result set by the presence of numFound + docs
		if (node.has("numFound") && node.has("docs")) {
			return toSolrDocumentList(node);
		}
		return toNamedList(node);
	}

	private Object convertArray(JsonNode arrayNode) {
		// Detect Solr's flat NamedList encoding: [String, non-String, String,
		// non-String, ...]
		// Used for facet counts (json.nl=flat default). Distinguished from plain string
		// arrays
		// by requiring odd-indexed elements to be non-string values.
		if (isFlatNamedList(arrayNode)) {
			return flatArrayToNamedList(arrayNode);
		}
		List<Object> list = new ArrayList<>(arrayNode.size());
		arrayNode.forEach(element -> list.add(convertValue(element)));
		return list;
	}

	/**
	 * Returns true when the array has even length, every even-indexed element is a
	 * string (the key), and every odd-indexed element is NOT a string (the value).
	 * This heuristic correctly identifies Solr facet data like
	 * {@code ["fantasy", 10, "scifi", 5]} while rejecting plain string arrays like
	 * {@code ["col1", "col2"]}.
	 */
	private boolean isFlatNamedList(JsonNode arrayNode) {
		int size = arrayNode.size();
		if (size == 0 || size % 2 != 0)
			return false;
		for (int i = 0; i < size; i += 2) {
			if (!arrayNode.get(i).isTextual())
				return false; // key must be string
			if (arrayNode.get(i + 1).isTextual())
				return false; // value must not be string
		}
		return true;
	}

	private SimpleOrderedMap<Object> flatArrayToNamedList(JsonNode arrayNode) {
		SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();
		for (int i = 0; i < arrayNode.size(); i += 2) {
			result.add(arrayNode.get(i).textValue(), convertValue(arrayNode.get(i + 1)));
		}
		return result;
	}

	private SolrDocumentList toSolrDocumentList(JsonNode node) {
		SolrDocumentList list = new SolrDocumentList();
		list.setNumFound(node.get("numFound").longValue());
		list.setStart(node.path("start").longValue());
		JsonNode numFoundExact = node.get("numFoundExact");
		if (numFoundExact != null && !numFoundExact.isNull()) {
			list.setNumFoundExact(numFoundExact.booleanValue());
		}
		JsonNode maxScore = node.get("maxScore");
		if (maxScore != null && !maxScore.isNull()) {
			list.setMaxScore(maxScore.floatValue());
		}
		node.get("docs").forEach(doc -> list.add(toSolrDocument(doc)));
		return list;
	}

	private SolrDocument toSolrDocument(JsonNode node) {
		SolrDocument doc = new SolrDocument();
		node.fields().forEachRemaining(entry -> {
			JsonNode val = entry.getValue();
			if (val.isArray()) {
				// Multi-valued field — always a plain list, never a flat NamedList
				List<Object> values = new ArrayList<>(val.size());
				val.forEach(v -> values.add(convertValue(v)));
				doc.setField(entry.getKey(), values);
			} else {
				doc.setField(entry.getKey(), convertValue(val));
			}
		});
		return doc;
	}
}
