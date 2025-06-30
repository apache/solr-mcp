package org.apache.solr.mcp.server;

import java.util.List;
import java.util.Map;

public record SearchResponse(
            long numFound,
            long start,
            Float maxScore,
            List<Map<String, Object>> documents,
            Map<String, Map<String, Long>> facets
    ) {}