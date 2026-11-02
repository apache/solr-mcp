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
package org.apache.solr.mcp.server;

import java.util.Arrays;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A {@link SolrContainer} that starts embedded ZooKeeper with {@code -DzkRun=true}.
 *
 * <p>Testcontainers launches Solr with a bare {@code -DzkRun}. Solr 10.1 deprecated that property
 * in favour of {@code solr.zookeeper.server.enabled} and parses its value as a boolean, so the
 * empty value Testcontainers passes aborts startup with {@code invalid boolean value:} and the
 * container never becomes ready. An explicit {@code =true} is accepted by every supported Solr
 * version (9.4 through 10.1).
 */
public class EmbeddedZkSolrContainer extends SolrContainer {

  private static final String BARE_ZK_RUN = "-DzkRun";

  public EmbeddedZkSolrContainer(DockerImageName image) {
    super(image);
  }

  @Override
  protected void configure() {
    super.configure();
    setCommandParts(
        Arrays.stream(getCommandParts())
            .map(part -> BARE_ZK_RUN.equals(part) ? BARE_ZK_RUN + "=true" : part)
            .toArray(String[]::new));
  }
}
