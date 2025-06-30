package org.apache.solr.mcp.server;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.time.Duration;

public class SolrTestContainer extends GenericContainer<SolrTestContainer> {
    private static final DockerImageName SOLR_IMAGE = DockerImageName.parse("solr:9-slim");
    private static final int SOLR_PORT = 8983;

    public SolrTestContainer() {
        super(SOLR_IMAGE);
        withExposedPorts(SOLR_PORT);
        withCommand("solr-precreate", "books");
        waitingFor(Wait.forHttp("/solr/").forPort(SOLR_PORT).withStartupTimeout(Duration.ofMinutes(2)));
    }

    @ServiceConnection
    public String getSolrUrl() {
        // Ensure the container is started before accessing the mapped port
        if (!isRunning()) {
            return "http://localhost:8983/solr"; // Default URL for local development
        }
        return String.format("http://%s:%d/solr", getHost(), getMappedPort(SOLR_PORT));
    }

    public void loadSampleData() {
        try {
            // Copy sample data to container
            copyFileToContainer(
                    MountableFile.forClasspathResource("sample-data/books.csv"),
                    "/tmp/books.csv"
            );

            // Load data into Solr
            execInContainer(
                    "bin/post", "-c", "books", "/tmp/books.csv"
            );
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to load sample data into Solr", e);
        }
    }
}
