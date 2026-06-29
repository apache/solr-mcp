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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

/**
 * Unit tests for {@link AliasService}.
 */
@DisabledInNativeImage
class AliasServiceTest {

    private SolrClient solrClient;
    private AliasService aliasService;

    @BeforeEach
    void setUp() {
        solrClient = mock(SolrClient.class);
        aliasService = new AliasService(solrClient);
    }

    @Nested
    @DisplayName("list-aliases")
    class ListAliases {

        @Test
        @DisplayName("returns aliases map when aliases exist")
        void returnsAliasesWhenPresent() throws Exception {
            // Given
            NamedList<Object> responseData = new NamedList<>();
            responseData.add("aliases", Map.of("ORDERS", "ORDERS_V2", "PRODUCTS", "PRODUCTS_V1"));
            when(solrClient.request(any(), nullable(String.class))).thenReturn(responseData);

            // When
            Map<String, String> result = aliasService.listAliases();

            // Then
            assertThat(result).containsEntry("ORDERS", "ORDERS_V2").containsEntry("PRODUCTS", "PRODUCTS_V1").hasSize(2);
        }

        @Test
        @DisplayName("returns empty map when no aliases exist")
        void returnsEmptyMapWhenNoAliases() throws Exception {
            // Given
            NamedList<Object> responseData = new NamedList<>();
            when(solrClient.request(any(), nullable(String.class))).thenReturn(responseData);

            // When
            Map<String, String> result = aliasService.listAliases();

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("create-alias")
    class CreateAlias {

        @Test
        @DisplayName("creates alias successfully")
        void createsAliasSuccessfully() throws Exception {
            // Given
            NamedList<Object> responseData = new NamedList<>();
            responseData.add("responseHeader", new NamedList<>(Map.of("status", 0)));
            when(solrClient.request(any(), nullable(String.class))).thenReturn(responseData);

            // When
            AliasResult result = aliasService.createAlias("ORDERS", "ORDERS_V2");

            // Then
            assertThat(result.aliasName()).isEqualTo("ORDERS");
            assertThat(result.collections()).isEqualTo("ORDERS_V2");
            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("successfully");
            assertThat(result.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("throws IllegalArgumentException when alias name is blank")
        void throwsWhenAliasNameBlank() {
            assertThatThrownBy(() -> aliasService.createAlias("", "ORDERS_V2"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Alias name must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when alias name is null")
        void throwsWhenAliasNameNull() {
            assertThatThrownBy(() -> aliasService.createAlias(null, "ORDERS_V2"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Alias name must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when collections is blank")
        void throwsWhenCollectionsBlank() {
            assertThatThrownBy(() -> aliasService.createAlias("ORDERS", ""))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Collections must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when collections is null")
        void throwsWhenCollectionsNull() {
            assertThatThrownBy(() -> aliasService.createAlias("ORDERS", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Collections must not be blank");
        }
    }

    @Nested
    @DisplayName("delete-alias")
    class DeleteAlias {

        @Test
        @DisplayName("deletes alias successfully")
        void deletesAliasSuccessfully() throws Exception {
            // Given
            NamedList<Object> responseData = new NamedList<>();
            responseData.add("responseHeader", new NamedList<>(Map.of("status", 0)));
            when(solrClient.request(any(), nullable(String.class))).thenReturn(responseData);

            // When
            AliasResult result = aliasService.deleteAlias("ORDERS");

            // Then
            assertThat(result.aliasName()).isEqualTo("ORDERS");
            assertThat(result.collections()).isNull();
            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("deleted");
            assertThat(result.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("throws IllegalArgumentException when alias name is blank")
        void throwsWhenAliasNameBlank() {
            assertThatThrownBy(() -> aliasService.deleteAlias("")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Alias name must not be blank");
        }

        @Test
        @DisplayName("throws IllegalArgumentException when alias name is null")
        void throwsWhenAliasNameNull() {
            assertThatThrownBy(() -> aliasService.deleteAlias(null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Alias name must not be blank");
        }
    }
}
