# Spec Delta

## Purpose

Defines what the four indexing tools (`index-json-documents`, `index-csv-documents`, `index-xml-documents`, `index-markdown-documents`) report after a successful indexing call.

## ADDED Requirements

### Requirement: Indexing returns a typed result instead of prose
Every indexing tool SHALL return an object with `collection` and `format` (`json`, `csv`, `xml` or `markdown`). It SHALL NOT return a sentence describing the outcome.

#### Scenario: Indexing JSON
- **WHEN** a client indexes JSON documents into `books`
- **THEN** the result has `collection` `books` and `format` `json`

### Requirement: Server-parsed formats report document counts and field names
For JSON and Markdown, which the server parses itself, the result SHALL include:
- `documentsSubmitted`: the number of documents the input produced.
- `documentsIndexed`: the number Solr accepted.
- `fieldNames`: the field names used.

#### Scenario: Every document accepted
- **WHEN** a client indexes 3 JSON documents and Solr accepts all of them
- **THEN** `documentsSubmitted` is 3 and `documentsIndexed` is 3

#### Scenario: Some documents rejected
- **WHEN** a client indexes 5 JSON documents and Solr rejects 2 of them individually
- **THEN** `documentsSubmitted` is 5 and `documentsIndexed` is 3

#### Scenario: Markdown split into sections
- **WHEN** one Markdown input yields 4 documents
- **THEN** `documentsSubmitted` is 4

### Requirement: Pass-through formats report acceptance without counts
For CSV and XML, which go to Solr unchanged, the result SHALL omit `documentsSubmitted`, `documentsIndexed` and `fieldNames`, because Solr's update response carries no document count.

#### Scenario: Indexing CSV
- **WHEN** a client indexes CSV and Solr accepts and commits it
- **THEN** the result has `collection` and `format` `csv` and none of the count or field-name properties

#### Scenario: Solr rejects the payload
- **WHEN** Solr rejects an XML payload
- **THEN** the call fails with `isError: true`
