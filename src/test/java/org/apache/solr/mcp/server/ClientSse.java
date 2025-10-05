package org.apache.solr.mcp.server;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.junit.jupiter.api.Disabled;

@Disabled("Enable only when MCP server is running in http mode")
public class ClientSse {

    public static void main(String[] args) {
        var transport = HttpClientSseClientTransport.builder("http://localhost:8080").build();
        new SampleClient(transport).run();
    }

}