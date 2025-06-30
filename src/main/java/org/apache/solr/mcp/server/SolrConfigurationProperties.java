package org.apache.solr.mcp.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solr")
public class SolrConfigurationProperties {
    private String url;

    public SolrConfigurationProperties() {
    }

    public SolrConfigurationProperties(String url) {
        this.url = url;
    }

    public String url() {
        return url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
