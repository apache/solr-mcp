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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.solr.client.solrj.RemoteSolrException;
import org.junit.jupiter.api.Test;

class SolrErrorTextTest {

  @Test
  void remoteErrorKeepsOnlySolrsReason() {
    RemoteSolrException e =
        new RemoteSolrException(
            "http://user:pw@solr-internal:8983/solr/books", 400, "undefined field foo", null);

    assertEquals("undefined field foo", SolrErrorText.redact(e.getMessage()));
  }

  @Test
  void urlElsewhereInTheTextIsReplaced() {
    assertEquals(
        "Server refused connection at: [redacted]",
        SolrErrorText.redact("Server refused connection at: http://solr-internal:8983/solr/"));
  }

  @Test
  void everyUrlIsReplaced() {
    assertEquals(
        "tried [redacted] then [redacted]",
        SolrErrorText.redact("tried https://a:8983/solr then http://b:8983/solr/x?q=1"));
  }

  @Test
  void textWithoutAUrlIsUnchanged() {
    assertEquals("undefined field: foo", SolrErrorText.redact("undefined field: foo"));
  }
}
