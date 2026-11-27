# Solr versions as seen through the tools

The server supports Solr 9.4 to 10. No tool reports the Solr version, so ask the user or the operator
if it matters. The tools, parameters and result properties are the same across the range, except for
the `list-fields` counts and the `get-collection-stats` figures below.

## `list-fields`: `docs` and multi-shard collections

- Numeric and date point fields and docValues-only fields have no terms, so their `docs` is missing,
  or `0` on Solr 9.11+ and 10.1+. Either value means Solr did not count the field.
- Before Solr 9.11 and 10.1, a collection with more than one shard lists the fields and counts of
  one shard only. A field that appears in only some shards may be missing from the listing.

## `get-collection-stats`

- Before Solr 9.11 and 10.1, `index.numDocs` and `index.segmentCount` of a collection with more
  than one shard are one shard's.
- `caches` and `handlers` describe the one core named in `metricsCore`. All three can be left out of
  the result while `index` is still filled in. Report them as unavailable; their absence is
  expected. They are left out when:
  - **The Solr is version 10.** The server cannot read these figures from Solr 10, so they are
    never available there.
  - **The node the server talks to holds no replica of the collection.** The figures are read from
    that node.

## Not version-dependent

- `search`, the four `index-*` tools, `get-schema`, `add-fields`, `add-field-types`,
  `create-collection`, and the alias tools behave the same on every supported version.
- The server requires SolrCloud (collection listing uses the Collections API); standalone Solr is not
  supported on any version.

## Version-specific Solr behaviour

How a Solr feature differs between versions (a query parser, a field type, a Schema API command) is
Reference Guide material. Read the guide for the version in use rather than assuming.
