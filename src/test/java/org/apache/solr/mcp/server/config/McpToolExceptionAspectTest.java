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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Checks {@link McpToolExceptionAspect} through a Spring context instead of {@code
 * AspectJProxyFactory}: the context's proxy class is generated at build time under AOT, so this
 * test also runs in the native test binary.
 */
@SpringJUnitConfig
class McpToolExceptionAspectTest {

  private static final String SOLR_DETAIL =
      "Server refused connection at: http://internal-solr:8983";

  @Configuration
  @EnableAspectJAutoProxy
  static class Config {

    @Bean
    McpToolExceptionAspect mcpToolExceptionAspect() {
      return new McpToolExceptionAspect();
    }

    @Bean
    FixtureTools fixtureTools() {
      return new FixtureTools();
    }
  }

  static class FixtureTools {

    static final RuntimeException RUNTIME_FAILURE = new IllegalArgumentException("bad input");

    static final AccessDeniedException ACCESS_DENIED = new AccessDeniedException("denied");

    @McpTool(name = "checked-tool", description = "Throws a checked exception")
    public String checkedTool() throws IOException {
      throw new IOException(SOLR_DETAIL);
    }

    @McpTool(name = "runtime-tool", description = "Throws a runtime exception")
    public String runtimeTool() {
      throw RUNTIME_FAILURE;
    }

    @McpTool(name = "secured-tool", description = "Throws an access-denied exception")
    public String securedTool() {
      throw ACCESS_DENIED;
    }

    @McpTool(name = "working-tool", description = "Returns normally")
    public String workingTool() {
      return "ok";
    }

    public String notATool() throws IOException {
      throw new IOException(SOLR_DETAIL);
    }
  }

  @Autowired private FixtureTools tools;

  @Test
  void checkedExceptionBecomesAReadableToolError() {
    IllegalStateException e = assertThrows(IllegalStateException.class, tools::checkedTool);

    assertTrue(e.getMessage().startsWith("checked-tool failed:"), e.getMessage());
    assertTrue(e.getMessage().contains("unable to communicate with Solr"), e.getMessage());
    assertFalse(e.getMessage().contains("internal-solr"), e.getMessage());
    assertNull(e.getCause(), "the client error must not carry SolrJ's text");
  }

  @Test
  void runtimeExceptionPassesThroughUnchanged() {
    RuntimeException e = assertThrows(RuntimeException.class, tools::runtimeTool);

    assertSame(FixtureTools.RUNTIME_FAILURE, e);
  }

  @Test
  void accessDeniedPassesThroughUnchanged() {
    AccessDeniedException e = assertThrows(AccessDeniedException.class, tools::securedTool);

    assertSame(FixtureTools.ACCESS_DENIED, e);
  }

  @Test
  void normalReturnPassesThrough() {
    assertEquals("ok", tools.workingTool());
  }

  @Test
  void methodWithoutMcpToolIsNotAdvised() {
    IOException e = assertThrows(IOException.class, tools::notATool);

    assertTrue(e.getMessage().contains("internal-solr"), e.getMessage());
  }
}
