/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Test class for CSV indexing functionality in IndexingService.
 *
 * <p>This test verifies that the IndexingService can correctly parse CSV data and convert it into
 * SolrInputDocument objects using the schema-less approach.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class CsvIndexingTest {

    @Autowired private IndexingDocumentCreator indexingDocumentCreator;

    @Test
    void testCreateSchemalessDocumentsFromCsv() throws Exception {
        // Given

        String csvData =
                """
id,cat,name,price,inStock,author,series_t,sequence_i,genre_s
0553573403,book,A Game of Thrones,7.99,true,George R.R. Martin,"A Song of Ice and Fire",1,fantasy
0553579908,book,A Clash of Kings,7.99,true,George R.R. Martin,"A Song of Ice and Fire",2,fantasy
0553293354,book,Foundation,7.99,true,Isaac Asimov,Foundation Novels,1,scifi
""";

        // When
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromCsv(csvData);

        // Then
        assertThat(documents).hasSize(3);

        // Verify first document
        SolrInputDocument firstDoc = documents.getFirst();
        assertThat(firstDoc.getFieldValue("id")).isEqualTo("0553573403");
        assertThat(firstDoc.getFieldValue("cat")).isEqualTo("book");
        assertThat(firstDoc.getFieldValue("name")).isEqualTo("A Game of Thrones");
        assertThat(firstDoc.getFieldValue("price")).isEqualTo("7.99");
        assertThat(firstDoc.getFieldValue("instock")).isEqualTo("true");
        assertThat(firstDoc.getFieldValue("author")).isEqualTo("George R.R. Martin");
        assertThat(firstDoc.getFieldValue("series_t")).isEqualTo("A Song of Ice and Fire");
        assertThat(firstDoc.getFieldValue("sequence_i")).isEqualTo("1");
        assertThat(firstDoc.getFieldValue("genre_s")).isEqualTo("fantasy");

        // Verify second document
        SolrInputDocument secondDoc = documents.get(1);
        assertThat(secondDoc.getFieldValue("id")).isEqualTo("0553579908");
        assertThat(secondDoc.getFieldValue("name")).isEqualTo("A Clash of Kings");
        assertThat(secondDoc.getFieldValue("sequence_i")).isEqualTo("2");

        // Verify third document
        SolrInputDocument thirdDoc = documents.get(2);
        assertThat(thirdDoc.getFieldValue("id")).isEqualTo("0553293354");
        assertThat(thirdDoc.getFieldValue("name")).isEqualTo("Foundation");
        assertThat(thirdDoc.getFieldValue("author")).isEqualTo("Isaac Asimov");
        assertThat(thirdDoc.getFieldValue("genre_s")).isEqualTo("scifi");
    }

    @Test
    void testCreateSchemalessDocumentsFromCsvWithEmptyValues() throws Exception {
        // Given

        String csvData =
                """
                id,name,description
                1,Test Product,Some description
                2,Another Product,
                3,,Empty name
                """;

        // When
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromCsv(csvData);

        // Then
        assertThat(documents).hasSize(3);

        // First document should have all fields
        SolrInputDocument firstDoc = documents.getFirst();
        assertThat(firstDoc.getFieldValue("id")).isEqualTo("1");
        assertThat(firstDoc.getFieldValue("name")).isEqualTo("Test Product");
        assertThat(firstDoc.getFieldValue("description")).isEqualTo("Some description");

        // Second document should skip empty description
        SolrInputDocument secondDoc = documents.get(1);
        assertThat(secondDoc.getFieldValue("id")).isEqualTo("2");
        assertThat(secondDoc.getFieldValue("name")).isEqualTo("Another Product");
        assertThat(secondDoc.getFieldValue("description")).isNull();

        // Third document should skip empty name
        SolrInputDocument thirdDoc = documents.get(2);
        assertThat(thirdDoc.getFieldValue("id")).isEqualTo("3");
        assertThat(thirdDoc.getFieldValue("name")).isNull();
        assertThat(thirdDoc.getFieldValue("description")).isEqualTo("Empty name");
    }

    @Test
    void testCreateSchemalessDocumentsFromCsvWithQuotedValues() throws Exception {
        // Given

        String csvData =
                """
                id,name,description
                1,"Quoted Name","Quoted description"
                2,Regular Name,Regular description
                """;

        // When
        List<SolrInputDocument> documents =
                indexingDocumentCreator.createSchemalessDocumentsFromCsv(csvData);

        // Then
        assertThat(documents).hasSize(2);

        // First document should have quotes removed
        SolrInputDocument firstDoc = documents.getFirst();
        assertThat(firstDoc.getFieldValue("id")).isEqualTo("1");
        assertThat(firstDoc.getFieldValue("name")).isEqualTo("Quoted Name");
        assertThat(firstDoc.getFieldValue("description")).isEqualTo("Quoted description");

        // Second document should remain unchanged
        SolrInputDocument secondDoc = documents.get(1);
        assertThat(secondDoc.getFieldValue("id")).isEqualTo("2");
        assertThat(secondDoc.getFieldValue("name")).isEqualTo("Regular Name");
        assertThat(secondDoc.getFieldValue("description")).isEqualTo("Regular description");
    }
}
