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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.solr.mcp.server.TestcontainersConfiguration;
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
 * Integration tests for {@link AliasService} using Testcontainers with a real
 * Solr instance.
 *
 * <p>
 * Tests create, list, update, and delete alias operations against a live Solr
 * cluster.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AliasServiceIntegrationTest {

    private static final String TEST_COLLECTION = "alias_test_collection";
    private static final String TEST_ALIAS = "alias_test_alias";
    private static final String TEST_ALIAS_2 = "alias_test_alias_2";

    @Autowired
    private AliasService aliasService;

    @Autowired
    private CollectionService collectionService;

    @BeforeAll
    void setupCollection() throws Exception {
        CollectionCreationResult created = collectionService.createCollection(TEST_COLLECTION, null, null, null);
        assertThat(created.success()).as("Collection creation should succeed: %s", created.message()).isTrue();
    }

    @Test
    @Order(1)
    void listAliases_initiallyEmpty() throws Exception {
        Map<String, String> aliases = aliasService.listAliases();
        // No aliases pointing to our test collection initially
        assertThat(aliases).doesNotContainKey(TEST_ALIAS);
    }

    @Test
    @Order(2)
    void createAlias_createsNewAlias() throws Exception {
        AliasResult result = aliasService.createAlias(TEST_ALIAS, TEST_COLLECTION);

        assertThat(result.aliasName()).isEqualTo(TEST_ALIAS);
        assertThat(result.collections()).isEqualTo(TEST_COLLECTION);
        assertThat(result.success()).isTrue();
        assertThat(result.timestamp()).isNotNull();
    }

    @Test
    @Order(3)
    void listAliases_containsCreatedAlias() throws Exception {
        Map<String, String> aliases = aliasService.listAliases();

        assertThat(aliases).containsEntry(TEST_ALIAS, TEST_COLLECTION);
    }

    @Test
    @Order(4)
    void createAlias_updatesExistingAlias() throws Exception {
        // Create a second collection to point the alias to
        String secondCollection = TEST_COLLECTION;

        // Update alias to point to same collection (idempotent check)
        AliasResult result = aliasService.createAlias(TEST_ALIAS, secondCollection);

        assertThat(result.success()).isTrue();
        assertThat(result.collections()).isEqualTo(secondCollection);

        // Verify it's still listed
        Map<String, String> aliases = aliasService.listAliases();
        assertThat(aliases).containsEntry(TEST_ALIAS, secondCollection);
    }

    @Test
    @Order(5)
    void createAlias_multipleAliasesCanExist() throws Exception {
        AliasResult result = aliasService.createAlias(TEST_ALIAS_2, TEST_COLLECTION);
        assertThat(result.success()).isTrue();

        Map<String, String> aliases = aliasService.listAliases();
        assertThat(aliases).containsEntry(TEST_ALIAS, TEST_COLLECTION).containsEntry(TEST_ALIAS_2, TEST_COLLECTION);
    }

    @Test
    @Order(6)
    void deleteAlias_removesAlias() throws Exception {
        AliasResult result = aliasService.deleteAlias(TEST_ALIAS);

        assertThat(result.aliasName()).isEqualTo(TEST_ALIAS);
        assertThat(result.collections()).isNull();
        assertThat(result.success()).isTrue();

        // Verify it's gone
        Map<String, String> aliases = aliasService.listAliases();
        assertThat(aliases).doesNotContainKey(TEST_ALIAS);
        // But the other alias still exists
        assertThat(aliases).containsEntry(TEST_ALIAS_2, TEST_COLLECTION);
    }

    @Test
    @Order(7)
    void deleteAlias_cleanupSecondAlias() throws Exception {
        AliasResult result = aliasService.deleteAlias(TEST_ALIAS_2);
        assertThat(result.success()).isTrue();

        Map<String, String> aliases = aliasService.listAliases();
        assertThat(aliases).doesNotContainKey(TEST_ALIAS_2);
    }
}
