package org.apache.solr.mcp.server;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for XML indexing functionality in IndexingService.
 *
 * <p>This test verifies that the IndexingService can correctly parse XML data
 * and convert it into SolrInputDocument objects using the schema-less approach.</p>
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class XmlIndexingTest {

    @Test
    void testCreateSchemalessDocumentsFromXmlSingleDocument() throws Exception {
        // Given
        IndexingDocumentCreator indexingDocumentCreator = new IndexingDocumentCreator();

        String xmlData = """
                <book id="123">
                    <title>A Game of Thrones</title>
                    <author>
                        <name>George R.R. Martin</name>
                        <email>george@example.com</email>
                    </author>
                    <price>7.99</price>
                    <inStock>true</inStock>
                    <genre>fantasy</genre>
                </book>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlData);

        // Then
        assertThat(documents).hasSize(1);

        SolrInputDocument doc = documents.getFirst();
        assertThat(doc.getFieldValue("id_attr")).isEqualTo("123");
        assertThat(doc.getFieldValue("book_title")).isEqualTo("A Game of Thrones");
        assertThat(doc.getFieldValue("book_author_name")).isEqualTo("George R.R. Martin");
        assertThat(doc.getFieldValue("book_author_email")).isEqualTo("george@example.com");
        assertThat(doc.getFieldValue("book_price")).isEqualTo("7.99");
        assertThat(doc.getFieldValue("book_instock")).isEqualTo("true");
        assertThat(doc.getFieldValue("book_genre")).isEqualTo("fantasy");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlMultipleDocuments() throws Exception {
        // Given
        IndexingDocumentCreator indexingDocumentCreator = new IndexingDocumentCreator();

        String xmlData = """
                <books>
                    <document id="1">
                        <title>A Game of Thrones</title>
                        <author>George R.R. Martin</author>
                        <genre>fantasy</genre>
                    </document>
                    <document id="2">
                        <title>Foundation</title>
                        <author>Isaac Asimov</author>
                        <genre>scifi</genre>
                    </document>
                    <document id="3">
                        <title>Dune</title>
                        <author>Frank Herbert</author>
                        <genre>scifi</genre>
                    </document>
                </books>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlData);

        // Then
        assertThat(documents).hasSize(3);

        // Verify first document
        SolrInputDocument firstDoc = documents.get(0);
        assertThat(firstDoc.getFieldValue("id_attr")).isEqualTo("1");
        assertThat(firstDoc.getFieldValue("document_title")).isEqualTo("A Game of Thrones");
        assertThat(firstDoc.getFieldValue("document_author")).isEqualTo("George R.R. Martin");
        assertThat(firstDoc.getFieldValue("document_genre")).isEqualTo("fantasy");

        // Verify second document
        SolrInputDocument secondDoc = documents.get(1);
        assertThat(secondDoc.getFieldValue("id_attr")).isEqualTo("2");
        assertThat(secondDoc.getFieldValue("document_title")).isEqualTo("Foundation");
        assertThat(secondDoc.getFieldValue("document_author")).isEqualTo("Isaac Asimov");
        assertThat(secondDoc.getFieldValue("document_genre")).isEqualTo("scifi");

        // Verify third document
        SolrInputDocument thirdDoc = documents.get(2);
        assertThat(thirdDoc.getFieldValue("id_attr")).isEqualTo("3");
        assertThat(thirdDoc.getFieldValue("document_title")).isEqualTo("Dune");
        assertThat(thirdDoc.getFieldValue("document_author")).isEqualTo("Frank Herbert");
        assertThat(thirdDoc.getFieldValue("document_genre")).isEqualTo("scifi");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithAttributes() throws Exception {
        // Given
        IndexingDocumentCreator indexingDocumentCreator = new IndexingDocumentCreator();

        String xmlData = """
                <product id="P123" category="electronics" featured="true">
                    <name lang="en">Smartphone</name>
                    <price currency="USD">599.99</price>
                    <description>Latest smartphone with advanced features</description>
                </product>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlData);

        // Then
        assertThat(documents).hasSize(1);

        SolrInputDocument doc = documents.getFirst();
        assertThat(doc.getFieldValue("id_attr")).isEqualTo("P123");
        assertThat(doc.getFieldValue("category_attr")).isEqualTo("electronics");
        assertThat(doc.getFieldValue("featured_attr")).isEqualTo("true");
        assertThat(doc.getFieldValue("product_name_lang_attr")).isEqualTo("en");
        assertThat(doc.getFieldValue("product_price_currency_attr")).isEqualTo("USD");
        assertThat(doc.getFieldValue("product_name")).isEqualTo("Smartphone");
        assertThat(doc.getFieldValue("product_price")).isEqualTo("599.99");
        assertThat(doc.getFieldValue("product_description")).isEqualTo("Latest smartphone with advanced features");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithEmptyValues() throws Exception {
        // Given
        IndexingDocumentCreator indexingDocumentCreator = new IndexingDocumentCreator();

        String xmlData = """
                <items>
                    <item id="1">
                        <name>Product One</name>
                        <description></description>
                        <price>19.99</price>
                    </item>
                    <item id="2">
                        <name></name>
                        <description>Product with no name</description>
                        <price>29.99</price>
                    </item>
                </items>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlData);

        // Then
        assertThat(documents).hasSize(2);

        // First document should skip empty description
        SolrInputDocument firstDoc = documents.get(0);
        assertThat(firstDoc.getFieldValue("id_attr")).isEqualTo("1");
        assertThat(firstDoc.getFieldValue("item_name")).isEqualTo("Product One");
        assertThat(firstDoc.getFieldValue("item_description")).isNull(); // Empty element should not be indexed
        assertThat(firstDoc.getFieldValue("item_price")).isEqualTo("19.99");

        // Second document should skip empty name
        SolrInputDocument secondDoc = documents.get(1);
        assertThat(secondDoc.getFieldValue("id_attr")).isEqualTo("2");
        assertThat(secondDoc.getFieldValue("item_name")).isNull(); // Empty element should not be indexed
        assertThat(secondDoc.getFieldValue("item_description")).isEqualTo("Product with no name");
        assertThat(secondDoc.getFieldValue("item_price")).isEqualTo("29.99");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithRepeatedElements() throws Exception {
        // Given
        IndexingDocumentCreator indexingDocumentCreator = new IndexingDocumentCreator();

        String xmlData = """
                <book>
                    <title>Programming Book</title>
                    <author>John Doe</author>
                    <tags>
                        <tag>programming</tag>
                        <tag>java</tag>
                        <tag>software</tag>
                    </tags>
                    <categories>
                        <category>Technology</category>
                        <category>Education</category>
                    </categories>
                </book>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlData);

        // Then
        assertThat(documents).hasSize(1);

        SolrInputDocument doc = documents.getFirst();
        assertThat(doc.getFieldValue("book_title")).isEqualTo("Programming Book");
        assertThat(doc.getFieldValue("book_author")).isEqualTo("John Doe");

        // Check that repeated elements are handled properly
        // Note: The current implementation processes each element separately,
        // so we check for the individual tag and category fields
        assertThat(doc.getFieldValue("book_tags_tag")).isNotNull();
        assertThat(doc.getFieldValue("book_categories_category")).isNotNull();
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlMixedContent() throws Exception {
        // Given
        IndexingDocumentCreator indexingDocumentCreator = new IndexingDocumentCreator();

        String xmlData = """
                <article>
                    <title>Mixed Content Example</title>
                    <content>
                        This is some text content with 
                        <emphasis>emphasized text</emphasis>
                        and more content here.
                    </content>
                    <author>Jane Smith</author>
                </article>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlData);

        // Then
        assertThat(documents).hasSize(1);

        SolrInputDocument doc = documents.getFirst();
        assertThat(doc.getFieldValue("article_title")).isEqualTo("Mixed Content Example");
        assertThat(doc.getFieldValue("article_author")).isEqualTo("Jane Smith");

        // Mixed content should be handled - text content should be captured
        assertThat(doc.getFieldValue("article_content")).isNotNull();
        assertThat(doc.getFieldValue("article_content_emphasis")).isEqualTo("emphasized text");
    }
}