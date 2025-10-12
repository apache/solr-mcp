package org.apache.solr.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;

import java.io.File;

// run after project has been built with "./gradlew build -x test and the mcp server jar is connected to a running solr"
public class ClientStdio {

    public static void main(String[] args) {

        System.out.println(new File(".").getAbsolutePath());

        var stdioParams = ServerParameters.builder("java")
                .args("-jar",
                        "build/libs/solr-mcp-server-0.0.1-SNAPSHOT.jar")
                .build();

        var transport = new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(new ObjectMapper()));

        new SampleClient(transport).run();
    }

}