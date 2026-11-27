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

/**
 * The {@code collection} argument that the collection-scoped prompts of several services share.
 *
 * <p>{@code CollectionService} completes it with one handler for every such prompt and for the
 * {@code {collection}} variable of the resource templates, matching the argument by this name, so
 * every declaration references the same constant.
 */
public final class PromptArguments {

  /** The name of the {@code collection} argument, and of the resource templates' variable. */
  public static final String COLLECTION = "collection";

  /** The description of {@link #COLLECTION} on every prompt that takes it. */
  public static final String COLLECTION_DESCRIPTION = "Target Solr collection name";

  private PromptArguments() {
    // Constants holder - prevent instantiation
  }
}
