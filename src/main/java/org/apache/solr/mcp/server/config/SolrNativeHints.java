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

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * GraalVM native image reachability hints for SolrJ types this project actually
 * uses.
 *
 * <p>
 * The project uses the JSON wire format (SolrJ {@code JsonResponseParser} on
 * {@code HttpJdkSolrClient}), avoiding the JavaBin/XML codec paths that
 * historically drive most SolrJ native-image issues. The hints below cover the
 * narrow remaining surface: response containers and the {@code NamedList} admin
 * shape returned by the mbeans path.
 *
 * <p>
 * This class is registered unconditionally — on the JVM path it is a no-op
 * because no runtime reflection happens against these types at startup. It is
 * only meaningful when running as a native image.
 *
 * @see SolrConfig
 */
@Configuration
@ImportRuntimeHints(SolrNativeHints.Registrar.class)
public class SolrNativeHints {

	static class Registrar implements RuntimeHintsRegistrar {
		@Override
		public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
			MemberCategory[] categories = {MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
					MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS};

			hints.reflection().registerType(QueryResponse.class, categories);
			hints.reflection().registerType(UpdateResponse.class, categories);

			hints.reflection().registerType(NamedList.class, categories);
			hints.reflection().registerType(SolrDocument.class, categories);
			hints.reflection().registerType(SolrDocumentList.class, categories);

			// Include logback.xml in the native image so logback's early
			// initialization (before Spring Boot) finds it and applies the
			// NopStatusListener. Without this, logback falls through to
			// BasicConfigurator and writes status messages to stdout,
			// corrupting the MCP STDIO JSON-RPC framing.
			hints.resources().registerPattern("logback.xml");
		}
	}
}
