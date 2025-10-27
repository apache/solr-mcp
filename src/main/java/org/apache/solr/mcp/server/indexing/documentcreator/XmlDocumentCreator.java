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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for processing XML documents and converting them to SolrInputDocument objects.
 *
 * <p>This class handles the conversion of XML documents into Solr-compatible format using a
 * schema-less approach where Solr automatically detects field types.
 */
@Component
public class XmlDocumentCreator implements SolrDocumentCreator {

    /**
     * Creates a list of SolrInputDocument objects from XML content.
     *
     * <p>This method parses the XML and creates documents based on the structure: - If the XML has
     * multiple child elements with the same tag name (indicating repeated structures), each child
     * element becomes a separate document - Otherwise, the entire XML structure is treated as a
     * single document
     *
     * <p>This approach is flexible and doesn't rely on hardcoded element names, allowing it to work
     * with any XML structure.
     *
     * @param xml the XML content to process
     * @return list of SolrInputDocument objects ready for indexing
     * @throws DocumentProcessingException if XML parsing fails, parser configuration fails, or
     *     structural errors occur
     */
    public List<SolrInputDocument> create(String xml) throws DocumentProcessingException {
        try {
            Element rootElement = parseXmlDocument(xml);
            return processRootElement(rootElement);
        } catch (ParserConfigurationException e) {
            throw new DocumentProcessingException("Failed to configure XML parser", e);
        } catch (SAXException e) {
            throw new DocumentProcessingException(
                    "Failed to parse XML document: structural error", e);
        } catch (IOException e) {
            throw new DocumentProcessingException("Failed to read XML document", e);
        }
    }

    /**
     * Parses XML string into a DOM Element.
     */
    private Element parseXmlDocument(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = createSecureDocumentBuilderFactory();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc =
                builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return doc.getDocumentElement();
    }

    /** Creates a secure DocumentBuilderFactory with XXE protection. */
    private DocumentBuilderFactory createSecureDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** Processes the root element and determines document structure strategy. */
    private List<SolrInputDocument> processRootElement(Element rootElement) {
        List<Element> childElements = extractChildElements(rootElement);

        if (shouldTreatChildrenAsDocuments(childElements)) {
            return createDocumentsFromChildren(childElements);
        } else {
            return createSingleDocument(rootElement);
        }
    }

    /** Extracts child elements from the root element. */
    private List<Element> extractChildElements(Element rootElement) {
        NodeList children = rootElement.getChildNodes();
        List<Element> childElements = new ArrayList<>();

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                childElements.add((Element) children.item(i));
            }
        }

        return childElements;
    }

    /** Determines if child elements should be treated as separate documents. */
    private boolean shouldTreatChildrenAsDocuments(List<Element> childElements) {
        Map<String, Integer> childElementCounts = new HashMap<>();

        for (Element child : childElements) {
            String tagName = child.getTagName();
            childElementCounts.put(tagName, childElementCounts.getOrDefault(tagName, 0) + 1);
        }

        return childElementCounts.values().stream().anyMatch(count -> count > 1);
    }

    /** Creates documents from child elements (multiple documents strategy). */
    private List<SolrInputDocument> createDocumentsFromChildren(List<Element> childElements) {
        List<SolrInputDocument> documents = new ArrayList<>();

        for (Element childElement : childElements) {
            SolrInputDocument solrDoc = new SolrInputDocument();
            addXmlElementFields(solrDoc, childElement, "");
            if (!solrDoc.isEmpty()) {
                documents.add(solrDoc);
            }
        }

        return documents;
    }

    /** Creates a single document from the root element. */
    private List<SolrInputDocument> createSingleDocument(Element rootElement) {
        List<SolrInputDocument> documents = new ArrayList<>();
        SolrInputDocument solrDoc = new SolrInputDocument();
        addXmlElementFields(solrDoc, rootElement, "");

        if (!solrDoc.isEmpty()) {
            documents.add(solrDoc);
        }

        return documents;
    }

    /**
     * Recursively processes XML elements and adds them as fields to a SolrInputDocument.
     *
     * <p>This method implements the core logic for converting nested XML structures into flat field
     * names that Solr can efficiently index and search. It handles both element content and
     * attributes while maintaining data integrity.
     *
     * <p><strong>Processing Logic:</strong>
     *
     * <ul>
     *   <li><strong>Attributes</strong>: Converted to fields with "_attr" suffix
     *   <li><strong>Text Content</strong>: Element text content indexed directly
     *   <li><strong>Child Elements</strong>: Recursively processed with prefix concatenation
     *   <li><strong>Empty Elements</strong>: Skipped to avoid indexing empty fields
     *   <li><strong>Repeated Elements</strong>: Combined into multi-valued fields
     * </ul>
     *
     * <p><strong>Field Naming Convention:</strong>
     *
     * <ul>
     *   <li>Nested elements: parent_child (e.g., author_name)
     *   <li>Attributes: elementname_attr (e.g., id_attr)
     *   <li>All field names are sanitized for Solr compatibility
     * </ul>
     *
     * @param doc the SolrInputDocument to add fields to
     * @param element the XML element to process
     * @param prefix current field name prefix for nested element flattening
     * @see FieldNameSanitizer#sanitizeFieldName(String)
     */
    private void addXmlElementFields(SolrInputDocument doc, Element element, String prefix) {
        String elementName = FieldNameSanitizer.sanitizeFieldName(element.getTagName());
        String currentPrefix = prefix.isEmpty() ? elementName : prefix + "_" + elementName;

        processXmlAttributes(doc, element, prefix, currentPrefix);

        NodeList children = element.getChildNodes();
        boolean hasChildElements = hasChildElements(children);

        processXmlTextContent(doc, elementName, currentPrefix, prefix, hasChildElements, children);
        processXmlChildElements(doc, children, currentPrefix);
    }

    /** Processes XML element attributes and adds them as fields to the document. */
    private void processXmlAttributes(
            SolrInputDocument doc, Element element, String prefix, String currentPrefix) {
        if (!element.hasAttributes()) {
            return;
        }

        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attr = element.getAttributes().item(i);
            String attrName = FieldNameSanitizer.sanitizeFieldName(attr.getNodeName()) + "_attr";
            String fieldName = prefix.isEmpty() ? attrName : currentPrefix + "_" + attrName;
            String attrValue = attr.getNodeValue();

            if (attrValue != null && !attrValue.trim().isEmpty()) {
                doc.addField(fieldName, attrValue.trim());
            }
        }
    }

    /** Checks if the node list contains any child elements. */
    private boolean hasChildElements(NodeList children) {
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /** Processes XML text content and adds it as a field to the document. */
    private void processXmlTextContent(
            SolrInputDocument doc,
            String elementName,
            String currentPrefix,
            String prefix,
            boolean hasChildElements,
            NodeList children) {
        String textContent = extractTextContent(children);
        if (!textContent.isEmpty()) {
            String fieldName = prefix.isEmpty() ? elementName : currentPrefix;
            doc.addField(fieldName, textContent);
        }
    }

    /** Extracts text content from child nodes. */
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

    /** Recursively processes XML child elements. */
    private void processXmlChildElements(
            SolrInputDocument doc, NodeList children, String currentPrefix) {
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                addXmlElementFields(doc, (Element) child, currentPrefix);
            }
        }
    }
}
