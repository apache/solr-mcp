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
package org.apache.solr.mcp.server.collection;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

import org.jspecify.annotations.Nullable;

/**
 * Result record for alias management operations.
 *
 * <p>
 * Returned by {@link AliasService} methods to communicate the outcome of
 * create, update, and delete operations on Solr aliases.
 *
 * @param aliasName   the alias that was operated on
 * @param collections the target collection(s) (null for delete operations)
 * @param success     whether the operation completed successfully
 * @param message     human-readable description of the outcome
 * @param timestamp   when the operation was performed
 */
public record AliasResult(String aliasName, @Nullable String collections, boolean success, String message,
                          @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") Date timestamp) {
}
