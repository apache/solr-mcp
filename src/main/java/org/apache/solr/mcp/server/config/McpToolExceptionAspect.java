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

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Turns checked exceptions thrown by {@link McpTool} methods into tool errors the MCP client can
 * read.
 *
 * <p>Spring AI 2.0 reports a {@link RuntimeException} from a tool as an {@code isError} result, but
 * wraps a checked exception in {@link java.lang.reflect.UndeclaredThrowableException} and rethrows
 * it. The MCP SDK then answers with a bare JSON-RPC {@code -32603} error, so a model calling a tool
 * while Solr is unreachable gets no text it can act on. Spring AI has no hook for this
 * (spring-projects/spring-ai#4488), so this aspect translates at the tool boundary instead of in
 * each of the tools.
 *
 * <p>Every checked exception a tool throws today comes from SolrJ ({@code SolrServerException},
 * {@code IOException}), so the message says Solr could not be reached. A tool whose checked
 * exceptions come from elsewhere must translate them itself before they reach this aspect. The
 * replacement carries no cause: SolrJ's message names the internal Solr URL, and Spring AI copies
 * the root cause's message into the tool error. The original is logged here instead.
 *
 * <p>Runtime exceptions and errors pass through unchanged. They already become tool errors, and
 * they include method security's {@code AccessDeniedException}.
 */
@Aspect
@Component
public class McpToolExceptionAspect {

  private static final Logger logger = LoggerFactory.getLogger(McpToolExceptionAspect.class);

  @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
  public Object translateCheckedExceptions(ProceedingJoinPoint joinPoint) throws Throwable {
    try {
      return joinPoint.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      String tool = toolName(((MethodSignature) joinPoint.getSignature()).getMethod());
      logger.warn("MCP tool {} failed", tool, e);
      throw new IllegalStateException(
          tool
              + " failed: unable to communicate with Solr. Retry later;"
              + " if it persists, ask the operator to check Solr availability and connection settings.");
    }
  }

  private static String toolName(Method method) {
    McpTool tool = method.getAnnotation(McpTool.class);
    return tool == null || tool.name().isEmpty() ? method.getName() : tool.name();
  }
}
