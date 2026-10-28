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

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Warns at startup when the scope compatibility bridge is active.
 *
 * <p>The bridge is {@code authorities-claim-expressions} set to a constant list such as {@code
 * {'solr:read','solr:write','solr:admin'}}. It ignores the token entirely and grants those scopes
 * to every valid token, so scoped authorization is effectively off. That is the intended upgrade
 * aid, but nothing else would reveal it is still on in production.
 */
@Profile("http")
@Component
@ConditionalOnProperty(name = "http.security.enabled", havingValue = "true", matchIfMissing = true)
class ScopeBridgeWarning {

  static final String EXPRESSIONS_PROPERTY =
      "spring.security.oauth2.resourceserver.jwt.authorities-claim-expressions";

  private static final Logger log = LoggerFactory.getLogger(ScopeBridgeWarning.class);

  /** A SpEL list literal of string literals only: reads nothing from the token. */
  private static final Pattern CONSTANT_LIST =
      Pattern.compile("\\s*\\{\\s*('[^']*'|\"[^\"]*\")(\\s*,\\s*('[^']*'|\"[^\"]*\"))*\\s*\\}\\s*");

  ScopeBridgeWarning(Environment environment) {
    List<String> expressions =
        Binder.get(environment)
            .bind(EXPRESSIONS_PROPERTY, Bindable.listOf(String.class))
            .orElse(List.of());
    expressions.stream()
        .filter(ScopeBridgeWarning::isConstantGrant)
        .forEach(
            expression ->
                log.warn(
                    "{} is the constant list {}: every valid token is granted these scopes, so "
                        + "solr:read/solr:write/solr:admin are NOT being enforced per token. This is "
                        + "the upgrade compatibility bridge; remove it once your IdP issues scopes.",
                    EXPRESSIONS_PROPERTY,
                    expression.strip()));
  }

  static boolean isConstantGrant(String expression) {
    return CONSTANT_LIST.matcher(expression).matches();
  }
}
