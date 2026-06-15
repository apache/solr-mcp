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
/**
 * Root package for the Solr MCP Server.
 *
 * <p>
 * Houses the Spring Boot entrypoint and the MCP tool, resource, prompt, and
 * completion handlers that expose Apache Solr to AI clients over the Model
 * Context Protocol. Subpackages group the implementations by concern:
 * {@code collection}, {@code indexing}, {@code schema}, {@code search}, and
 * {@code config}.
 */
@NullMarked
package org.apache.solr.mcp.server;

import org.jspecify.annotations.NullMarked;
