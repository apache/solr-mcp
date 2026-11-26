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
package org.apache.solr.mcp.server.indexing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What an indexing tool reports after Solr accepted and committed the input.
 *
 * <p>A failed call throws and never produces this result, so there is no {@code success} flag.
 *
 * <p>The counts and field names are present only for the formats the server parses itself (JSON and
 * Markdown). CSV and XML go to Solr unchanged, and Solr's update response carries no document
 * count, so for those formats the three properties are absent.
 *
 * @param collection the collection the documents were indexed into
 * @param format the input format of the call
 * @param documentsSubmitted the number of documents the input produced; absent for CSV and XML
 * @param documentsIndexed the number of documents Solr accepted; lower than {@code
 *     documentsSubmitted} when Solr rejected some documents individually; absent for CSV and XML
 * @param fieldNames the distinct field names of the submitted documents, sorted, as they were sent
 *     to Solr (nested JSON objects flattened with underscores). At most 50 names are listed, the
 *     first 50 in sort order, to keep the result small. Absent for CSV and XML
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexingResult(
    String collection,
    Format format,
    @Nullable Integer documentsSubmitted,
    @Nullable Integer documentsIndexed,
    @Nullable List<String> fieldNames) {

  /** Copies {@code fieldNames} so the result cannot change after it is built. */
  public IndexingResult {
    fieldNames = fieldNames == null ? null : List.copyOf(fieldNames);
  }

  /**
   * Builds the result for a format the server parsed into documents itself.
   *
   * @param collection the collection the documents were indexed into
   * @param format {@link Format#json} or {@link Format#markdown}
   * @param documentsSubmitted the number of documents the input produced
   * @param documentsIndexed the number of documents Solr accepted
   * @param fieldNames the field names to report
   * @return a result with counts and field names
   */
  static IndexingResult parsed(
      String collection,
      Format format,
      int documentsSubmitted,
      int documentsIndexed,
      List<String> fieldNames) {
    return new IndexingResult(collection, format, documentsSubmitted, documentsIndexed, fieldNames);
  }

  /**
   * Builds the result for a payload forwarded to Solr unchanged, which reports no document count.
   *
   * @param collection the collection the payload was indexed into
   * @param format {@link Format#csv} or {@link Format#xml}
   * @return a result without counts or field names
   */
  static IndexingResult forwarded(String collection, Format format) {
    return new IndexingResult(collection, format, null, null, null);
  }

  /**
   * The input format of an indexing call.
   *
   * <p>The constants are lowercase because their names are the JSON values. Jackson writes an enum
   * by its name, and Spring AI's output-schema generator lists the constants by their Java names,
   * so lowercase names keep the serialized value and the published schema the same without extra
   * annotations.
   */
  @SuppressWarnings("java:S115")
  public enum Format {
    /** A JSON array of objects, parsed by the server. */
    json,
    /** CSV text, forwarded to Solr's CSV update handler. */
    csv,
    /** A Solr update XML {@code <add>} block, forwarded to Solr's XML update handler. */
    xml,
    /** Markdown documents, parsed by the server. */
    markdown
  }
}
