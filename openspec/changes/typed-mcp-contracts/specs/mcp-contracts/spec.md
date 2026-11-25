# Spec Delta

## Purpose

Defines the contract rules every MCP tool, resource, prompt and completion of the Solr MCP server follows. Clients see stable, typed shapes owned by the server rather than Solr's own response formats.

## ADDED Requirements

### Requirement: Tools that return an object publish an output schema
Every tool whose result is a JSON object SHALL publish an `outputSchema` in `tools/list`. Every successful call SHALL return `structuredContent` that conforms to it. A tool whose result is a JSON array SHALL NOT publish one, because MCP 2025-11-25 restricts an `outputSchema` to an object root; it returns the array as JSON text.

#### Scenario: Listing tools shows output schemas
- **WHEN** a client calls `tools/list`
- **THEN** every tool that returns an object has a non-empty `outputSchema` of type `object`, and `list-aliases` and `list-collections`, which return arrays, have none

#### Scenario: A call's structured content matches its schema
- **WHEN** a client calls any tool that publishes an `outputSchema`, with valid arguments, and the call succeeds
- **THEN** the result's `structuredContent` validates against that schema

#### Scenario: Optional values that are absent still validate
- **WHEN** a tool result has a property with no value, such as a search with no `maxScore`
- **THEN** the property is omitted and the result still validates, because the schema does not list that property as required

### Requirement: Tool results keep a text copy for text-only clients
Every successful call of a tool that publishes an `outputSchema` SHALL also return, in `content`, a text block holding the same JSON as `structuredContent`.

#### Scenario: A text-only client reads the result
- **WHEN** a client that ignores `structuredContent` calls any tool that publishes an `outputSchema`
- **THEN** the first `content` item is a text block whose text parses to JSON equal to `structuredContent`

### Requirement: Contracts are defined by the server, not by Solr
Tool inputs and outputs SHALL use only property names and nesting the server defines. They SHALL NOT expose Solr's wire formats: no response headers, no Solr-internal flag encodings, no comma-joined lists. User-defined content is the exception: document fields, and the string `parameters` of field types and analyzer components, whose names depend on the class the user chose.

#### Scenario: A Solr upgrade adds a response property
- **WHEN** a newer Solr version adds a property to a response the server reads
- **THEN** the tool's output is unchanged until the server's contract adds a field for it

#### Scenario: No pass-through properties
- **WHEN** any tool returns a result
- **THEN** every property in its `structuredContent` is declared in the tool's `outputSchema`, except the keys of the `fields` map of search hits and of the `parameters` maps of field types and analyzer components

### Requirement: Failures are reported only as tool errors
A tool that cannot do what was asked SHALL fail the call, so the client receives a result with `isError: true`. Successful results SHALL NOT carry `success`, `message` or similar status properties.

#### Scenario: A mutation fails in Solr
- **WHEN** `create-collection` is called and Solr rejects the request
- **THEN** the client receives `isError: true` and no result object

#### Scenario: A successful mutation has no status flag
- **WHEN** any tool that changes Solr state succeeds
- **THEN** its result has no `success` or `message` property

### Requirement: Results carry no server-clock timestamps
Results SHALL NOT include timestamps taken from the MCP server's clock. A time value appears only when Solr reports it.

#### Scenario: Creating a collection
- **WHEN** `create-collection` succeeds
- **THEN** the result has no creation-time property

### Requirement: Error text does not carry Solr internals
Error text and result properties that describe a failure SHALL NOT include Solr's or SolrJ's exception messages, which can quote host names, URLs, paths and credentials.

#### Scenario: A health check fails
- **WHEN** `check-health` is called on a collection whose ping fails
- **THEN** the result's `reason` is one of the server's fixed phrases, and the underlying exception text appears only in the server log

### Requirement: Prompts return typed prompt results
Every prompt SHALL return a prompt result whose `description` is set, with its instructions as one or more messages that each carry an explicit role.

#### Scenario: Getting a prompt
- **WHEN** a client calls `prompts/get` for any of the server's prompts with valid arguments
- **THEN** the result has a non-empty `description` and at least one message with role `user` and text content

### Requirement: Resources return JSON resource contents
Every resource read SHALL return contents with the requested `uri`, `mimeType` `application/json`, and text that is the JSON of the matching tool's result.

#### Scenario: Reading the collections resource
- **WHEN** a client reads `solr://collections`
- **THEN** the single content item has `uri` `solr://collections`, `mimeType` `application/json`, and text equal to the JSON array `list-collections` returns

### Requirement: Completions report totals and truncation
Every completion SHALL return its values together with the total number of matches and whether more matches exist than were returned.

#### Scenario: More matches than the cap
- **WHEN** a client asks to complete a collection name and more than 100 collections match the prefix
- **THEN** the result holds 100 values, `total` equals the number of matches, and `hasMore` is true

#### Scenario: All matches returned
- **WHEN** fewer than 100 collections match the prefix
- **THEN** `total` equals the number of values returned and `hasMore` is false
