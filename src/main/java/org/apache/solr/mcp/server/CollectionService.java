package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    // 1. CHECK IF COLLECTION EXISTS
    public boolean collectionExists(String collectionName) {
        try {
            if (solrClient instanceof CloudSolrClient) {
                // For SolrCloud - check cluster state
                CloudSolrClient cloudClient = (CloudSolrClient) solrClient;
                ClusterState clusterState = cloudClient.getClusterState();
                DocCollection collection = clusterState.getCollection(collectionName);
                return collection != null;
            } else {
                // For standalone Solr - check if core exists using Core Admin API
                CoreAdminRequest coreAdminRequest = new CoreAdminRequest();
                coreAdminRequest.setAction(CoreAdminParams.CoreAdminAction.STATUS);

                // Don't set core name in the request, as this will return status for all cores
                CoreAdminResponse coreResponse = coreAdminRequest.process(solrClient);

                // Check if the specific collection/core exists in the response
                List<String> collections = listCollections();
                return collections.contains(collectionName);
            }
        } catch (Exception e) {
            // Log the exception for debugging
            return false;
        }
    }

    // 2. CREATE COLLECTION (SolrCloud) OR CORE (Standalone)
    public boolean createCollection(String collectionName, String configSet, int numShards, int replicationFactor) {
        try {
            if (solrClient instanceof CloudSolrClient) {
                // For SolrCloud - use Collections API
                CollectionAdminRequest.Create createRequest = CollectionAdminRequest.createCollection(
                        collectionName, configSet, numShards, replicationFactor);

                CollectionAdminResponse response = createRequest.process(solrClient);
                return response.isSuccess();
            } else {
                // For standalone Solr - use Core Admin API to create core
                CoreAdminRequest.Create createCoreRequest = new CoreAdminRequest.Create();
                createCoreRequest.setCoreName(collectionName);
                createCoreRequest.setConfigSet(configSet);

                CoreAdminResponse response = createCoreRequest.process(solrClient);
                return response.getStatus() == 0;
            }
        } catch (SolrServerException | IOException e) {
            return false;
        }
    }

    // 3. CREATE COLLECTION WITH AUTO-DETECTION
    @Tool(description = "Create a Solr collection if it does not already exist")
    public boolean createCollectionIfNotExists(String collectionName) {
        if (collectionExists(collectionName)) {
            return true;
        }

        // Try different configSets that might be available in the Solr container
        // The order is important - try the most commonly available configSets first
        String[] possibleConfigSets = {
            "_default",       // Common in newer Solr versions
            "data_driven_schema_configs", // Common in older Solr versions
            "basic_configs",  // Another common default
            "sample_techproducts_configs", // Sample configSet that might be available
            "gettingstarted", // Another sample configSet
            "books"           // The collection name from our test container
        };

        boolean created = false;
        for (String configSet : possibleConfigSets) {
            try {
                boolean success = createCollection(collectionName, configSet, 1, 1);
                if (success) {
                    created = true;
                    break;
                }
            } catch (Exception e) {
                // Continue to the next configSet
            }
        }

        if (!created) {
            // If we've tried all configSets and none worked, log the failure and return false
            return false;
        }

        // Wait a short time to ensure the collection is fully created and registered
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify the collection exists after creation
        boolean exists = collectionExists(collectionName);

        return exists;
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
