# Spec Delta

## Purpose

Defines what the `search` tool returns: the hit count and paging position, each matching document with its identifier and score, and facet counts per field.

## ADDED Requirements

### Requirement: Search reports totals and paging position
A search result SHALL contain `numFound` (total matches), `start` (offset of the first returned hit) and, when scores were computed, `maxScore`.

#### Scenario: Paged search
- **WHEN** a client searches with `start` 10 and `rows` 5 and 42 documents match
- **THEN** the result has `numFound` 42, `start` 10 and at most 5 `documents`

#### Scenario: No score requested
- **WHEN** a search does not compute scores
- **THEN** the result omits `maxScore`

### Requirement: Each hit has an id, an optional score, and its fields
Every element of `documents` SHALL be an object with `id`, the value of the collection's uniqueKey field as a string; `score` when Solr returned one; and `fields`, the document's other returned fields keyed by field name.

#### Scenario: Collection whose uniqueKey is not named id
- **WHEN** a collection's uniqueKey field is `sku` and a hit has `sku` = `A-1`
- **THEN** the hit's `id` is `A-1` and `fields` does not contain `sku`

#### Scenario: Scores requested
- **WHEN** a client searches with `fields` including `score`
- **THEN** each hit has a numeric `score` and `fields` does not contain `score`

#### Scenario: Document field values keep their types
- **WHEN** a hit has an integer field `year` = 1965 and a multi-valued field `genres`
- **THEN** `fields.year` is the number 1965 and `fields.genres` is a JSON array

#### Scenario: uniqueKey not in the returned fields
- **WHEN** a client restricts `fields` to exclude the uniqueKey field
- **THEN** the server still returns the uniqueKey value as `id`

### Requirement: Facets are a list of fields with ordered value counts
`facets` SHALL be a list with one entry per faceted field. Each entry holds `field` and `values`, and `values` is a list of `{value, count}` in the order Solr returned them.

#### Scenario: Facet on one field
- **WHEN** a client facets on `genre` and Solr returns `drama` 12, `comedy` 7
- **THEN** `facets` is `[{"field":"genre","values":[{"value":"drama","count":12},{"value":"comedy","count":7}]}]`

#### Scenario: No facets requested
- **WHEN** a search requests no facet fields
- **THEN** `facets` is an empty list
