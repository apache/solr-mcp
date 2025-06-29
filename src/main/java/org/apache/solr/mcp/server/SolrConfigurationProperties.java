package org.apache.solr.mcp.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solr")
public record SolrConfigurationProperties(String url) {


}
