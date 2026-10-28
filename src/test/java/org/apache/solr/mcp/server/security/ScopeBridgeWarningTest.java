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
package org.apache.solr.mcp.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class ScopeBridgeWarningTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(ScopeBridgeWarning.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  @Test
  void warnsWhenExpressionGrantsAConstantListOfScopes() {
    var env =
        new MockEnvironment()
            .withProperty(
                ScopeBridgeWarning.EXPRESSIONS_PROPERTY + "[0]",
                "{'solr:read','solr:write','solr:admin'}");

    new ScopeBridgeWarning(env);

    assertEquals(1, logs.list.size());
    assertEquals(Level.WARN, logs.list.getFirst().getLevel());
    assertTrue(logs.list.getFirst().getFormattedMessage().contains("NOT being enforced"));
  }

  @Test
  void staysQuietForExpressionsThatReadTheToken() {
    var env =
        new MockEnvironment()
            .withProperty(ScopeBridgeWarning.EXPRESSIONS_PROPERTY, "[realm_access][roles]");

    new ScopeBridgeWarning(env);

    assertTrue(logs.list.isEmpty());
  }

  @Test
  void staysQuietWhenNoExpressionIsConfigured() {
    new ScopeBridgeWarning(new MockEnvironment());

    assertTrue(logs.list.isEmpty());
  }

  @Test
  void onlyPureStringListsCountAsConstant() {
    assertTrue(ScopeBridgeWarning.isConstantGrant("{'solr:read'}"));
    assertTrue(ScopeBridgeWarning.isConstantGrant(" { \"solr:read\" , 'solr:admin' } "));
    assertFalse(ScopeBridgeWarning.isConstantGrant("[realm_access][roles]"));
    assertFalse(ScopeBridgeWarning.isConstantGrant("{'solr:read'}.?[#this != null]"));
    assertFalse(ScopeBridgeWarning.isConstantGrant("[scope]"));
  }
}
