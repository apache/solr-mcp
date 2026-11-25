# Spec Delta

## Purpose

Defines the contracts for reading a collection's schema (`get-schema`, `list-fields`, and the `solr://{collection}/schema` and `solr://{collection}/fields` resources) and for adding to it (`add-fields`, `add-field-types`).

## ADDED Requirements

### Requirement: The schema is reported as typed fields, field types and copy fields
`get-schema` SHALL return:
- `name`, `version` and `uniqueKey` (when Solr reports them).
- `fields` and `dynamicFields`, lists of field objects.
- `fieldTypes`, a list of field-type objects.
- `copyFields`, a list of `{source, dest, maxChars?}`.

#### Scenario: Reading a schema
- **WHEN** a client calls `get-schema` for `books`
- **THEN** the result has `uniqueKey` and non-empty `fields` and `fieldTypes` lists, and no Solr `responseHeader`

### Requirement: Field properties are declared booleans, keeping explicit false
A field or dynamic-field object SHALL have `name`, `type` and, only for the properties the schema declares, a boolean for each of `indexed`, `stored`, `docValues`, `multiValued`, `required`, `uninvertible`, `omitNorms`, `omitTermFreqAndPositions`, `omitPositions`, `termVectors`, `termPositions`, `termOffsets`, `termPayloads`, `sortMissingFirst`, `sortMissingLast`, `useDocValuesAsStored` and `large`. It also has `default` when set.

#### Scenario: Explicit false is preserved
- **WHEN** a field is declared with `stored="false"` and does not declare `indexed`
- **THEN** its object has `stored: false` and no `indexed` property

#### Scenario: Attributes outside the contract are not passed on
- **WHEN** the schema declares a field attribute that is not one of the listed properties
- **THEN** the field object does not contain it

### Requirement: Field types have a class, declared properties, analyzers and class parameters
A field-type object SHALL have:
- `name` and `className`.
- The same declared boolean properties as a field.
- `analyzer`, `indexAnalyzer`, `queryAnalyzer` and `multiTermAnalyzer` when present.
- `parameters`, a string-to-string map of the class-specific settings the schema declares, such as `positionIncrementGap` or `vectorDimension`.

#### Scenario: Text type with an analyzer
- **WHEN** a field type declares `positionIncrementGap="100"` and an analyzer
- **THEN** its object has `parameters.positionIncrementGap` = `"100"` and an `analyzer` object

### Requirement: Analyzers are typed tokenizer and filter chains
An analyzer object SHALL have `charFilters`, `tokenizer` and `filters`. Each component has `name` (Solr's short name) or `className`, plus `parameters` as a string-to-string map.

#### Scenario: Standard analyzer chain
- **WHEN** an analyzer uses tokenizer `standard` and the filter `lowercase`
- **THEN** `tokenizer` is `{"name":"standard","parameters":{}}` and `filters` is `[{"name":"lowercase","parameters":{}}]`

### Requirement: Listed fields are a list with effective properties and document counts
`list-fields` SHALL return `fields` as a list ordered by field name. Each entry has:
- `name` and `type`.
- `flags`, the list of properties in effect for the field, using the property names of the schema contract.
- `dynamicBase` when the field comes from a dynamic field.
- `docs` when Solr reports a document count.

#### Scenario: A field created from a dynamic field
- **WHEN** the index holds `title_s` created from dynamic field `*_s`
- **THEN** its entry has `name` `title_s`, `dynamicBase` `*_s`, and `flags` containing `indexed`

#### Scenario: Effective flags are decoded
- **WHEN** Solr reports a field as indexed, tokenized and stored
- **THEN** `flags` contains `indexed` and `stored`, and Solr's letter encoding of the flags appears nowhere in the result

### Requirement: Schema resources mirror the schema tools
Reading `solr://{collection}/schema` SHALL return the JSON `get-schema` returns for that collection. Reading `solr://{collection}/fields` SHALL return the JSON `list-fields` returns.

#### Scenario: Reading the schema resource
- **WHEN** a client reads `solr://books/schema`
- **THEN** the content text equals the JSON of `get-schema` for `books`

### Requirement: add-fields accepts typed field definitions
`add-fields` SHALL accept `fields` as a list of field objects with the same shape `get-schema` returns. A property left out means "inherit from the field type". It SHALL return `collection` and `addedNames`.

#### Scenario: Adding a field
- **WHEN** a client calls `add-fields` on `books` with `[{"name":"year","type":"pint","docValues":true}]`
- **THEN** the field is added with only `docValues` declared, and the result is `{"collection":"books","addedNames":["year"]}`

#### Scenario: Unknown property
- **WHEN** a field definition contains a property outside the field contract
- **THEN** the call fails with `isError: true` and no field is added

#### Scenario: Round trip
- **WHEN** a client passes a field object taken from one collection's `get-schema` result to `add-fields` on another collection
- **THEN** the other collection gains a field with the same declared properties

### Requirement: add-field-types accepts typed field-type definitions
`add-field-types` SHALL accept `fieldTypes` as a list of field-type objects with the same shape `get-schema` returns. It SHALL return `collection` and `addedNames`.

#### Scenario: Adding a text field type
- **WHEN** a client calls `add-field-types` with a field type that has `className` `solr.TextField` and an `analyzer` with tokenizer `standard`
- **THEN** the type is added and the result lists its name in `addedNames`
