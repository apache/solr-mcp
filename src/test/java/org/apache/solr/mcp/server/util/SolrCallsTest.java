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
import org.apache.solr.common.SolrException;
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

  private static final String LIST_FIELDS_HINT = ". Hint: call list-fields.";

  /** What {@code HttpSolrClientBase.checkContentType} writes before a non-JSON body. */
  private static final String EXPECTED_JSON =
      "Expected mime type in [application/json, text/plain] but got text/html. ";

  /** Solr 9.9's body for a request to a collection that does not exist. */
  private static final String SOLR_NOT_FOUND_PAGE =
      "<p>Searching for Solr?<br/>You must type the correct path.<br/>Solr will respond.</p>";

  /** Jetty's error page for a 401 from Solr's BasicAuth plugin. */
  private static final String JETTY_401_PAGE =
      """
      <html>
      <head>
      <meta http-equiv="Content-Type" content="text/html;charset=utf-8"/>
      <title>Error 401 Bad credentials</title>
      </head>
      <body><h2>HTTP ERROR 401 Bad credentials</h2>
      <table>
      <tr><th>URI:</th><td>/solr/admin/collections</td></tr>
      <tr><th>STATUS:</th><td>401</td></tr>
      <tr><th>MESSAGE:</th><td>Bad credentials</td></tr>
      </table>
      </body>
      </html>
      """;

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
        .doesNotContain(HOST_NAME)
        .doesNotContain("/solr/")
        .doesNotContain("<html")
        .doesNotContain("<p>");
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
  void anHtmlNotFoundReportsTheCallersText() {
    // HttpSolrClientBase.checkContentType: Solr 9.9 answers a missing collection with this HTML
    // body, which SolrJ appends to its own text through the message constructor.
    final RemoteSolrException notFound =
        new RemoteSolrException(HOST, 404, EXPECTED_JSON + SOLR_NOT_FOUND_PAGE, null);

    assertThat(translated(notFound))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OPERATION + ": " + NOT_FOUND);
  }

  @Test
  void anHtmlUnauthorizedPageSaysAccessWasDeniedWithoutThePage() {
    // Jetty's error page for a 401, which names the request path.
    final RemoteSolrException unauthorized =
        new RemoteSolrException(HOST, 401, EXPECTED_JSON + JETTY_401_PAGE, null);

    assertThat(translated(unauthorized))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(OPERATION + ": " + SolrCalls.ACCESS_DENIED);
  }

  @Test
  void aForbiddenStatusWithoutABodySaysAccessWasDenied() {
    // HttpSolrClientBase: a non-OK status with no response parser or content type.
    final RemoteSolrException forbidden =
        new RemoteSolrException(HOST, 403, "non ok status: 403, message:Forbidden", null);

    assertThat(translated(forbidden))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(OPERATION + ": " + SolrCalls.ACCESS_DENIED);
  }

  @Test
  void aRefusalSolrJWroteItselfGetsTheFixedStatusTextAndTheHint() {
    final List<String> seen = new ArrayList<>();
    final SolrCalls.ReasonHint hint =
        (statusCode, reason) -> {
          seen.add(statusCode + "[" + reason + "]");
          return LIST_FIELDS_HINT;
        };
    for (String solrJText :
        List.of(
            EXPECTED_JSON + "<html><body><h2>HTTP ERROR 400</h2>URI: /solr/books/select</body>",
            // HttpSolrClientBase: a non-200 JSON response without an error object.
            "Bad Request\nrequest: GET")) {
      final RemoteSolrException refused = new RemoteSolrException(HOST, 400, solrJText, null);

      final RuntimeException thrown = translated(refused, hint);

      assertThat(thrown)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(OPERATION + ": " + SolrCalls.STATUS_ERROR.formatted(400) + LIST_FIELDS_HINT);
      assertThat(thrown.getMessage()).doesNotContain("<").doesNotContain("/solr/");
    }
    assertThat(seen).containsExactly("400[]", "400[]");
  }

  @Test
  void anHtmlServerErrorGetsTheFixedStatusTextWithoutThePage() {
    final RemoteSolrException failed =
        new RemoteSolrException(
            HOST, 503, EXPECTED_JSON + "<html><body>URI: /solr/books/update</body></html>", null);

    assertThat(translated(failed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            OPERATION + ": " + SolrCalls.STATUS_ERROR.formatted(503) + SolrCalls.SERVER_ERROR_HINT);
  }

  @Test
  void aSolrExceptionThatSolrDidNotSendGetsTheFixedStatusText() {
    final SolrException clientSide =
        new SolrException(SolrException.ErrorCode.BAD_REQUEST, UNDEFINED_FIELD + " " + HOST);

    assertThat(translated(clientSide))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(OPERATION + ": " + SolrCalls.STATUS_ERROR.formatted(400));
  }

  @Test
  void aRefusalFromSolrsErrorObjectCarriesSolrsReasonAndTheHint() {
    // HttpSolrClientBase: a non-200 response whose body has an "error" object.
    final List<String> seen = new ArrayList<>();
    final SolrCalls.ReasonHint hint =
        (statusCode, reason) -> {
          seen.add(statusCode + " " + reason);
          return LIST_FIELDS_HINT;
        };
    final RemoteSolrException refused =
        new RemoteSolrException(HOST, 400, Map.of("msg", UNDEFINED_FIELD, "code", 400));

    assertThat(translated(refused, hint))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            OPERATION + ": " + SolrCalls.SOLR_REASON_LEAD + UNDEFINED_FIELD + LIST_FIELDS_HINT);
    assertThat(seen).containsExactly("400 " + UNDEFINED_FIELD);
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
  void anErrorObjectWithoutAMessageGetsTheFixedStatusText() {
    // RemoteSolrException keeps no suffix, so its message is "Error from server at <host>" alone.
    final RemoteSolrException refused = new RemoteSolrException(HOST, 400, Map.of("code", 400));

    assertThat(translated(refused))
        .hasMessage(OPERATION + ": " + SolrCalls.STATUS_ERROR.formatted(400));
  }

  @Test
  void aServerErrorDropsANestedNodesHostAndAsksForTheOperator() {
    // A node forwarding the request quotes the other node's RemoteSolrException in its reason.
    final RemoteSolrException failed =
        new RemoteSolrException(
            HOST,
            500,
            Map.of(
                "msg",
                "org.apache.solr.client.solrj.RemoteSolrException: Error from server at "
                    + OTHER_NODE
                    + ": Server error writing document"));

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
            HOST,
            400,
            Map.of("msg", "Could not fetch https://configs.internal/managed-schema.xml now"));

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
