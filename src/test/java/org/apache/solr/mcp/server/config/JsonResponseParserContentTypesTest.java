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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Unit tests verifying the Content-Types accepted by
 * {@link JsonResponseParser}.
 *
 * <p>
 * Some Solr request handlers (notably {@code /admin/ping} and certain
 * standalone-mode paths) return JSON-encoded bodies with Content-Type
 * {@code text/plain} rather than {@code application/json}. SolrJ rejects the
 * response unless the configured
 * {@link org.apache.solr.client.solrj.response.ResponseParser} advertises that
 * Content-Type, so the parser must tolerate both.
 */
class JsonResponseParserContentTypesTest {

	@Test
	void advertisesJsonAndTextPlain() {
		JsonResponseParser parser = new JsonResponseParser(new ObjectMapper());

		Collection<String> contentTypes = parser.getContentTypes();

		assertTrue(contentTypes.contains(MediaType.APPLICATION_JSON_VALUE),
				"Parser must advertise application/json so SolrJ accepts standard Solr responses");
		assertTrue(contentTypes.contains(MediaType.TEXT_PLAIN_VALUE),
				"Parser must advertise text/plain so SolrJ accepts responses from handlers that mislabel JSON bodies");
	}
}
