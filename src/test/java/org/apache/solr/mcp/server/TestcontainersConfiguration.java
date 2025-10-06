package org.apache.solr.mcp.server;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final int SOLR_PORT = 8983;

    @Bean
    SolrContainer solr() {
        SolrContainer container = new SolrContainer(DockerImageName.parse("solr:latest"));
        container.start();
        return container;
    }

    @Bean
    DynamicPropertyRegistrar propertiesRegistrar(SolrContainer solr) {
        return registry -> registry.add("solr.url", () -> "http://" + solr.getHost() + ":" + solr.getMappedPort(SOLR_PORT) + "/solr/");
    }
}
