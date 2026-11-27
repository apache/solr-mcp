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
package org.apache.solr.mcp.server.alias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link AliasService} using Testcontainers with a real Solr instance.
 *
 * <p>Tests create, list, update, and delete alias operations against a live Solr cluster, and the
 * {@code alias-management} spec's scenarios: list-valued targets, properties kept across a
 * re-point, and failures for an unknown alias or an empty target list.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AliasServiceIntegrationTest {

  private static final String TEST_COLLECTION = "alias_test_collection";
  private static final String TEST_COLLECTION_2 = "alias_test_collection_2";
  private static final String TEST_ALIAS = "alias_test_alias";
  private static final String TEST_ALIAS_2 = "alias_test_alias_2";
  private static final String TEST_ALIAS_3 = "alias_test_alias_3";

  @Autowired private AliasService aliasService;

  @Autowired private CollectionService collectionService;

  @Autowired private SolrClient solrClient;

  @BeforeAll
  void setupCollection() {
    collectionService.createCollection(TEST_COLLECTION, null, null, null);
    collectionService.createCollection(TEST_COLLECTION_2, null, null, null);
  }

  private Optional<Alias> alias(String name) {
    return aliasService.listAliases().stream()
        .filter(alias -> alias.name().equals(name))
        .findFirst();
  }

  @Test
  @Order(1)
  void listAliases_initiallyLacksTestAlias() {
    assertThat(alias(TEST_ALIAS)).isEmpty();
  }

  @Test
  @Order(2)
  void createAlias_createsNewAlias() {
    Alias result = aliasService.createAlias(TEST_ALIAS, List.of(TEST_COLLECTION));

    assertThat(result).isEqualTo(new Alias(TEST_ALIAS, List.of(TEST_COLLECTION), Map.of()));
    assertThat(alias(TEST_ALIAS)).contains(result);
  }

  @Test
  @Order(3)
  void createAlias_withTwoTargets_listsBothInOrder() {
    Alias result =
        aliasService.createAlias(TEST_ALIAS_3, List.of(TEST_COLLECTION, TEST_COLLECTION_2));

    assertThat(result.collections()).containsExactly(TEST_COLLECTION, TEST_COLLECTION_2);
    assertThat(alias(TEST_ALIAS_3).map(Alias::collections))
        .contains(List.of(TEST_COLLECTION, TEST_COLLECTION_2));
    aliasService.deleteAlias(TEST_ALIAS_3);
  }

  @Test
  @Order(4)
  void createAlias_repointingKeepsProperties() throws Exception {
    CollectionAdminRequest.setAliasProperty(TEST_ALIAS)
        .addProperty("owner", "search-team")
        .process(solrClient);

    Alias result = aliasService.createAlias(TEST_ALIAS, List.of(TEST_COLLECTION_2));

    assertThat(result)
        .isEqualTo(
            new Alias(TEST_ALIAS, List.of(TEST_COLLECTION_2), Map.of("owner", "search-team")));
  }

  @Test
  @Order(5)
  void createAlias_withNoTargets_failsAndChangesNothing() {
    assertThatThrownBy(() -> aliasService.createAlias(TEST_ALIAS, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(alias(TEST_ALIAS).map(Alias::collections)).contains(List.of(TEST_COLLECTION_2));
  }

  @Test
  @Order(6)
  void listAliases_isOrderedByName() {
    aliasService.createAlias(TEST_ALIAS_2, List.of(TEST_COLLECTION));

    List<String> names = aliasService.listAliases().stream().map(Alias::name).toList();

    assertThat(names).isSorted().contains(TEST_ALIAS, TEST_ALIAS_2);
  }

  @Test
  @Order(7)
  void deleteAlias_removesAliasAndReturnsItsName() {
    assertThat(aliasService.deleteAlias(TEST_ALIAS)).isEqualTo(new AliasDeletion(TEST_ALIAS));

    assertThat(alias(TEST_ALIAS)).isEmpty();
    assertThat(alias(TEST_ALIAS_2)).isPresent();
  }

  @Test
  @Order(8)
  void deleteAlias_unknownAliasFails() {
    assertThatThrownBy(() -> aliasService.deleteAlias("alias_test_nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  @Order(9)
  void deleteAlias_cleanupSecondAlias() {
    aliasService.deleteAlias(TEST_ALIAS_2);

    assertThat(alias(TEST_ALIAS_2)).isEmpty();
  }
}
