# Spec Delta

## Purpose

Defines the contracts for listing, creating and deleting collection aliases: what `list-aliases`, `create-alias` and `delete-alias` accept and return.

## ADDED Requirements

### Requirement: Aliases are listed as typed alias objects
`list-aliases` SHALL return a JSON array of aliases ordered by alias name. Each element has:
- `name`.
- `collections`, the alias's target collections as a list in Solr's order.
- `properties`, a string-to-string map that is empty when the alias has none.

#### Scenario: Alias with two targets and a property
- **WHEN** alias `tv` points to `shows,shows_typed` and has property `owner` = `search-team`
- **THEN** the array contains `{"name":"tv","collections":["shows","shows_typed"],"properties":{"owner":"search-team"}}`

#### Scenario: No aliases
- **WHEN** the cluster defines no aliases
- **THEN** the result is `[]`

### Requirement: create-alias takes target collections as a list
`create-alias` SHALL accept `name` and `collections`, a non-empty list of collection names. It SHALL NOT accept a comma-joined string.

#### Scenario: Creating an alias
- **WHEN** a client calls `create-alias` with name `tv` and collections `["shows","shows_typed"]`
- **THEN** Solr's alias `tv` points to `shows` and `shows_typed`

#### Scenario: Empty target list
- **WHEN** a client calls `create-alias` with an empty `collections` list
- **THEN** the call fails with `isError: true` and no alias changes

### Requirement: create-alias returns the alias as Solr holds it
`create-alias` SHALL return the alias object, in the shape `list-aliases` uses, as Solr holds it after the call, including properties kept from an existing alias of the same name.

#### Scenario: Re-pointing an alias that has properties
- **WHEN** alias `tv` has property `owner` and a client calls `create-alias` for `tv` with collections `["shows_v2"]`
- **THEN** the result is `{"name":"tv","collections":["shows_v2"],"properties":{"owner":"search-team"}}`

### Requirement: delete-alias returns the deleted alias's name
`delete-alias` SHALL return `{name}` for the alias it removed. Deleting an alias that does not exist SHALL fail the call.

#### Scenario: Deleting an alias
- **WHEN** a client deletes alias `tv`
- **THEN** the result is `{"name":"tv"}` and `list-aliases` no longer contains `tv`

#### Scenario: Unknown alias
- **WHEN** a client deletes alias `nope`, which does not exist
- **THEN** the call fails with `isError: true`
