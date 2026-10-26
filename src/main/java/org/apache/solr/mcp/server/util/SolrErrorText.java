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
package org.apache.solr.mcp.server.util;

import java.util.regex.Pattern;

/**
 * Removes Solr URLs from Solr and SolrJ failure text before it reaches an MCP client.
 *
 * <p>SolrJ names the server in its messages: {@code Error from server at <url>: <reason>} from
 * {@code RemoteSolrException}, and {@code Server refused connection at: <url>} from the Jetty
 * client. That URL is the operator's internal Solr address, and it carries the credentials verbatim
 * if they were written into {@code solr.url}. Solr's reason, such as {@code undefined field foo},
 * is what lets a model correct its next call, so it is kept.
 */
public final class SolrErrorText {

  /** Replaces each URL in the redacted text. */
  public static final String REDACTED_URL = "[redacted]";

  private static final Pattern REMOTE_ERROR_PREFIX =
      Pattern.compile("^Error from server at \\S+?: ");

  private static final Pattern URL = Pattern.compile("\\b[A-Za-z][A-Za-z0-9+.-]*://\\S+");

  private SolrErrorText() {}

  /**
   * Returns {@code message} without SolrJ's {@code Error from server at <url>: } prefix and with
   * every remaining URL replaced by {@link #REDACTED_URL}.
   *
   * @param message Solr or SolrJ failure text
   * @return the text with no URL in it
   */
  public static String redact(String message) {
    String reason = REMOTE_ERROR_PREFIX.matcher(message).replaceFirst("");
    return URL.matcher(reason).replaceAll(REDACTED_URL);
  }
}
