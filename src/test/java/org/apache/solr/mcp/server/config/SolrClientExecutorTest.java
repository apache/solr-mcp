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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * The executor {@link SolrConfig} gives the Solr client: daemon threads, so a STDIO server exits
 * when the client closes stdin, without displacing Spring Boot's own task executor.
 */
@DisabledInNativeImage // ApplicationContextRunner relies on JDK proxies
class SolrClientExecutorTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(UserConfigurations.of(SolrConfig.class))
          .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withPropertyValues("solr.url=http://localhost:8983/solr/");

  @Test
  void solrClientThreadsAreDaemonThreads() {
    contextRunner.run(
        context -> {
          ExecutorService executor =
              context.getBean(SolrConfig.SOLR_CLIENT_EXECUTOR, ExecutorService.class);
          boolean daemon =
              executor.submit(() -> Thread.currentThread().isDaemon()).get(10, TimeUnit.SECONDS);
          assertThat(daemon)
              .as("a non-daemon thread keeps a STDIO server alive after EOF")
              .isTrue();
        });
  }

  @Test
  void bootStillAutoConfiguresTheApplicationTaskExecutor() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasBean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME));
  }

  @Test
  void contextClosesTheExecutor() {
    AtomicReference<ExecutorService> executor = new AtomicReference<>();
    contextRunner.run(
        context ->
            executor.set(context.getBean(SolrConfig.SOLR_CLIENT_EXECUTOR, ExecutorService.class)));
    assertThat(executor.get().isShutdown())
        .as("closing the context shuts the executor down")
        .isTrue();
  }
}
