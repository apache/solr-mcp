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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class ClusterServiceTest {

  private static final String SOLR_URL = "http://solr-internal.corp:8983/solr";

  @Mock private SolrClient solrClient;

  private ClusterService clusterService;

  @BeforeEach
  void setUp() {
    clusterService = new ClusterService(solrClient, JsonMapper.shared());
  }

  @Test
  void getClusterStatusResource_SolrFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(new SolrServerException("Server refused connection at: " + SOLR_URL));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> clusterService.getClusterStatusResource());

    assertEquals(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
    assertFalse(e.getMessage().contains(SOLR_URL));
  }

  @Test
  void getClusterStatusResource_IoFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(new IOException("Connection reset by " + SOLR_URL));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> clusterService.getClusterStatusResource());

    assertEquals(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
    assertFalse(e.getMessage().contains(SOLR_URL));
  }
}
