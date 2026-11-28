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

import static org.apache.solr.mcp.server.McpResultAssertions.resourceJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.mcp.server.SolrContainer;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.alias.AliasService;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.apache.solr.mcp.server.util.SolrCalls;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.json.JsonContent;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for {@link ClusterService} against the shared single-node SolrCloud container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterServiceIntegrationTest {

  private static final String TEST_COLLECTION = "cluster_test_collection";

  private static final String TEST_ALIAS = "cluster_test_alias";

  /**
   * A copy of the image's {@code _default}, uploaded so that a collection can share it by name.
   * ConfigSet CREATE cannot make it: Solr refuses to copy the trusted {@code _default} for an
   * unauthenticated request, while an upload is accepted and marked untrusted.
   */
  private static final String TEST_CONFIGSET = "cluster_test_conf";

  private static final String SHARED_COLLECTION = "cluster_test_shared";

  /** Created through {@code create-collection} with no configSet, so it gets a private copy. */
  private static final String PRIVATE_COLLECTION = "cluster_test_private";

  private static final String MISSING_COLLECTION = "cluster_test_no_such_collection";

  private static final String AUTOCREATED_SUFFIX = ".AUTOCREATED";

  private static final String CLUSTER_STATUS_URI = "solr://cluster/status";

  /** Where the Solr image keeps {@code _default}, the same path from 9.4 to 10. */
  private static final String IMAGE_DEFAULT_CONF =
      "/opt/solr/server/solr/configsets/_default/conf/";

  private static final String TEST_COLLECTION_PATH =
      "$.collections[?(@.name == '" + TEST_COLLECTION + "')]";

  private static final String REPLICAS = TEST_COLLECTION_PATH + ".shards[*].replicas[*]";

  @Autowired private ClusterService clusterService;

  @Autowired private CollectionService collectionService;

  @Autowired private AliasService aliasService;

  @Autowired private SolrClient solrClient;

  @Autowired private SolrContainer solrContainer;

  @Autowired private JsonMapper jsonMapper;

  @BeforeAll
  void setupCollections() throws Exception {
    CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1).process(solrClient);
    aliasService.createAlias(TEST_ALIAS, List.of(TEST_COLLECTION));

    new ConfigSetAdminRequest.Upload()
        .setConfigSetName(TEST_CONFIGSET)
        .setUploadStream(
            new ContentStreamBase.ByteArrayStream(
                zipImageDefaultConfigSet(), TEST_CONFIGSET + ".zip", "application/zip"))
        .process(solrClient);
    CollectionAdminRequest.createCollection(SHARED_COLLECTION, TEST_CONFIGSET, 1, 1)
        .process(solrClient);
    collectionService.createCollection(PRIVATE_COLLECTION, null, null, null);
  }

  /**
   * Every integration test class shares one Solr container. Solr deletes a {@code .AUTOCREATED}
   * copy with its collection, but not a configset created on its own.
   */
  @AfterAll
  void deleteConfigSetCollections() throws Exception {
    CollectionAdminRequest.deleteCollection(PRIVATE_COLLECTION).process(solrClient);
    CollectionAdminRequest.deleteCollection(SHARED_COLLECTION).process(solrClient);
    new ConfigSetAdminRequest.Delete().setConfigSetName(TEST_CONFIGSET).process(solrClient);
  }

  @Test
  void listConfigSetsReportsNamedAndPrivateConfigSetsSorted() {
    List<String> names = clusterService.listConfigSets().stream().map(ConfigSet::name).toList();

    assertThat(names)
        .contains("_default", TEST_CONFIGSET, PRIVATE_COLLECTION + AUTOCREATED_SUFFIX)
        .isSorted();
  }

  @Test
  void configNameIsTheConfigSetTheCollectionWasCreatedOn() {
    assertThat(configNameOf(TEST_COLLECTION)).isEqualTo("_default");
    assertThat(configNameOf(SHARED_COLLECTION)).isEqualTo(TEST_CONFIGSET);
    assertThat(configNameOf(PRIVATE_COLLECTION)).isEqualTo(PRIVATE_COLLECTION + AUTOCREATED_SUFFIX);
  }

  @Test
  void newCollectionHasOneActiveLeaderOnTheLiveNode() {
    ClusterStatus status = clusterService.getClusterStatus(TEST_COLLECTION);

    assertThat(status.liveNodes()).hasSize(1);
    assertThat(status.collections())
        .extracting(ClusterStatus.Collection::name)
        .containsExactly(TEST_COLLECTION);
    ClusterStatus.Shard shard = status.collections().getFirst().shards().getFirst();
    assertThat(shard.name()).isEqualTo("shard1");
    assertThat(shard.state()).isEqualTo("active");
    assertThat(shard.range()).isNotBlank();
    assertThat(shard.replicas())
        .singleElement()
        .satisfies(
            replica -> {
              assertThat(replica.name()).startsWith("core_node");
              assertThat(replica.state()).isEqualTo("active");
              assertThat(replica.type()).isEqualTo("NRT");
              assertThat(replica.leader()).isTrue();
              assertThat(replica.nodeName()).isEqualTo(status.liveNodes().getFirst());
              assertThat(replica.core()).startsWith(TEST_COLLECTION);
            });
  }

  @Test
  void statusIsWrittenWithServerOwnedNamesOnly() throws Exception {
    JsonContent json =
        new JsonContent(
            jsonMapper.writeValueAsString(clusterService.getClusterStatus(TEST_COLLECTION)));

    // Solr sends "leader": "true", a string; the record writes it as a boolean
    assertThat(json).extractingPath(REPLICAS + ".leader").asArray().containsExactly(true);
    assertThat(json).extractingPath(REPLICAS + ".nodeName").asArray().hasSize(1);
    assertThat(json).extractingPath("$.liveNodes").asArray().hasSize(1);
    // A deep scan never throws PathNotFoundException, so doesNotHavePath would always fail here
    for (String dropped : List.of("live_nodes", "node_name", "base_url", "router", "other")) {
      assertThat(json).extractingPath("$.." + dropped).asArray().as(dropped).isEmpty();
    }
  }

  @Test
  void aliasReportsTheCollectionBehindIt() {
    assertThat(collectionNames(clusterService.getClusterStatus(TEST_ALIAS)))
        .containsExactly(TEST_COLLECTION);
  }

  @Test
  void noCollectionReportsEveryCollectionSorted() {
    assertThat(collectionNames(clusterService.getClusterStatus(null)))
        .contains(TEST_COLLECTION, SHARED_COLLECTION, PRIVATE_COLLECTION)
        .isSorted();
  }

  @Test
  void unknownCollectionCarriesSolrsReasonAndTheListCollectionsHint() {
    assertThatThrownBy(() -> clusterService.getClusterStatus(MISSING_COLLECTION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(ClusterService.CLUSTER_STATUS_FAILED)
        .hasMessageContaining(SolrCalls.SOLR_REASON_LEAD)
        .hasMessageContaining(MISSING_COLLECTION + " not found")
        .hasMessageEndingWith(SolrCalls.LIST_COLLECTIONS_HINT)
        .hasNoCause();
  }

  @Test
  void resourceReturnsTheSameStatusAsTheTool() throws Exception {
    String json =
        resourceJson(
            clusterService.getClusterStatusResource(new ReadResourceRequest(CLUSTER_STATUS_URI)),
            CLUSTER_STATUS_URI);

    assertThat(new JsonContent(json))
        .isStrictlyEqualTo(jsonMapper.writeValueAsString(clusterService.getClusterStatus(null)));
  }

  /**
   * The image's own {@code _default} conf directory as a zip, so it matches the Solr under test.
   */
  private byte[] zipImageDefaultConfigSet() throws Exception {
    String listing =
        solrContainer.execInContainer("find", IMAGE_DEFAULT_CONF, "-type", "f").getStdout();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String file : listing.strip().split("\n")) {
        zip.putNextEntry(new ZipEntry(file.substring(IMAGE_DEFAULT_CONF.length())));
        solrContainer.copyFileFromContainer(file, content -> content.transferTo(zip));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private @Nullable String configNameOf(String collection) {
    return clusterService.getClusterStatus(collection).collections().getFirst().configName();
  }

  private List<String> collectionNames(ClusterStatus status) {
    return status.collections().stream().map(ClusterStatus.Collection::name).toList();
  }
}
