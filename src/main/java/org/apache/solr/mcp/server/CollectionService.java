package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.LukeRequest;
import org.apache.solr.client.solrj.response.*;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import static org.apache.solr.mcp.server.Utils.*;

@Service
public class CollectionService {

    private static final Logger logger = Logger.getLogger(CollectionService.class.getName());
    
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

    @Tool(description = "Get stats/metrics on a Solr collection")
    public SolrMetrics getCollectionStats(String collection) throws Exception {
        // Extract actual collection name from shard name if needed
        String actualCollection = extractCollectionName(collection);
        
        // Validate collection exists
        if (!validateCollectionExists(actualCollection)) {
            logger.warning("Collection does not exist: " + actualCollection);
            throw new IllegalArgumentException("Collection not found: " + actualCollection);
        }
        
        logger.info("Collecting metrics for collection: " + actualCollection);

        // Index statistics using Luke
        LukeRequest lukeRequest = new LukeRequest();
        lukeRequest.setIncludeIndexFieldFlags(true);
        LukeResponse lukeResponse = lukeRequest.process(solrClient, actualCollection);

        // Query performance metrics
        QueryResponse statsResponse = solrClient.query(actualCollection,
                new SolrQuery("*:*").setRows(0));

        return SolrMetrics.builder()
                .indexStats(buildIndexStats(lukeResponse))
                .queryStats(buildQueryStats(statsResponse))
                .cacheStats(getCacheMetrics(actualCollection))
                .handlerStats(getHandlerMetrics(actualCollection))
                .timestamp(new Date())
                .build();
    }

    public IndexStats buildIndexStats(LukeResponse lukeResponse) {
        NamedList<Object> indexInfo = lukeResponse.getIndexInfo();

        // Extract index information using helper methods
        Integer segmentCount = getInteger(indexInfo, "segmentCount");

        return IndexStats.builder()
                .numDocs(lukeResponse.getNumDocs())
                .segmentCount(segmentCount)
                .build();
    }

    public QueryStats buildQueryStats(QueryResponse response) {

        return QueryStats.builder()
                .queryTime(response.getQTime())
                .totalResults(response.getResults().getNumFound())
                .start(response.getResults().getStart())
                .maxScore(response.getResults().getMaxScore())
                .build();
    }

    public CacheStats getCacheMetrics(String collection) throws Exception {
        logger.info("Collecting cache metrics for collection: " + collection);
        
        try {
            // Get MBeans for cache information
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("stats", "true");
            params.set("cat", "CACHE");
            params.set("wt", "json");

            // Extract actual collection name from shard name if needed
            String actualCollection = extractCollectionName(collection);
            
            // Validate collection exists first
            if (!validateCollectionExists(actualCollection)) {
                logger.warning("Collection does not exist for cache metrics: " + actualCollection);
                return null; // Return null instead of empty object
            }
            
            String path = actualCollection + "/admin/mbeans";

            GenericSolrRequest request = new GenericSolrRequest(
                    SolrRequest.METHOD.GET,
                    path,
                    params
            );

            NamedList<Object> response = solrClient.request(request);
            CacheStats stats = extractCacheStats(response);
            
            // Return null if all cache stats are empty/null
            if (isCacheStatsEmpty(stats)) {
                logger.info("Cache stats are empty, returning null");
                return null;
            }
            
            return stats;
        } catch (Exception e) {
            logger.warning("Failed to collect cache metrics: " + e.getMessage());
            return null; // Return null instead of empty object
        }
    }
    
    private boolean isCacheStatsEmpty(CacheStats stats) {
        return stats == null || 
               (stats.getQueryResultCache() == null && 
                stats.getDocumentCache() == null && 
                stats.getFilterCache() == null);
    }

    private CacheStats extractCacheStats(NamedList<Object> mbeans) {
        CacheStats.CacheStatsBuilder builder = CacheStats.builder();

        NamedList<Object> caches = (NamedList<Object>) mbeans.get("CACHE");

        if (caches != null) {
            // Query result cache
            NamedList<Object> queryResultCache = (NamedList<Object>) caches.get("queryResultCache");
            if (queryResultCache != null) {
                NamedList<Object> stats = (NamedList<Object>) queryResultCache.get("stats");
                builder.queryResultCache(CacheInfo.builder()
                        .lookups(getLong(stats, "lookups"))
                        .hits(getLong(stats, "hits"))
                        .hitratio(getFloat(stats, "hitratio"))
                        .inserts(getLong(stats, "inserts"))
                        .evictions(getLong(stats, "evictions"))
                        .size(getLong(stats, "size"))
                        .build());
            }

            // Document cache
            NamedList<Object> documentCache = (NamedList<Object>) caches.get("documentCache");
            if (documentCache != null) {
                NamedList<Object> stats = (NamedList<Object>) documentCache.get("stats");
                builder.documentCache(CacheInfo.builder()
                        .lookups(getLong(stats, "lookups"))
                        .hits(getLong(stats, "hits"))
                        .hitratio(getFloat(stats, "hitratio"))
                        .inserts(getLong(stats, "inserts"))
                        .evictions(getLong(stats, "evictions"))
                        .size(getLong(stats, "size"))
                        .build());
            }

            // Filter cache
            NamedList<Object> filterCache = (NamedList<Object>) caches.get("filterCache");
            if (filterCache != null) {
                NamedList<Object> stats = (NamedList<Object>) filterCache.get("stats");
                builder.filterCache(CacheInfo.builder()
                        .lookups(getLong(stats, "lookups"))
                        .hits(getLong(stats, "hits"))
                        .hitratio(getFloat(stats, "hitratio"))
                        .inserts(getLong(stats, "inserts"))
                        .evictions(getLong(stats, "evictions"))
                        .size(getLong(stats, "size"))
                        .build());
            }
        }

        return builder.build();
    }

