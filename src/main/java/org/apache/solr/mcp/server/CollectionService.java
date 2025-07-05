package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CollectionService {

    private final SolrClient solrClient;
    private final SolrConfigurationProperties solrConfigurationProperties;

    public CollectionService(SolrClient solrClient,
                             SolrConfigurationProperties solrConfigurationProperties) {
        this.solrClient = solrClient;
        this.solrConfigurationProperties = solrConfigurationProperties;
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

}
