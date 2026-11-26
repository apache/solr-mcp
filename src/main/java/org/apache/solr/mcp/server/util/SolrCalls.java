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

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Runs a call to Solr from an MCP tool and turns its failures into errors the client can act on.
 *
 * <p>Spring AI 2.1 reports a {@link RuntimeException} thrown by an {@code @McpTool} method as an
 * {@code isError: true} result, but rethrows a checked exception, which the client receives as a
 * JSON-RPC protocol error with no text it can act on. Its documented remedy is to convert the
 * failure to a {@code RuntimeException} in the method body, so every tool's Solr calls go through
 * {@link #call} and no tool declares {@link SolrServerException} or {@link IOException}. This is
 * exception translation (Effective Java, 3rd ed., Item 73): the tool reports a failure in its own
 * terms rather than SolrJ's.
 *
 * <p>The translated exception never has a cause. Spring AI appends the root cause's message to the
 * error text, and SolrJ's messages quote the Solr URL. The original is logged at {@code warn}
 * instead, so the server log holds the full failure.
 *
 * <table>
 *   <caption>What each SolrJ failure becomes</caption>
 *   <tr><th>Failure</th><th>Error text after the operation</th><th>Thrown as</th></tr>
 *   <tr><td>{@link SolrException} 404</td><td>the caller's not-found text</td>
 *       <td>{@link IllegalArgumentException}</td></tr>
 *   <tr><td>{@link SolrException} 401 or 403</td><td>{@link #ACCESS_DENIED}</td>
 *       <td>{@link IllegalStateException}</td></tr>
 *   <tr><td>other {@link SolrException} 4xx</td><td>Solr's reason, or {@link #STATUS_ERROR}
 *       when there is none to pass on; then the caller's hint</td>
 *       <td>{@link IllegalArgumentException}</td></tr>
 *   <tr><td>other {@link SolrException}</td><td>Solr's reason, or {@link #STATUS_ERROR}; then
 *       {@link #SERVER_ERROR_HINT}</td><td>{@link IllegalStateException}</td></tr>
 *   <tr><td>{@link SolrServerException} or {@link IOException} caused by a timeout</td>
 *       <td>{@link #TIMED_OUT}</td><td>{@link IllegalStateException}</td></tr>
 *   <tr><td>any other {@link SolrServerException} or {@link IOException}</td>
 *       <td>{@link #UNREACHABLE}</td><td>{@link IllegalStateException}</td></tr>
 * </table>
 *
 * <p>Solr's reason is passed on only when SolrJ built the exception from the {@code error} object
 * of Solr's JSON response (see {@link #reason}). Every other message SolrJ writes, such as {@code
 * "Expected mime type ... but got text/html. "} followed by the body of an HTML error page, which
 * can name the request path, gets the fixed text for its status instead.
 *
 * <p>The two exception types are the standard ones (Effective Java, 3rd ed., Item 72): {@code
 * IllegalArgumentException} when Solr refused the request because of something in it, so the client
 * can correct its next call, and {@code IllegalStateException} when the failure lies with Solr or
 * the connection to it.
 */
public final class SolrCalls {

  private static final Logger logger = LoggerFactory.getLogger(SolrCalls.class);

  /**
   * Opens every message SolrJ 10's {@code RemoteSolrException} builds: both of its constructors
   * write {@code "Error from server at " + remoteHost}. Solr's reason can quote another node's such
   * message, when a node it forwarded the request to failed.
   */
  static final String REMOTE_ERROR_PREFIX = "Error from server at ";

  /**
   * Separates {@code remoteHost} from the text that follows it, and Solr's reason from the
   * remote-host part of the message. The host is a request URL, which cannot hold a literal space,
   * so the first occurrence after {@link #REMOTE_ERROR_PREFIX} is the end of the host.
   */
  private static final String REMOTE_HOST_SEPARATOR = ": ";

  /** Any URL left in Solr's reason, such as a node another node failed to reach. */
  private static final Pattern URL = Pattern.compile("https?://\\S+");

  /** Takes the place of a URL removed from Solr's reason. */
  private static final String URL_REPLACEMENT = "<url>";

  /** Separates the operation from what went wrong. */
  private static final String OPERATION_SEPARATOR = ": ";

  /** Leads Solr's own reason for a failed request. */
  public static final String SOLR_REASON_LEAD = "Solr returned an error: ";

  /**
   * Error text, formatted with the HTTP status, for a Solr error whose reason is not passed on: one
   * SolrJ wrote itself rather than read from Solr's {@code error} object, or one with no reason.
   */
  static final String STATUS_ERROR = "Solr returned an error (HTTP %d)";

  /** Error text for a Solr error with no HTTP status, which SolrJ reports as code 0. */
  static final String UNKNOWN_STATUS_ERROR = "Solr returned an error";

  /** Remediation hint naming the {@code list-collections} tool. */
  public static final String LIST_COLLECTIONS_HINT =
      ". Hint: call list-collections to see available collections.";

  /**
   * The not-found text for a tool that names no collection and calls one of Solr's admin APIs: a
   * 404 there means the configured URL does not lead to them.
   */
  public static final String ADMIN_API_NOT_FOUND =
      "Solr answered that its admin API was not found. Ask the operator to check that the"
          + " configured Solr URL points at the Solr root of a SolrCloud cluster.";

  /** Error text for a 401 or 403 from Solr. */
  static final String ACCESS_DENIED =
      "Solr denied access. Ask the operator to check the configured Solr credentials and the"
          + " account's permissions.";

  /** Error text for a request that timed out. */
  static final String TIMED_OUT =
      "Solr did not answer in time. Retry later; if it persists, ask the operator to check Solr's"
          + " load and health.";

  /** Error text for any other connection or I/O failure. */
  public static final String UNREACHABLE =
      "Solr could not be reached. Retry later; if it persists, ask the operator to check that"
          + " Solr is running and the configured Solr URL.";

  /** Follows Solr's reason for a failure that is not the request's fault. */
  static final String SERVER_ERROR_HINT =
      ". If it persists, ask the operator to check Solr's health and logs.";

  /** The lowest and highest HTTP status that blame the request rather than the server. */
  private static final int FIRST_CLIENT_ERROR = 400;

  private static final int LAST_CLIENT_ERROR = 499;

  /**
   * A call to Solr, which may fail with SolrJ's checked exceptions.
   *
   * @param <T> what the call returns
   */
  @FunctionalInterface
  public interface SolrCall<T> {

    /**
     * Makes the call.
     *
     * @return the call's result
     * @throws SolrServerException if SolrJ could not complete the request
     * @throws IOException if the request failed on the wire
     */
    T call() throws SolrServerException, IOException;
  }

  /** Chooses a hint to follow Solr's reason for a request Solr refused (a 4xx other than 404). */
  @FunctionalInterface
  public interface ReasonHint {

    /** Adds no hint. */
    ReasonHint NONE = (statusCode, reason) -> "";

    /**
     * The hint for one refusal.
     *
     * @param statusCode the HTTP status Solr answered with
     * @param reason Solr's reason, with SolrJ's wording and any URL removed
     * @return the text to append, starting with its own punctuation, or an empty string
     */
    String hintFor(int statusCode, String reason);
  }

  private SolrCalls() {
    // Utility class - prevent instantiation
  }

  /**
   * The not-found text for a tool that names a collection.
   *
   * @param collection the collection the tool was called on
   * @return text saying the collection was not found, with the {@code list-collections} hint
   */
  public static String collectionNotFound(String collection) {
    return "collection '" + collection + "' was not found" + LIST_COLLECTIONS_HINT;
  }

  /**
   * Runs {@code call}, translating its failures with no hint after Solr's reason.
   *
   * @param operation what failed, leading the error text, such as {@code "Creating the alias
   *     failed"}
   * @param notFound the error text for a 404
   * @param call the call to run
   * @param <T> what the call returns
   * @return the call's result
   * @throws IllegalArgumentException if Solr refused the request because of something in it
   * @throws IllegalStateException if Solr failed, denied access, timed out or could not be reached
   */
  public static <T> T call(String operation, String notFound, SolrCall<T> call) {
    return call(operation, notFound, ReasonHint.NONE, call);
  }

  /**
   * Runs {@code call}, translating its failures and following Solr's reason for a refused request
   * with {@code hint}.
   *
   * @param operation what failed, leading the error text
   * @param notFound the error text for a 404
   * @param hint chooses the text that follows Solr's reason for a 4xx other than 404
   * @param call the call to run
   * @param <T> what the call returns
   * @return the call's result
   * @throws IllegalArgumentException if Solr refused the request because of something in it
   * @throws IllegalStateException if Solr failed, denied access, timed out or could not be reached
   */
  public static <T> T call(String operation, String notFound, ReasonHint hint, SolrCall<T> call) {
    return call(operation, notFound, hint, Level.WARN, call);
  }

  /**
   * Runs {@code call}, translating its failures with no hint, and logging the original at {@code
   * logLevel} instead of {@code WARN}. A caller that runs often and expects Solr to be away at
   * times, such as a completion, logs at {@link Level#DEBUG} so an outage does not fill the log
   * with one stack trace per keystroke (Effective Java, 3rd ed., Item 77: do not ignore a failure,
   * but record it at the weight it deserves).
   *
   * @param operation what failed, leading the error text
   * @param notFound the error text for a 404
   * @param logLevel the level the original failure is logged at
   * @param call the call to run
   * @param <T> what the call returns
   * @return the call's result
   * @throws IllegalArgumentException if Solr refused the request because of something in it
   * @throws IllegalStateException if Solr failed, denied access, timed out or could not be reached
   */
  public static <T> T call(String operation, String notFound, Level logLevel, SolrCall<T> call) {
    return call(operation, notFound, ReasonHint.NONE, logLevel, call);
  }

  private static <T> T call(
      String operation, String notFound, ReasonHint hint, Level logLevel, SolrCall<T> call) {
    try {
      return call.call();
    } catch (SolrException | SolrServerException | IOException failure) {
      logger.atLevel(logLevel).setCause(failure).log(operation);
      throw translate(operation, notFound, hint, failure);
    }
  }

  /**
   * The exception, without a cause, that reports {@code failure} to the client.
   *
   * @param operation what failed, leading the error text
   * @param notFound the error text for a 404
   * @param hint chooses the text that follows Solr's reason for a 4xx other than 404
   * @param failure what SolrJ threw
   * @return the exception to throw in its place
   */
  static RuntimeException translate(
      String operation, String notFound, ReasonHint hint, Exception failure) {
    final String lead = operation + OPERATION_SEPARATOR;
    if (!(failure instanceof SolrException solrFailure)) {
      return new IllegalStateException(lead + (isTimeout(failure) ? TIMED_OUT : UNREACHABLE));
    }
    final int statusCode = solrFailure.code();
    if (statusCode == SolrException.ErrorCode.NOT_FOUND.code) {
      return new IllegalArgumentException(lead + notFound);
    }
    if (statusCode == SolrException.ErrorCode.UNAUTHORIZED.code
        || statusCode == SolrException.ErrorCode.FORBIDDEN.code) {
      return new IllegalStateException(lead + ACCESS_DENIED);
    }
    final String reason = reason(solrFailure);
    final String reasonText =
        reason.isEmpty() ? statusError(statusCode) : SOLR_REASON_LEAD + reason;
    if (statusCode >= FIRST_CLIENT_ERROR && statusCode <= LAST_CLIENT_ERROR) {
      return new IllegalArgumentException(lead + reasonText + hint.hintFor(statusCode, reason));
    }
    return new IllegalStateException(lead + reasonText + SERVER_ERROR_HINT);
  }

  /**
   * Solr's reason for {@code failure}, or an empty string when there is none to pass on.
   *
   * <p>Only a {@code RemoteSolrException} that SolrJ built from Solr's parsed {@code error} object
   * carries a reason Solr wrote. In SolrJ 10.0.0 only the constructors that take that object call
   * {@code setDetails}, so {@code getRemoteErrorObject()} is non-null for them and null for the
   * constructor that takes a message, which SolrJ uses for the text it writes itself: an HTML error
   * page body, {@code "non ok status: ..."}, or the HTTP reason phrase and request method. For the
   * former, {@code getMessage()} is {@code getResponseMessage()} ({@code "Error from server at " +
   * remoteHost}) followed by {@code ": "} and the error's class and message, so the reason is
   * exactly what follows the response message. Any other {@code SolrException}, such as one SolrJ
   * raises before sending a request, carries no reason Solr wrote.
   *
   * <p>A node that forwarded the request to another can quote that node's {@code
   * RemoteSolrException} text in its reason, so every {@code Error from server at <host>: } in the
   * reason is removed as well, and any other URL is replaced.
   *
   * @param failure the Solr failure
   * @return Solr's reason, stripped; empty when there is none to pass on
   */
  static String reason(SolrException failure) {
    if (!(failure instanceof RemoteSolrException remote) || remote.getRemoteErrorObject() == null) {
      return "";
    }
    final String message = Objects.requireNonNullElse(remote.getMessage(), "");
    final String responseMessage = Objects.requireNonNullElse(remote.getResponseMessage(), "");
    if (!message.startsWith(responseMessage)) {
      return "";
    }
    String suffix = message.substring(responseMessage.length());
    if (suffix.startsWith(REMOTE_HOST_SEPARATOR)) {
      suffix = suffix.substring(REMOTE_HOST_SEPARATOR.length());
    }
    return URL.matcher(withoutRemoteHosts(suffix)).replaceAll(URL_REPLACEMENT).strip();
  }

  /**
   * The fixed text for a Solr error whose reason is not passed on.
   *
   * @param statusCode the HTTP status, or 0 when SolrJ knows none
   * @return the text, naming the status when there is one
   */
  private static String statusError(int statusCode) {
    return statusCode > 0 ? STATUS_ERROR.formatted(statusCode) : UNKNOWN_STATUS_ERROR;
  }

  /**
   * {@code message} with every {@code Error from server at <host>: } removed. A prefix with no text
   * after its host, which {@code RemoteSolrException} writes when Solr's error has no message, is
   * removed to the end.
   */
  private static String withoutRemoteHosts(String message) {
    final StringBuilder reason = new StringBuilder(message.length());
    int from = 0;
    int prefix = message.indexOf(REMOTE_ERROR_PREFIX);
    while (prefix >= 0) {
      reason.append(message, from, prefix);
      final int separator =
          message.indexOf(REMOTE_HOST_SEPARATOR, prefix + REMOTE_ERROR_PREFIX.length());
      if (separator < 0) {
        return reason.toString();
      }
      from = separator + REMOTE_HOST_SEPARATOR.length();
      prefix = message.indexOf(REMOTE_ERROR_PREFIX, from);
    }
    return reason.append(message, from, message.length()).toString();
  }

  /**
   * Whether {@code failure} or one of its causes is a timeout. SolrJ 10's {@code HttpJdkSolrClient}
   * wraps the JDK client's {@link HttpTimeoutException} in a {@code SolrServerException}; {@link
   * SocketTimeoutException} is the {@code java.net} equivalent for socket-based clients.
   */
  private static boolean isTimeout(Throwable failure) {
    for (@Nullable Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
        return true;
      }
      if (current.getCause() == current) {
        return false;
      }
    }
    return false;
  }
}
