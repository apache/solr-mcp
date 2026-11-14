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

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.transport.StdioMcpTransport;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the STDIO server on the MCP 2026-07-28 revision ({@code MCP_PROTOCOL=modern}) reliable.
 *
 * <p>Spring AI 2.1.0-RC2 starts the SDK's modern {@link StdioMcpTransport} as soon as its lifecycle
 * bean starts, which is before the application context has finished refreshing, and the transport
 * runs on daemon threads only. Measured against the packaged jar, that causes two failures a
 * desktop client hits on its very first request:
 *
 * <ul>
 *   <li>A request written to stdin straight after launch, as clients do, is read while the context
 *       is still starting. The answer is then random: the correct result, {@code -32602
 *       clientCapabilities is malformed}, {@code -32603 Internal error}, or nothing at all. The
 *       same request sent a few seconds later always succeeds.
 *   <li>STDIO is a non-web application, so once {@code main} returns no non-daemon thread holds the
 *       JVM open. The process exits a moment after startup and a request still in flight is dropped
 *       without a response.
 * </ul>
 *
 * <p>This transport therefore starts reading stdin only once the application is ready, and holds
 * one non-daemon thread until the session ends (the client closes stdin, or the context shuts
 * down). It replaces the auto-configured transport, which is {@code @ConditionalOnMissingBean}.
 * Remove this class once Spring AI defers and keeps alive the modern STDIO transport itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "true")
@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "modern")
class ModernStdioTransportConfiguration {

  static final String KEEP_ALIVE_THREAD_NAME = "solr-mcp-stdio-keep-alive";

  @Bean
  StdioMcpTransport mcpModernStdioTransport(
      McpServer mcpServer, @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper) {
    return new ReadyStdioMcpTransport(mcpServer, new JacksonMcpJsonMapper(jsonMapper));
  }

  /**
   * A modern STDIO transport that starts reading on {@link ApplicationReadyEvent} and keeps a
   * non-daemon thread alive for the length of the session.
   */
  static final class ReadyStdioMcpTransport extends StdioMcpTransport
      implements ApplicationListener<ApplicationReadyEvent> {

    private final Sinks.Empty<Void> session = Sinks.empty();

    private final AtomicBoolean started = new AtomicBoolean();

    ReadyStdioMcpTransport(McpServer server, JacksonMcpJsonMapper jsonMapper) {
      super(server, jsonMapper);
    }

    /** Called by Spring AI's lifecycle during refresh; reading waits for the ready event. */
    @Override
    public Mono<Void> start() {
      return session.asMono();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
      if (!started.compareAndSet(false, true)) {
        return;
      }
      Mono<Void> reading = super.start();
      Thread keepAlive =
          new Thread(
              () -> {
                reading.onErrorComplete().block();
                session.tryEmitEmpty();
              },
              KEEP_ALIVE_THREAD_NAME);
      keepAlive.setDaemon(false);
      keepAlive.start();
    }
  }
}
