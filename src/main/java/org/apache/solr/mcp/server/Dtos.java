package org.apache.solr.mcp.server;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Date;


@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class SolrMetrics {
    private IndexStats indexStats;
    private QueryStats queryStats;
    private CacheStats cacheStats;
    private HandlerStats handlerStats;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date timestamp;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class IndexStats {
    private Integer numDocs;
    private Integer segmentCount;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class FieldStats {
    private String type;
    private Integer docs;
    private Integer distinct;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class QueryStats {
    private Integer queryTime;
    private Long totalResults;
    private Long start;
    private Float maxScore;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class CacheStats {
    private CacheInfo queryResultCache;
    private CacheInfo documentCache;
    private CacheInfo filterCache;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class CacheInfo {
    private Long lookups;
    private Long hits;
    private Float hitratio;
    private Long inserts;
    private Long evictions;
    private Long size;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class HandlerStats {
    private HandlerInfo selectHandler;
    private HandlerInfo updateHandler;
}

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class HandlerInfo {
    private Long requests;
    private Long errors;
    private Long timeouts;
    private Long totalTime;
    private Float avgTimePerRequest;
    private Float avgRequestsPerSecond;
}



@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
class SolrHealthStatus {
    private boolean isHealthy;
    private String errorMessage;
    private Long responseTime;
    private Long totalDocuments;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Date lastChecked;
    private String collection;
    private String solrVersion;
    private String status;
}