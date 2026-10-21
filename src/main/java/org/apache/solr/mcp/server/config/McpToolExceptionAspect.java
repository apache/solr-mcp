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

import static java.util.Objects.requireNonNullElse;

import java.lang.reflect.Method;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.util.SolrErrorText;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Turns checked exceptions thrown by {@link McpTool} methods into tool errors the MCP client can
 * read, and keeps Solr URLs out of every tool error.
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
 * <p>A {@link SolrException} that escapes a tool, typically SolrJ's {@code RemoteSolrException},
 * already becomes a tool error, but its message is {@code Error from server at <url>: <reason>},
 * and Spring AI also appends its root cause's message. When the message names a URL or there is a
 * cause, the aspect throws a new cause-less {@code SolrException} with the same code, keeping
 * Solr's reason, which tells the model what to fix, and dropping the URL, which can carry
 * credentials. See {@link SolrErrorText}.
 *
 * <p>Any other runtime exception that has a {@code SolrException} among its causes would leak the
 * same way, through the root-cause message Spring AI appends. The aspect replaces it with a
 * cause-less {@link IllegalStateException} whose message mirrors what Spring AI would have shown,
 * the wrapper's message and then Solr's, with both redacted.
 *
 * <p>Other runtime exceptions and errors pass through unchanged. They already become tool errors,
 * and they include method security's {@code AccessDeniedException}.
 */
@Aspect
@Component
public class McpToolExceptionAspect {

  private static final Logger logger = LoggerFactory.getLogger(McpToolExceptionAspect.class);

  @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
  public Object translateToolExceptions(ProceedingJoinPoint joinPoint) throws Throwable {
    try {
      return joinPoint.proceed();
    } catch (SolrException e) {
      String message = requireNonNullElse(e.getMessage(), "Solr rejected the request");
      String redacted = SolrErrorText.redact(message);
      if (e.getCause() == null && redacted.equals(message)) {
        // Already safe, such as the cause-less errors SearchService builds itself.
        throw e;
      }
      logger.warn("MCP tool {} failed", toolName(joinPoint), e);
      throw new SolrException(SolrException.ErrorCode.getErrorCode(e.code()), redacted);
    } catch (RuntimeException e) {
      SolrException solrCause = solrCause(e);
      if (solrCause == null) {
        throw e;
      }
      String tool = toolName(joinPoint);
      logger.warn("MCP tool {} failed", tool, e);
      throw new IllegalStateException(
          SolrErrorText.redact(requireNonNullElse(e.getMessage(), tool + " failed"))
              + System.lineSeparator()
              + SolrErrorText.redact(
                  requireNonNullElse(solrCause.getMessage(), "Solr rejected the request")));
    } catch (Error e) {
      throw e;
    } catch (Exception e) {
      String tool = toolName(joinPoint);
      logger.warn("MCP tool {} failed", tool, e);
      throw new IllegalStateException(
          tool
              + " failed: unable to communicate with Solr. Retry later;"
              + " if it persists, ask the operator to check Solr availability and connection settings.");
    }
  }

  /** The first {@code SolrException} among {@code e}'s causes, if any. */
  private static @Nullable SolrException solrCause(Throwable e) {
    Throwable cause = e.getCause();
    // Bounded, so a cause cycle cannot loop forever.
    for (int depth = 0; cause != null && depth < 16; depth++) {
      if (cause instanceof SolrException solr) {
        return solr;
      }
      cause = cause.getCause();
    }
    return null;
  }

  private static String toolName(ProceedingJoinPoint joinPoint) {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    McpTool tool = method.getAnnotation(McpTool.class);
    return tool == null || tool.name().isEmpty() ? method.getName() : tool.name();
  }
}
