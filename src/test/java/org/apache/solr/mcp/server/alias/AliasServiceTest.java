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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;

/** Unit tests for {@link AliasService}. */
@DisabledInNativeImage
class AliasServiceTest {

  private SolrClient solrClient;
  private AliasService aliasService;

  @BeforeEach
  void setUp() {
    solrClient = mock(SolrClient.class);
    aliasService = new AliasService(solrClient);
  }

  /** A {@code LISTALIASES} response, in the shape SolrJ parses Solr's JSON into. */
  private static NamedList<Object> listAliasesResponse(
      Map<String, String> aliases, Map<String, Map<String, String>> properties) {
    NamedList<Object> response = new NamedList<>();
    response.add("aliases", aliases);
    response.add("properties", properties);
    return response;
  }

  @Nested
  @DisplayName("list-aliases")
  class ListAliases {

    @Test
    @DisplayName("splits targets into a list and attaches each alias's properties")
    void splitsTargetsAndAttachesProperties() throws Exception {
      when(solrClient.request(any(), nullable(String.class)))
          .thenReturn(
              listAliasesResponse(
                  Map.of("tv", "shows,shows_typed", "orders", "orders_v2"),
                  Map.of("tv", Map.of("owner", "search-team"))));

      AliasList result = aliasService.listAliases();

      assertThat(result.aliases())
          .containsExactly(
              new Alias("orders", List.of("orders_v2"), Map.of()),
              new Alias("tv", List.of("shows", "shows_typed"), Map.of("owner", "search-team")));
    }

    @Test
    @DisplayName("returns an empty list when no aliases exist")
    void returnsEmptyListWhenNoAliases() throws Exception {
      when(solrClient.request(any(), nullable(String.class))).thenReturn(new NamedList<>());

      assertThat(aliasService.listAliases().aliases()).isEmpty();
    }
  }

  @Nested
  @DisplayName("create-alias")
  class CreateAlias {

    @Test
    @DisplayName("returns the alias as Solr lists it after the call")
    void returnsAliasReadBack() throws Exception {
      when(solrClient.request(any(), nullable(String.class)))
          .thenReturn(new NamedList<>())
          .thenReturn(listAliasesResponse(Map.of("ORDERS", "ORDERS_V2"), Map.of()));

      Alias result = aliasService.createAlias("ORDERS", List.of("ORDERS_V2"));

      assertThat(result).isEqualTo(new Alias("ORDERS", List.of("ORDERS_V2"), Map.of()));
      verify(solrClient, times(2)).request(any(), nullable(String.class));
    }

    @Test
    @DisplayName("throws IllegalArgumentException when alias name is blank")
    void throwsWhenAliasNameBlank() {
      assertThatThrownBy(() -> aliasService.createAlias("", List.of("ORDERS_V2")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Alias name must not be blank");
    }

    @Test
    @DisplayName("throws IllegalArgumentException when no collections are given")
    void throwsWhenCollectionsEmpty() {
      assertThatThrownBy(() -> aliasService.createAlias("ORDERS", List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("Collections must name at least one collection");
    }

    @Test
    @DisplayName("throws IllegalArgumentException when a collection name is blank")
    void throwsWhenACollectionIsBlank() {
      assertThatThrownBy(() -> aliasService.createAlias("ORDERS", List.of("ORDERS_V2", " ")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("delete-alias")
  class DeleteAlias {

    @Test
    @DisplayName("returns the deleted alias's name")
    void deletesExistingAlias() throws Exception {
      when(solrClient.request(any(), nullable(String.class)))
          .thenReturn(listAliasesResponse(Map.of("ORDERS_TEST", "ORDERS_V1"), Map.of()))
          .thenReturn(new NamedList<>());

      assertThat(aliasService.deleteAlias("ORDERS_TEST"))
          .isEqualTo(new AliasDeletion("ORDERS_TEST"));
      verify(solrClient, times(2)).request(any(), nullable(String.class));
    }

    @Test
    @DisplayName("fails for an alias that does not exist, without calling DELETEALIAS")
    void failsForUnknownAlias() throws Exception {
      when(solrClient.request(any(), nullable(String.class)))
          .thenReturn(listAliasesResponse(Map.of(), Map.of()));

      assertThatThrownBy(() -> aliasService.deleteAlias("nope"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Alias 'nope' does not exist");
      verify(solrClient, times(1)).request(any(), nullable(String.class));
    }

    @Test
    @DisplayName("throws IllegalArgumentException when alias name is blank")
    void throwsWhenAliasNameBlank() {
      assertThatThrownBy(() -> aliasService.deleteAlias(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Alias name must not be blank");
    }
  }
}
