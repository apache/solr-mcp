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
 * Installs the OpenTelemetry Logback appender to enable OTLP log export.
 *
 * <p>
 * This component connects the OpenTelemetry Logback appender (configured in
 * logback-spring.xml) to the Spring-managed OpenTelemetry SDK instance. Without
 * this installation step, logs would not be exported via OTLP.
 *
 * <p>
 * <strong>Profile Restriction:</strong> This component is only active when the
 * "http" Spring profile is active. In STDIO mode, log export is disabled to
 * prevent stdout pollution that would interfere with MCP protocol
 * communication.
 *
 * @version 0.0.2
 * @since 0.0.2
 * @see OpenTelemetryAppender
 */
@Component
@Profile("http")
public class OpenTelemetryAppenderInstaller implements InitializingBean {

	private final OpenTelemetry openTelemetry;

	/**
	 * Constructs the installer with the Spring-managed OpenTelemetry instance.
	 *
	 * @param openTelemetry
	 *            the OpenTelemetry SDK instance configured by Spring Boot
	 */
	public OpenTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	/**
	 * Installs the OpenTelemetry SDK into the Logback appender after bean
	 * initialization.
	 *
	 * <p>
	 * This method is called by Spring after all properties are set. It connects the
	 * Logback OpenTelemetryAppender to the SDK, enabling log export via OTLP.
	 */
	@Override
	public void afterPropertiesSet() {
		OpenTelemetryAppender.install(openTelemetry);
	}
}
