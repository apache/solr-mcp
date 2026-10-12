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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link OtlpExportSwitch}, the runtime half of
 * {@code OTEL_EXPORT_ENABLED}. On the JVM Spring Boot's bean conditions already
 * drop the exporters, so these tests are what covers the native image path,
 * where the conditions were fixed during AOT processing.
 */
class OtlpExportSwitchTest {

	private static final String UNREACHABLE = "http://localhost:4999";

	@Test
	void replacesSpanExporterWhenTraceExportDisabled() {
		var exportSwitch = switchWith(OtlpExportSwitch.TRACES_ENABLED, "false");
		var exporter = OtlpHttpSpanExporter.builder().setEndpoint(UNREACHABLE + "/v1/traces").build();

		Object result = exportSwitch.postProcessAfterInitialization(exporter, "otlpHttpSpanExporter");

		assertThat(result).isInstanceOf(SpanExporter.class).isNotInstanceOf(OtlpHttpSpanExporter.class);
	}

	@Test
	void replacesLogRecordExporterWhenLogExportDisabled() {
		var exportSwitch = switchWith(OtlpExportSwitch.LOGS_ENABLED, "false");
		var exporter = OtlpHttpLogRecordExporter.builder().setEndpoint(UNREACHABLE + "/v1/logs").build();

		Object result = exportSwitch.postProcessAfterInitialization(exporter, "otlpHttpLogRecordExporter");

		assertThat(result).isInstanceOf(LogRecordExporter.class).isNotInstanceOf(OtlpHttpLogRecordExporter.class);
	}

	@Test
	void keepsExportersWhenPropertiesUnset() {
		var exportSwitch = new OtlpExportSwitch(new MockEnvironment());
		var spans = OtlpHttpSpanExporter.builder().setEndpoint(UNREACHABLE + "/v1/traces").build();
		var logs = OtlpHttpLogRecordExporter.builder().setEndpoint(UNREACHABLE + "/v1/logs").build();

		assertThat(exportSwitch.postProcessAfterInitialization(spans, "otlpHttpSpanExporter")).isSameAs(spans);
		assertThat(exportSwitch.postProcessAfterInitialization(logs, "otlpHttpLogRecordExporter")).isSameAs(logs);
	}

	@Test
	void leavesOtherExportersAlone() {
		var exportSwitch = switchWith(OtlpExportSwitch.TRACES_ENABLED, "false");
		SpanExporter other = SpanExporter.composite();

		assertThat(exportSwitch.postProcessAfterInitialization(other, "customSpanExporter")).isSameAs(other);
	}

	private static OtlpExportSwitch switchWith(String property, String value) {
		return new OtlpExportSwitch(new MockEnvironment().withProperty(property, value));
	}
}
