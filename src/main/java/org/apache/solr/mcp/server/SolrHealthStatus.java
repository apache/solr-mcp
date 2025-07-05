package org.apache.solr.mcp.server;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class SolrHealthStatus {
    private boolean isHealthy;
    private String errorMessage;
    private Long responseTime;
    private Long totalDocuments;
    private Date lastChecked;
    private String collection;
    private String solrVersion;
    private String status;
}