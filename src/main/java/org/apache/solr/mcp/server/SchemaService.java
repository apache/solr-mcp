package org.apache.solr.mcp.server;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.schema.SchemaRepresentation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class SchemaService {

    private final SolrClient solrClient;

    public SchemaService(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    @Tool(description = "Get schema for a Solr collection")
    public SchemaRepresentation getSchema(String collection) throws Exception {
        SchemaRequest schemaRequest = new SchemaRequest();
        return schemaRequest.process(solrClient, collection).getSchemaRepresentation();
    }

}
