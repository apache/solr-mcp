package org.apache.solr.mcp.server.indexing.documentcreator;

import org.apache.solr.common.SolrInputDocument;

import java.util.List;

public interface SolrDocumentCreator {

    List<SolrInputDocument> create(String content) throws DocumentProcessingException;
}
