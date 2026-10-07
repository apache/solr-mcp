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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Installs the application's {@link OpenTelemetry} instance into the Logback
 * {@link OpenTelemetryAppender} so that log records are exported over OTLP.
 *
 * <p>
 * Spring Boot 4 does not bundle the OpenTelemetry Logback appender, and the
 * appender needs programmatic access to an {@link OpenTelemetry} instance at
 * runtime — it cannot obtain one from the Spring context on its own. This
 * {@link InitializingBean} performs that one-time wiring once the
 * {@link OpenTelemetry} bean is available.
 *
 * <p>
 * Active only in the {@code http} profile, where observability export is
 * enabled; the {@code stdio} transport keeps stdout clean for the MCP JSON-RPC
 * stream and does not export logs. Log records emitted before this bean
 * initializes are not exported via OTLP.
 */
@Component
@Profile("http")
class InstallOpenTelemetryAppender implements InitializingBean {

	private final OpenTelemetry openTelemetry;

	InstallOpenTelemetryAppender(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	@Override
	public void afterPropertiesSet() {
		OpenTelemetryAppender.install(this.openTelemetry);
	}
}
