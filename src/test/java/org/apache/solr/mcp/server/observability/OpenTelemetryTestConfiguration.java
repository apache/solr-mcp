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
package org.apache.solr.mcp.server.observability;

import io.micrometer.tracing.test.simple.SimpleTracer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that provides SimpleTracer for capturing spans in tests.
 *
 * <p>
 * This configuration uses Spring Boot 3's recommended approach for testing
 * observability by providing a {@link SimpleTracer} from the
 * {@code micrometer-tracing-test} library.
 *
 * <p>
 * The {@link SimpleTracer} captures spans created via {@code @Observed}
 * annotations through the Micrometer Observation → Micrometer Tracing →
 * OpenTelemetry bridge.
 *
 * <p>
 * By marking this bean as {@code @Primary}, it replaces the OpenTelemetry
 * tracer that would normally be auto-configured, allowing tests to capture and
 * verify spans without requiring external infrastructure.
 *
 * <p>
 * This is the Spring Boot 3-native testing approach, as documented in the
 * Micrometer Tracing reference documentation.
 */
@TestConfiguration
public class OpenTelemetryTestConfiguration {

	/**
	 * Provides a SimpleTracer for tests to capture and verify spans.
	 *
	 * <p>
	 * The {@code @Primary} annotation ensures this tracer is used instead of the
	 * OpenTelemetry tracer that would normally be auto-configured. Spring Boot's
	 * observability auto-configuration will automatically connect this tracer to
	 * the ObservationRegistry through the appropriate handlers.
	 *
	 * <p>
	 * Returning SimpleTracer directly (instead of Tracer interface) allows tests to
	 * inject SimpleTracer and access test-specific methods like getFinishedSpans().
	 *
	 * @return SimpleTracer instance that will be used by the Observation
	 *         infrastructure
	 */
	@Bean
	@Primary
	public SimpleTracer simpleTracer() {
		return new SimpleTracer();
	}

}
