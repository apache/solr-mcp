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

import java.util.Locale;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.springframework.util.StringUtils;

/**
 * Immutable record representing a single sort clause of the {@code search} MCP
 * tool.
 *
 * <p>
 * Because this is a record, its components become named properties in the JSON
 * schema that MCP clients receive, making the contract self-documenting —
 * unlike a {@code Map} parameter whose expected keys are invisible to the
 * schema.
 *
 * @param field
 *            the Solr field name to sort on
 * @param order
 *            the sort direction, {@code asc} or {@code desc} (case
 *            insensitive); defaults to {@code asc} when omitted
 */
public record SortClause(String field, String order) {

	/** Default sort order applied when {@code order} is omitted. */
	private static final String DEFAULT_ORDER = "asc";

	/**
	 * Converts this clause to a SolrJ {@link SolrQuery.SortClause}, normalizing the
	 * order to lowercase and defaulting a missing order to {@code asc}.
	 *
	 * @return the equivalent SolrJ sort clause
	 * @throws IllegalArgumentException
	 *             if {@code field} is missing or {@code order} is neither
	 *             {@code asc} nor {@code desc}; the message names the offending
	 *             value so MCP clients can correct the call
	 */
	SolrQuery.SortClause toSolrSortClause() {
		if (!StringUtils.hasText(field)) {
			throw new IllegalArgumentException("Sort clause is missing 'field': provide the field name to sort on");
		}
		String normalized = StringUtils.hasText(order) ? order.toLowerCase(Locale.ROOT) : DEFAULT_ORDER;
		if (!"asc".equals(normalized) && !"desc".equals(normalized)) {
			throw new IllegalArgumentException(
					"Invalid sort order '" + order + "' for field '" + field + "': must be 'asc' or 'desc'");
		}
		return new SolrQuery.SortClause(field, normalized);
	}
}
