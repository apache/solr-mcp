package org.apache.solr.mcp.server.indexing;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class for XML indexing functionality in IndexingService.
 *
 * <p>This test verifies that the IndexingService can correctly parse XML data
 * and convert it into SolrInputDocument objects using the schema-less approach.</p>
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class XmlIndexingTest {

    @Autowired
    private IndexingDocumentCreator indexingDocumentCreator;

    @Test
    void testCreateSchemalessDocumentsFromXmlSingleDocument() throws Exception {
        // Given

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

    @Test
    void testCreateSchemalessDocumentsFromXmlWithMalformedXml() {
        // Given

        String malformedXml = """
                <book>
                    <title>Incomplete Book
                    <author>John Doe</author>
                </book>
                """;

        // When/Then
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(malformedXml))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithInvalidCharacters() {
        // Given

        String invalidXml = """
                <book>
                    <title>Book with invalid character: \u0000</title>
                    <author>John Doe</author>
                </book>
                """;

        // When/Then
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(invalidXml))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithDoctype() {
        // Given

        String xmlWithDoctype = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE book [
                    <!ELEMENT book (title, author)>
                    <!ELEMENT title (#PCDATA)>
                    <!ELEMENT author (#PCDATA)>
                ]>
                <book>
                    <title>Test Book</title>
                    <author>Test Author</author>
                </book>
                """;

        // When/Then - Should fail due to XXE protection
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlWithDoctype))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithExternalEntity() {
        // Given

        String xmlWithExternalEntity = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE book [
                    <!ENTITY external SYSTEM "file:///etc/passwd">
                ]>
                <book>
                    <title>&external;</title>
                    <author>Test Author</author>
                </book>
                """;

        // When/Then - Should fail due to XXE protection
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlWithExternalEntity))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithNullInput() {
        // Given

        // When/Then
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML input cannot be null or empty");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithEmptyInput() {
        // Given

        // When/Then
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML input cannot be null or empty");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithWhitespaceOnlyInput() {
        // Given

        // When/Then
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml("   \n\t  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML input cannot be null or empty");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithLargeDocument() {
        // Given

        // Create a large XML document (over 10MB)
        StringBuilder largeXml = new StringBuilder();
        largeXml.append("<books>");

        // Add enough data to exceed the 10MB limit
        String bookTemplate = """
                <book id="%d">
                    <title>%s</title>
                    <content>%s</content>
                </book>
                """;

        // Create approximately 11MB of XML data
        String longContent = "A".repeat(10000); // 10KB per book
        for (int i = 0; i < 1200; i++) { // 1200 * 10KB = 12MB
            largeXml.append(String.format(bookTemplate, i, "Title " + i, longContent));
        }
        largeXml.append("</books>");

        // When/Then
        assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromXml(largeXml.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML document too large");
    }

    @Test
    void testCreateSchemalessDocumentsFromXmlWithComplexNestedStructure() throws Exception {
        // Given

        String complexXml = """
                <product id="123" category="electronics">
                    <details>
                        <name lang="en">Smartphone</name>
                        <name lang="es">Teléfono inteligente</name>
                        <specifications>
                            <screen size="6.1" type="OLED">Full HD+</screen>
                            <camera type="main" resolution="12MP">Primary camera</camera>
                            <camera type="selfie" resolution="8MP">Front camera</camera>
                            <storage>
                                <internal>128GB</internal>
                                <expandable>Yes</expandable>
                            </storage>
                        </specifications>
                    </details>
                    <pricing currency="USD">599.99</pricing>
                    <availability>
                        <regions>
                            <region>US</region>
                            <region>EU</region>
                            <region>APAC</region>
                        </regions>
                        <inStock>true</inStock>
                    </availability>
                </product>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(complexXml);

        // Then
        assertThat(documents).hasSize(1);

        SolrInputDocument doc = documents.getFirst();

        // Verify basic attributes
        assertThat(doc.getFieldValue("id_attr")).isEqualTo("123");
        assertThat(doc.getFieldValue("category_attr")).isEqualTo("electronics");

        // Verify nested structure flattening
        assertThat(doc.getFieldValue("product_details_name_lang_attr")).isNotNull();
        assertThat(doc.getFieldValue("product_details_specifications_screen_size_attr")).isEqualTo("6.1");
        assertThat(doc.getFieldValue("product_details_specifications_screen_type_attr")).isEqualTo("OLED");
        assertThat(doc.getFieldValue("product_details_specifications_screen")).isEqualTo("Full HD+");

        // Verify multiple similar elements
        assertThat(doc.getFieldValue("product_details_specifications_camera_type_attr")).isNotNull();
        assertThat(doc.getFieldValue("product_details_specifications_camera_resolution_attr")).isNotNull();

        // Verify deeply nested elements
        assertThat(doc.getFieldValue("product_details_specifications_storage_internal")).isEqualTo("128GB");
        assertThat(doc.getFieldValue("product_details_specifications_storage_expandable")).isEqualTo("Yes");

        // Verify pricing and availability
        assertThat(doc.getFieldValue("product_pricing_currency_attr")).isEqualTo("USD");
        assertThat(doc.getFieldValue("product_pricing")).isEqualTo("599.99");
        assertThat(doc.getFieldValue("product_availability_instock")).isEqualTo("true");
        assertThat(doc.getFieldValue("product_availability_regions_region")).isNotNull();
    }

    @Test
    void testFieldNameSanitization() throws Exception {
        // Given

        String xmlWithSpecialChars = """
                <product_data id="123">
                    <product_name>Test Product</product_name>
                    <price_USD>99.99</price_USD>
                    <category_type>electronics</category_type>
                    <field__with__multiple__underscores>value</field__with__multiple__underscores>
                    <field_with_dashes>dashed value</field_with_dashes>
                    <UPPERCASE_FIELD>uppercase value</UPPERCASE_FIELD>
                </product_data>
                """;

        // When
        List<SolrInputDocument> documents = indexingDocumentCreator.createSchemalessDocumentsFromXml(xmlWithSpecialChars);

        // Then
        assertThat(documents).hasSize(1);

        SolrInputDocument doc = documents.getFirst();

        // Verify field name sanitization
        assertThat(doc.getFieldValue("id_attr")).isEqualTo("123");
        assertThat(doc.getFieldValue("product_data_product_name")).isEqualTo("Test Product");
        assertThat(doc.getFieldValue("product_data_price_usd")).isEqualTo("99.99");
        assertThat(doc.getFieldValue("product_data_category_type")).isEqualTo("electronics");
        assertThat(doc.getFieldValue("product_data_field_with_multiple_underscores")).isEqualTo("value");
        assertThat(doc.getFieldValue("product_data_field_with_dashes")).isEqualTo("dashed value");
        assertThat(doc.getFieldValue("product_data_uppercase_field")).isEqualTo("uppercase value");
    }
}