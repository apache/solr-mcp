# Solr versions as seen through the tools

The server supports Solr 9.4 to 10. No tool reports the Solr version, so ask the user or the operator
if it matters. The tools, parameters and response shapes are the same across the range, except for
the metrics figures below.

## `get-collection-stats`: `cacheStats` and `handlerStats`

These two figures can be `null`, and the rest of the result (index statistics and query timings) is
still filled in. Report them as unavailable, not as a fault. They are `null` when:

- **The Solr is version 10.** Solr 10's metrics endpoint does not return the JSON the server reads,
  so these figures are never available there.
- **The node the server talks to holds no replica of the collection.** The figures are read from
  that node.
- **No cache or handler activity has been recorded yet.** Run a search and ask again.

## Not version-dependent

- `search`, the four `index-*` tools, `get-schema`, `add-fields`, `add-field-types`,
  `create-collection`, and the alias tools behave the same on every supported version.
- The server requires SolrCloud (collection listing uses the Collections API); standalone Solr is not
  supported on any version.

## Version-specific Solr behaviour

How a Solr feature differs between versions (a query parser, a field type, a Schema API command) is
Reference Guide material. Read the guide for the version in use rather than assuming.
