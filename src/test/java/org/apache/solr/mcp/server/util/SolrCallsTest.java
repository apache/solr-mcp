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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SolrCalls}, with each failure built by SolrJ 10's own constructors so the
 * messages are the ones {@code HttpJdkSolrClient} really produces.
 */
class SolrCallsTest {

  private static final String OPERATION = "Doing the thing failed";

  private static final String NOT_FOUND = "collection 'books' was not found";

  /** The request URL SolrJ quotes as the remote host; none of it may reach the client. */
  private static final String HOST = "http://solr-node-1.internal:8983/solr/books";

  private static final String OTHER_NODE =
      "http://solr-node-2.internal:8983/solr/books_shard1_replica_n1";

  private static final String HOST_NAME = "solr-node";

  private static final String UNDEFINED_FIELD = "undefined field bogus";

  /** Throws {@code failure} from a Solr call and returns what the client would be told. */
  private static RuntimeException translated(Exception failure) {
    return translated(failure, SolrCalls.ReasonHint.NONE);
  }

  private static RuntimeException translated(Exception failure, SolrCalls.ReasonHint hint) {
    final RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                SolrCalls.call(
                    OPERATION,
                    NOT_FOUND,
                    hint,
                    () -> {
                      throwAs(failure);
                      return "never returned";
                    }));
    assertThat(thrown.getCause())
        .as("Spring AI appends the root cause's message, which quotes the Solr URL")
        .isNull();
    assertThat(thrown.getMessage())
        .startsWith(OPERATION + ": ")
        .doesNotContain(SolrCalls.REMOTE_ERROR_PREFIX)
        .doesNotContain("http://")
        .doesNotContain("https://")
        .doesNotContain(HOST_NAME);
    return thrown;
  }

  /** Throws one of the exceptions a {@link SolrCalls.SolrCall} may throw. */
  private static void throwAs(Exception failure) throws SolrServerException, IOException {
    switch (failure) {
      case SolrServerException solrServerException -> throw solrServerException;
      case IOException ioException -> throw ioException;
      case RuntimeException runtimeException -> throw runtimeException;
      default -> throw new AssertionError("not a Solr call failure: " + failure);
    }
  }

  @Test
  void returnsTheResultOfACallThatSucceeds() {
    assertThat(SolrCalls.call(OPERATION, NOT_FOUND, () -> "result")).isEqualTo("result");
  }

  @Test
  void leavesAFailureThatIsNotSolrsUnchanged() {
    final IllegalArgumentException ownFailure = new IllegalArgumentException("blank name");
    assertThatThrownBy(
            () ->
                SolrCalls.call(
                    OPERATION,
                    NOT_FOUND,
                    () -> {
                      throw ownFailure;
                    }))
        .isSameAs(ownFailure);
  }

  @Test
  void notFoundReportsTheCallersText() {
    // HttpSolrClientBase.checkContentType: Solr's 404 is an HTML page, not the JSON SolrJ expects.
    final RemoteSolrException notFound =
        new RemoteSolrException(
            HOST,
            404,
            "Expected mime type in [application/json] but got text/html. <html><body>"
                + HOST
                + "</body></html>",
            null);

    assertThat(translated(notFound))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OPERATION + ": " + NOT_FOUND);
  }

  @Test
  void unauthorizedAndForbiddenSayAccessWasDenied() {
    for (int statusCode : List.of(401, 403)) {
      final RemoteSolrException denied =
          new RemoteSolrException(HOST, statusCode, "non ok status: " + statusCode, null);

      assertThat(translated(denied))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(OPERATION + ": " + SolrCalls.ACCESS_DENIED);
    }
  }

  @Test
  void aRefusalFromTheMessageConstructorCarriesSolrsReasonAndTheHint() {
    final List<String> seen = new ArrayList<>();
    final SolrCalls.ReasonHint hint =
        (statusCode, reason) -> {
          seen.add(statusCode + " " + reason);
          return ". Hint: call list-fields.";
        };

    final RuntimeException thrown =
        translated(new RemoteSolrException(HOST, 400, UNDEFINED_FIELD, null), hint);

    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            OPERATION
                + ": "
                + SolrCalls.SOLR_REASON_LEAD
                + UNDEFINED_FIELD
                + ". Hint: call list-fields.");
    assertThat(seen).containsExactly("400 " + UNDEFINED_FIELD);
  }

  @Test
  void aRefusalFromSolrsErrorObjectCarriesSolrsReason() {
    // HttpSolrClientBase: a non-200 response whose body has an "error" object.
    final RemoteSolrException refused =
        new RemoteSolrException(HOST, 400, Map.of("msg", UNDEFINED_FIELD, "code", 400));

    assertThat(translated(refused))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OPERATION + ": " + SolrCalls.SOLR_REASON_LEAD + UNDEFINED_FIELD);
  }

  @Test
  void anErrorObjectWithAnErrorClassKeepsTheClassAsSolrWroteIt() {
    final RemoteSolrException refused =
        new RemoteSolrException(
            HOST,
            400,
            Map.of("errorClass", "org.apache.solr.common.SolrException", "msg", UNDEFINED_FIELD));

    assertThat(translated(refused).getMessage())
        .endsWith(
            SolrCalls.SOLR_REASON_LEAD
                + "org.apache.solr.common.SolrException: "
                + UNDEFINED_FIELD);
  }

  @Test
  void anErrorObjectWithoutAMessageSaysSolrGaveNoReason() {
    // RemoteSolrException keeps no suffix, so its message is "Error from server at <host>" alone.
    final RemoteSolrException refused = new RemoteSolrException(HOST, 400, Map.of("code", 400));

    assertThat(translated(refused)).hasMessage(OPERATION + ": " + SolrCalls.NO_REASON);
  }

  @Test
  void aServerErrorDropsANestedNodesHostAndAsksForTheOperator() {
    // A node forwarding the request quotes the other node's RemoteSolrException in its reason.
    final RemoteSolrException failed =
        new RemoteSolrException(
            HOST,
            500,
            "org.apache.solr.client.solrj.RemoteSolrException: Error from server at "
                + OTHER_NODE
                + ": Server error writing document",
            null);

    assertThat(translated(failed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            OPERATION
                + ": "
                + SolrCalls.SOLR_REASON_LEAD
                + "org.apache.solr.client.solrj.RemoteSolrException: Server error writing document"
                + SolrCalls.SERVER_ERROR_HINT);
  }

  @Test
  void aNestedRemoteErrorClassInSolrsErrorObjectLosesItsHost() {
    // RemoteSolrException.getRemoteErrorMessageSuffix special-cases a remote RemoteSolrException.
    final RemoteSolrException failed =
        new RemoteSolrException(
            HOST,
            500,
            Map.of(
                "errorClass",
                RemoteSolrException.class.getName(),
                "msg",
                "Error from server at " + OTHER_NODE + ": Server error writing document"));

    assertThat(translated(failed).getMessage())
        .contains(RemoteSolrException.class.getName() + ": Server error writing document");
  }

  @Test
  void anyOtherUrlInSolrsReasonIsReplaced() {
    final RemoteSolrException refused =
        new RemoteSolrException(
            HOST, 400, "Could not fetch https://configs.internal/managed-schema.xml now", null);

    assertThat(translated(refused).getMessage())
        .endsWith(SolrCalls.SOLR_REASON_LEAD + "Could not fetch <url> now");
  }

  @Test
  void aTimeoutSaysSolrDidNotAnswerInTime() {
    // HttpJdkSolrClient.requestWithBaseUrl wraps the JDK client's HttpTimeoutException.
    for (HttpTimeoutException timeout :
        List.of(
            new HttpTimeoutException("request timed out"),
            new HttpConnectTimeoutException("HTTP connect timed out"))) {
      final SolrServerException timedOut =
          new SolrServerException(
              "Timeout occurred while waiting response from server at: " + HOST + "/select",
              timeout);

      assertThat(translated(timedOut))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(OPERATION + ": " + SolrCalls.TIMED_OUT);
    }
  }

  @Test
  void anyOtherConnectionFailureSaysSolrCouldNotBeReached() {
    for (Exception unreachable :
        List.of(
            // HttpJdkSolrClient lets the JDK client's IOException through unwrapped.
            new ConnectException("Connection refused"),
            new IOException("Connection reset talking to " + HOST),
            // ...and wraps a RuntimeException, such as an invalid URI, in a SolrServerException.
            new SolrServerException(new IllegalArgumentException("Illegal character in " + HOST)),
            new SolrServerException("Server at " + HOST + " sent back a redirect (302)."))) {

      assertThat(translated(unreachable))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(OPERATION + ": " + SolrCalls.UNREACHABLE);
    }
  }

  @Test
  void collectionNotFoundNamesTheCollectionAndListCollections() {
    assertThat(SolrCalls.collectionNotFound("books"))
        .isEqualTo("collection 'books' was not found" + SolrCalls.LIST_COLLECTIONS_HINT);
  }
}
