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
- `fieldNames`: the field names of the documents Solr accepted, at most 50 in sort order.
- `fieldCount`: how many distinct field names those documents have, so a client can tell when `fieldNames` was cut short.

#### Scenario: Every document accepted
- **WHEN** a client indexes 3 JSON documents and Solr accepts all of them
- **THEN** `documentsSubmitted` is 3 and `documentsIndexed` is 3

#### Scenario: Some documents rejected
- **WHEN** a client indexes 5 JSON documents and Solr rejects 2 of them individually
- **THEN** `documentsSubmitted` is 5 and `documentsIndexed` is 3

#### Scenario: Field names come from the accepted documents
- **WHEN** 2 of 5 JSON documents are rejected, and only the rejected ones have a field `colour`
- **THEN** `fieldNames` does not contain `colour`

#### Scenario: More than 50 field names
- **WHEN** the accepted documents have 60 distinct field names
- **THEN** `fieldNames` holds the first 50 in sort order and `fieldCount` is 60

#### Scenario: Every document rejected
- **WHEN** Solr rejects every document of a JSON or Markdown input
- **THEN** the call fails with `isError: true`

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
