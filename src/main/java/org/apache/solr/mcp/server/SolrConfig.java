package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SolrConfigurationProperties.class)
public class SolrConfig {

    // todo connectiondetails, timeouts, cloud mode
    @Bean
    SolrClient solrClient(SolrConfigurationProperties properties) {
        String url = properties.url();
        // Ensure URL ends with a slash
        if (!url.endsWith("/")) {
            url = url + "/";
        }

        // Use HttpSolrClient with explicit base URL
        return new HttpSolrClient.Builder(url)
                .withConnectionTimeout(10000)
                .withSocketTimeout(60000)
                .build();
    }
}
