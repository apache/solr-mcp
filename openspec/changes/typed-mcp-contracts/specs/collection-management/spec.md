# Spec Delta

## Purpose

Defines the contracts for listing collections (`list-collections` and the `solr://collections` resource), reading their metrics (`get-collection-stats`), checking their health (`check-health`) and creating them (`create-collection`).

## ADDED Requirements

### Requirement: Collections are listed as an object with a name list
`list-collections` SHALL return an object whose `collections` property is the list of collection names. A cluster with no collections gives an empty list.

#### Scenario: Two collections
- **WHEN** the cluster has collections `books` and `films`
- **THEN** the result is `{"collections":["books","films"]}`

#### Scenario: Empty cluster
- **WHEN** the cluster has no collections
- **THEN** the result is `{"collections":[]}`

### Requirement: Collection metrics report index, query, cache and handler statistics
`get-collection-stats` SHALL return:
- `index`, with `numDocs` and `segmentCount`.
- `query`, the statistics of the probe query.
- `caches` and `handlers`, when the Solr version reports them.

Property names are the server's camelCase names, for example `hitRatio`.

#### Scenario: Solr 9
- **WHEN** a client reads the stats of a collection on Solr 9
- **THEN** the result has `index`, `query`, `caches` and `handlers`, and no timestamp

#### Scenario: Solr 10 without the mbeans endpoint
- **WHEN** a client reads the stats of a collection on Solr 10
- **THEN** the result has `index` and `query` and omits `caches` and `handlers`

### Requirement: Health is reported as a typed verdict
`check-health` SHALL return:
- `collection` and `healthy`.
- When healthy: `pingMillis` and `numDocs`.
- When not healthy: `reason`, one of the server's fixed phrases.

An unhealthy collection is a result, not a tool error.

#### Scenario: Healthy collection
- **WHEN** the ping of `books` succeeds
- **THEN** the result has `healthy: true`, a `pingMillis` value and `numDocs`, and no `reason`

#### Scenario: Unreachable collection
- **WHEN** the ping of `books` fails
- **THEN** the call succeeds with `healthy: false` and a `reason` that contains no host name, URL or Solr exception text

### Requirement: Creating a collection reports the settings used
`create-collection` SHALL return `name`, `configSet`, `numShards` and `replicationFactor`. These are the values the collection was created with, including the defaults the server applied for anything the client left out.

#### Scenario: Defaults applied
- **WHEN** a client creates `books` giving only the name
- **THEN** the result has `name` `books`, `numShards` 1, `replicationFactor` 1, and the configset the server chose

#### Scenario: Creation fails
- **WHEN** Solr rejects the creation, for example because the collection already exists
- **THEN** the call fails with `isError: true`
