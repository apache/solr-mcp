package org.apache.solr.mcp.server.indexing.documentcreator;

import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for processing XML documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of XML documents into Solr-compatible format
 * using a schema-less approach where Solr automatically detects field types.</p>
 */
@Component
public class XmlDocumentCreator implements SolrDocumentCreator {

    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");

    /**
     * Creates a list of SolrInputDocument objects from XML content.
     *
     * <p>This method parses the XML and creates documents based on the structure:
     * - If the XML contains elements that could represent individual documents
     * (like &lt;book&gt;, &lt;document&gt;, &lt;record&gt;), each becomes a separate document
     * - Otherwise, the entire XML structure is treated as a single document</p>
     *
     * @param xml the XML content to process
     * @return list of SolrInputDocument objects ready for indexing
     * @throws RuntimeException if XML parsing fails
     */
    public List<SolrInputDocument> create(String xml) throws IOException {
        List<SolrInputDocument> documents = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Element rootElement = doc.getDocumentElement();
            NodeList children = rootElement.getChildNodes();

            // Check if we have potential document elements (common patterns for individual records)
            boolean hasDocumentElements = false;
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    Element child = (Element) children.item(i);
                    String tagName = child.getTagName().toLowerCase();
                    // Common patterns that suggest individual documents/records
                    if (tagName.equals("document") || tagName.equals("record") ||
                            tagName.equals("item") || tagName.equals("entry") ||
                            tagName.equals("book") || tagName.equals("product") ||
                            tagName.equals("person") || tagName.equals("customer") ||
                            tagName.equals("order") || tagName.equals("article")) {
                        hasDocumentElements = true;
                        break;
                    }
                }
            }

            // Process child elements as separate documents if they match document patterns
            if (hasDocumentElements) {
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                        Element childElement = (Element) children.item(i);
                        SolrInputDocument solrDoc = new SolrInputDocument();
                        addXmlElementFields(solrDoc, childElement, "");
                        if (!solrDoc.isEmpty()) { // Only add if document has fields
                            documents.add(solrDoc);
                        }
                    }
                }
            }

            // If no explicit document elements found, treat the entire root as a single document
            if (!hasDocumentElements) {
                SolrInputDocument solrDoc = new SolrInputDocument();
                addXmlElementFields(solrDoc, rootElement, "");
                if (!solrDoc.isEmpty()) { // Only add if document has fields
                    documents.add(solrDoc);
                }
            }

        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException("Failed to parse XML: " + e.getMessage(), e);
        }

        return documents;
    }

    /**
     * Recursively processes XML elements and adds them as fields to a SolrInputDocument.
     *
     * <p>This method implements the core logic for converting nested XML structures
     * into flat field names that Solr can efficiently index and search. It handles
     * both element content and attributes while maintaining data integrity.</p>
     *
     * <p><strong>Processing Logic:</strong></p>
     * <ul>
     *   <li><strong>Attributes</strong>: Converted to fields with "_attr" suffix</li>
     *   <li><strong>Text Content</strong>: Element text content indexed directly</li>
     *   <li><strong>Child Elements</strong>: Recursively processed with prefix concatenation</li>
     *   <li><strong>Empty Elements</strong>: Skipped to avoid indexing empty fields</li>
     *   <li><strong>Repeated Elements</strong>: Combined into multi-valued fields</li>
     * </ul>
     *
     * <p><strong>Field Naming Convention:</strong></p>
     * <ul>
     *   <li>Nested elements: parent_child (e.g., author_name)</li>
     *   <li>Attributes: elementname_attr (e.g., id_attr)</li>
     *   <li>All field names are sanitized for Solr compatibility</li>
     * </ul>
     *
     * @param doc     the SolrInputDocument to add fields to
     * @param element the XML element to process
     * @param prefix  current field name prefix for nested element flattening
     * @see #sanitizeFieldName(String)
     */
    private void addXmlElementFields(SolrInputDocument doc, Element element, String prefix) {
        String elementName = sanitizeFieldName(element.getTagName());
        String currentPrefix = prefix.isEmpty() ? elementName : prefix + "_" + elementName;

        processXmlAttributes(doc, element, prefix, currentPrefix);

        NodeList children = element.getChildNodes();
        boolean hasChildElements = hasChildElements(children);

        processXmlTextContent(doc, element, elementName, currentPrefix, prefix, hasChildElements, children);
        processXmlChildElements(doc, children, currentPrefix);
    }

    /**
     * Processes XML element attributes and adds them as fields to the document.
     */
    private void processXmlAttributes(SolrInputDocument doc, Element element, String prefix, String currentPrefix) {
        if (!element.hasAttributes()) {
            return;
        }

        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attr = element.getAttributes().item(i);
            String attrName = sanitizeFieldName(attr.getNodeName()) + "_attr";
            String fieldName = prefix.isEmpty() ? attrName : currentPrefix + "_" + attrName;
            String attrValue = attr.getNodeValue();

            if (attrValue != null && !attrValue.trim().isEmpty()) {
                doc.addField(fieldName, attrValue.trim());
            }
        }
    }

    /**
     * Checks if the node list contains any child elements.
     */
    private boolean hasChildElements(NodeList children) {
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Processes XML text content and adds it as a field to the document.
     */
    private void processXmlTextContent(SolrInputDocument doc, Element element, String elementName,
                                       String currentPrefix, String prefix, boolean hasChildElements,
                                       NodeList children) {
        String textContent = extractTextContent(children);
        if (!textContent.isEmpty()) {
            String fieldName = prefix.isEmpty() ? elementName : currentPrefix;
            doc.addField(fieldName, textContent);
        }
    }

    /**
     * Extracts text content from child nodes.
     */
    private String extractTextContent(NodeList children) {
        StringBuilder textContent = new StringBuilder();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getNodeValue();
                if (text != null && !text.trim().isEmpty()) {
                    textContent.append(text.trim()).append(" ");
                }
            }
        }

        return textContent.toString().trim();
    }

    /**
     * Recursively processes XML child elements.
     */
    private void processXmlChildElements(SolrInputDocument doc, NodeList children, String currentPrefix) {
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                addXmlElementFields(doc, (Element) child, currentPrefix);
            }
        }
    }

    /**
     * Sanitizes field names to ensure they are compatible with Solr's field naming requirements.
     *
     * <p>This method ensures that field names:</p>
     * <ul>
     *   <li>Contain only alphanumeric characters and underscores</li>
     *   <li>Are lowercase for consistency</li>
     *   <li>Have special characters replaced with underscores</li>
     *   <li>Don't have leading/trailing underscores or multiple consecutive underscores</li>
     * </ul>
     *
     * @param fieldName the original field name to sanitize
     * @return a sanitized field name safe for use in Solr
     */
    private String sanitizeFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return "field";
        }

        // Convert to lowercase and replace invalid characters with underscores
        String sanitized = FIELD_NAME_PATTERN.matcher(fieldName.toLowerCase()).replaceAll("_");

        // Remove leading/trailing underscores and collapse multiple underscores
        sanitized = sanitized.replaceAll("^_+|_+$", "").replaceAll("_+", "_");

        // If the result is empty after sanitization, provide a default name
        if (sanitized.isEmpty()) {
            return "field";
        }

        // Ensure the field name doesn't start with a number (Solr requirement)
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "field_" + sanitized;
        }

        return sanitized;
    }
}