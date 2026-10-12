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

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Applies Spring Boot's OTLP trace and log export switches at runtime, so
 * {@code OTEL_EXPORT_ENABLED=false} also works in the native image.
 *
 * <p>
 * {@code application-http.properties} maps {@code OTEL_EXPORT_ENABLED} onto
 * {@code management.tracing.export.otlp.enabled},
 * {@code management.otlp.metrics.export.enabled} and
 * {@code management.logging.export.otlp.enabled}. On the JVM those properties
 * are bean conditions and remove the exporters. In the native image the
 * conditions were evaluated during AOT processing, where the switch is unset,
 * so the OTLP span and log exporters are always registered and every export to
 * an unreachable collector logs a connection error. This post-processor reads
 * the same properties at runtime and replaces a disabled exporter with a no-op
 * one. Metrics need nothing here: Micrometer checks its {@code enabled}
 * property again when the OTLP registry starts publishing.
 */
@Component
@Profile("http")
class OtlpExportSwitch implements BeanPostProcessor {

	static final String TRACES_ENABLED = "management.tracing.export.otlp.enabled";

	static final String LOGS_ENABLED = "management.logging.export.otlp.enabled";

	private final Environment environment;

	OtlpExportSwitch(Environment environment) {
		this.environment = environment;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if ((bean instanceof OtlpHttpSpanExporter || bean instanceof OtlpGrpcSpanExporter)
				&& !enabled(TRACES_ENABLED)) {
			((SpanExporter) bean).shutdown();
			return SpanExporter.composite();
		}
		if ((bean instanceof OtlpHttpLogRecordExporter || bean instanceof OtlpGrpcLogRecordExporter)
				&& !enabled(LOGS_ENABLED)) {
			((LogRecordExporter) bean).shutdown();
			return LogRecordExporter.composite();
		}
		return bean;
	}

	private boolean enabled(String property) {
		return this.environment.getProperty(property, Boolean.class, true);
	}
}