    public HandlerStats getHandlerMetrics(String collection) throws Exception {
        logger.info("Collecting handler metrics for collection: " + collection);
        
        try {
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("stats", "true");
            params.set("cat", "QUERYHANDLER,UPDATEHANDLER");
            params.set("wt", "json");

            // Extract actual collection name from shard name if needed
            String actualCollection = extractCollectionName(collection);
            
            // Validate collection exists first
            if (!validateCollectionExists(actualCollection)) {
                logger.warning("Collection does not exist for handler metrics: " + actualCollection);
                return null; // Return null instead of empty object
            }
            
            String path = actualCollection + "/admin/mbeans";

            GenericSolrRequest request = new GenericSolrRequest(
                    SolrRequest.METHOD.GET,
                    path,
                    params
            );

            NamedList<Object> response = solrClient.request(request);
            HandlerStats stats = extractHandlerStats(response);
            
            // Return null if all handler stats are empty/null
            if (isHandlerStatsEmpty(stats)) {
                logger.info("Handler stats are empty, returning null");
                return null;
            }
            
            return stats;
        } catch (Exception e) {
            logger.warning("Failed to collect handler metrics: " + e.getMessage());
            return null; // Return null instead of empty object
        }
    }
    
    private boolean isHandlerStatsEmpty(HandlerStats stats) {
        return stats == null || 
               (stats.getSelectHandler() == null && stats.getUpdateHandler() == null);
    }

    private HandlerStats extractHandlerStats(NamedList<Object> mbeans) {
        HandlerStats.HandlerStatsBuilder builder = HandlerStats.builder();

        NamedList<Object> queryHandlers = (NamedList<Object>) mbeans.get("QUERYHANDLER");

        if (queryHandlers != null) {
            // Select handler
            NamedList<Object> selectHandler = (NamedList<Object>) queryHandlers.get("/select");
            if (selectHandler != null) {
                NamedList<Object> stats = (NamedList<Object>) selectHandler.get("stats");
                builder.selectHandler(HandlerInfo.builder()
                        .requests(getLong(stats, "requests"))
                        .errors(getLong(stats, "errors"))
                        .timeouts(getLong(stats, "timeouts"))
                        .totalTime(getLong(stats, "totalTime"))
                        .avgTimePerRequest(getFloat(stats, "avgTimePerRequest"))
                        .avgRequestsPerSecond(getFloat(stats, "avgRequestsPerSecond"))
                        .build());
            }

            // Update handler
            NamedList<Object> updateHandler = (NamedList<Object>) queryHandlers.get("/update");
            if (updateHandler != null) {
                NamedList<Object> stats = (NamedList<Object>) updateHandler.get("stats");
                builder.updateHandler(HandlerInfo.builder()
                        .requests(getLong(stats, "requests"))
                        .errors(getLong(stats, "errors"))
                        .timeouts(getLong(stats, "timeouts"))
                        .totalTime(getLong(stats, "totalTime"))
                        .avgTimePerRequest(getFloat(stats, "avgTimePerRequest"))
                        .avgRequestsPerSecond(getFloat(stats, "avgRequestsPerSecond"))
                        .build());
            }
        }

        return builder.build();
    }


    /**
     * Extract the actual collection name from a shard name like "films_shard1_replica_n1"
     * Returns "films" from "films_shard1_replica_n1"
     */
    String extractCollectionName(String collectionOrShard) {
        if (collectionOrShard == null || collectionOrShard.isEmpty()) {
            return collectionOrShard;
        }

        // Check if this looks like a shard name (contains "_shard" pattern)
        if (collectionOrShard.contains("_shard")) {
            // Extract collection name before "_shard"
            int shardIndex = collectionOrShard.indexOf("_shard");
            return collectionOrShard.substring(0, shardIndex);
        }

        // If it doesn't look like a shard name, return as-is
        return collectionOrShard;
    }
    
    /**
     * Validate that a collection exists in Solr
     */
    private boolean validateCollectionExists(String collection) {
        try {
            List<String> collections = listCollections();
            
            // Check for exact match first
            if (collections.contains(collection)) {
                logger.info("Collection validation for '" + collection + "': true (exact match in available: " + collections + ")");
                return true;
            }
            
            // Check if any of the returned collections start with the collection name (for shard names)
            boolean shardMatch = collections.stream()
                    .anyMatch(c -> c.startsWith(collection + "_shard"));
            
            logger.info("Collection validation for '" + collection + "': " + shardMatch + " (shard match in available: " + collections + ")");
            return shardMatch;
        } catch (Exception e) {
            logger.warning("Failed to validate collection existence: " + e.getMessage());
            return false;
        }
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
