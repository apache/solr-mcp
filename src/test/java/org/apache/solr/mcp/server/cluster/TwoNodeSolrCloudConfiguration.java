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
package org.apache.solr.mcp.server.cluster;

import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A two-node SolrCloud sharing one external ZooKeeper, with {@code solr.url} pointing at {@code
 * solr1}.
 *
 * <p>Each node is a {@link GenericContainer} rather than a {@code SolrContainer}: {@code
 * SolrContainer} starts Solr with {@code -h localhost} and embedded ZooKeeper, so a second node
 * could neither join it nor reach it.
 */
@TestConfiguration(proxyBeanMethods = false)
class TwoNodeSolrCloudConfiguration {

  static final String NODE1 = "solr1:8983_solr";

  static final String NODE2 = "solr2:8983_solr";

  private static final int SOLR_PORT = 8983;

  private static final String ZOOKEEPER_IMAGE_PROPERTY = "zookeeper.test.image";

  @Bean(destroyMethod = "close")
  Network solrCloudNetwork() {
    return Network.newNetwork();
  }

  @Bean
  GenericContainer<?> zookeeper(Network solrCloudNetwork) {
    // Pinned in gradle/libs.versions.toml as test-image-zookeeper, matching
    // compose.yaml; -Dzookeeper.test.image overrides it for a single run.
    return new GenericContainer<>(
            DockerImageName.parse(System.getProperty(ZOOKEEPER_IMAGE_PROPERTY, "zookeeper:3.9")))
        .withNetwork(solrCloudNetwork)
        .withNetworkAliases("zoo")
        .withExposedPorts(2181)
        .waitingFor(Wait.forListeningPort());
  }

  @Bean
  GenericContainer<?> solr1(Network solrCloudNetwork, GenericContainer<?> zookeeper) {
    return solrNode("solr1", solrCloudNetwork, zookeeper);
  }

  @Bean
  GenericContainer<?> solr2(Network solrCloudNetwork, GenericContainer<?> zookeeper) {
    return solrNode("solr2", solrCloudNetwork, zookeeper);
  }

  @Bean
  DynamicPropertyRegistrar twoNodePropertiesRegistrar(
      GenericContainer<?> solr1, GenericContainer<?> solr2) {
    // solr2 is a parameter so that it is started before any test runs.
    return registry ->
        registry.add(
            "solr.url",
            () -> "http://" + solr1.getHost() + ":" + solr1.getMappedPort(SOLR_PORT) + "/solr/");
  }

  private static GenericContainer<?> solrNode(
      String host, Network network, GenericContainer<?> zookeeper) {
    // The node advertises itself under its network alias: Solr 9 reads
    // SOLR_HOST and Solr 10 reads SOLR_HOST_ADVERTISE. The health handler
    // answers 200 only once the node is in live_nodes, so a started container
    // can take replicas straight away.
    return new GenericContainer<>(DockerImageName.parse(TestcontainersConfiguration.solrImage()))
        .withNetwork(network)
        .withNetworkAliases(host)
        .withEnv("ZK_HOST", "zoo:2181")
        .withEnv("SOLR_HOST", host)
        .withEnv("SOLR_HOST_ADVERTISE", host)
        .withExposedPorts(SOLR_PORT)
        .dependsOn(zookeeper)
        .waitingFor(Wait.forHttp("/solr/admin/info/health").forStatusCode(200));
  }
}
