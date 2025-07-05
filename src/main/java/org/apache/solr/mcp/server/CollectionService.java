package org.apache.solr.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.response.*;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.lang.runtime.ObjectMethods;
import java.util.*;

@Service
public class CollectionService {

    private final SolrClient solrClient;
    private final SolrConfigurationProperties solrConfigurationProperties;
    private final ObjectMapper objectMapper;

    public CollectionService(SolrClient solrClient,
                             SolrConfigurationProperties solrConfigurationProperties,
                             ObjectMapper objectMapper) {
        this.solrClient = solrClient;
        this.solrConfigurationProperties = solrConfigurationProperties;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "List solr collections")
    public List<String> listCollections() {
        try {
            if (solrClient instanceof CloudSolrClient) {
                // For SolrCloud - use Collections API
                CollectionAdminRequest.List request = new CollectionAdminRequest.List();
                CollectionAdminResponse response = request.process(solrClient);

                @SuppressWarnings("unchecked")
                List<String> collections = (List<String>) response.getResponse().get("collections");
                return collections != null ? collections : new ArrayList<>();
            } else {
                // For standalone Solr - use Core Admin API
                CoreAdminRequest coreAdminRequest = new CoreAdminRequest();
                coreAdminRequest.setAction(CoreAdminParams.CoreAdminAction.STATUS);
                CoreAdminResponse coreResponse = coreAdminRequest.process(solrClient);

                List<String> cores = new ArrayList<>();
                NamedList<NamedList<Object>> coreStatus = coreResponse.getCoreStatus();
                for (int i = 0; i < coreStatus.size(); i++) {
                    cores.add(coreStatus.getName(i));
                }
                return cores;
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @Tool(description = "Get stats on a Solr collection")
    public Map<String, Object> getCollectionStats(String collection) throws Exception {
        LukeRequest lukeRequest = new LukeRequest();
        LukeResponse lukeResponse = lukeRequest.process(solrClient, collection);

        Map<String, Object> stats = new HashMap<>();
        stats.put("numDocs", lukeResponse.getNumDocs());

        return stats;
    }

    @Tool(description = "Check health of a Solr collection")
    public SolrHealthStatus checkHealth(String collection) {
        try {
            // Ping Solr
            SolrPingResponse pingResponse = solrClient.ping(collection);

            // Get basic stats
            QueryResponse statsResponse = solrClient.query(collection,
                    new SolrQuery("*:*").setRows(0));

            return SolrHealthStatus.builder()
                    .isHealthy(true)
                    .responseTime(pingResponse.getElapsedTime())
                    .totalDocuments(statsResponse.getResults().getNumFound())
                    .lastChecked(new Date())
                    .build();

        } catch (Exception e) {
            return SolrHealthStatus.builder()
                    .isHealthy(false)
                    .errorMessage(e.getMessage())
                    .lastChecked(new Date())
                    .build();
        }
    }

}
