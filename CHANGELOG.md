# Changelog

## [Unreleased]

### Added

- JSON document indexing now accepts a typed array of documents in a single call.

### Changed

- CSV and XML documents are forwarded directly to Solr's own update handlers instead of being parsed by the server.

### Fixed

- Configuring `solr.url` to an address SolrJ cannot reach now fails fast at startup instead of failing on first search.
- Native-image STDIO startup no longer risks corrupting the MCP protocol stream with stray logging output.

### Security

[Unreleased]: https://github.com/apache/solr-mcp/commits
