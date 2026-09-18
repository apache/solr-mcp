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

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.test.context.aot.TestRuntimeHintsRegistrar;

/**
 * Makes the sample-data fixtures in {@code src/test/resources} readable from
 * the native test binary.
 *
 * <p>
 * Tests load these through {@code getResourceAsStream}, which GraalVM only
 * answers for resources embedded in the image. Spring AOT already registers
 * {@code *.json}, so {@code shows.json} worked by a mechanism this project does
 * not own, while the {@code shows.csv} and {@code shows.xml} that arrived with
 * the cross-format parity test did not. Registering all three here removes that
 * asymmetry: the dataset is covered by one rule in one place, whatever format a
 * test reads it in.
 *
 * <p>
 * The patterns name the fixtures rather than matching by extension because
 * {@code registerPattern} compiles {@code *} to {@code .*}, which crosses
 * {@code /}: a {@code *.xml} here would embed all 124 XML resources found on
 * this project's 210-jar test classpath, declaring every XML in every
 * dependency to be a test fixture. A new fixture is one line below.
 *
 * <p>
 * Registered for every test class via
 * {@code src/test/resources/META-INF/spring/aot.factories}. It lives in test
 * sources on purpose: {@code SolrNativeHints} is the production equivalent and
 * must not ship test fixture names in the application image.
 *
 * @see org.apache.solr.mcp.server.indexing.ShowsSampleDataIntegrationTest
 */
public class SampleDataRuntimeHints implements TestRuntimeHintsRegistrar {

	/** Default constructor used by Spring's AOT services loader. */
	public SampleDataRuntimeHints() {
	}

	@Override
	public void registerHints(RuntimeHints hints, Class<?> testClass, ClassLoader classLoader) {
		hints.resources().registerPattern("shows.json").registerPattern("shows.csv").registerPattern("shows.xml");
	}
}
