# Solr MCP documentation site content

This directory is the **source of truth** for the Apache Solr MCP documentation that
is published on the Solr website at <https://solr.apache.org/mcp/>.

Keeping the content here means every feature PR can update its documentation alongside
the code change, in the same review.

## What lives here

```
docs/site/content/
├── pages/mcp/**        Markdown pages (one file per published page)
└── doap/solr-mcp.rdf   DOAP project descriptor
```

These are plain [Pelican](https://getpelican.com/) content files. Each page carries
front-matter that selects a template, e.g.:

```
Title: Quick Start
URL: mcp/quick-start.html
save_as: mcp/quick-start.html
template: mcp/quick-start
```

## How it gets published (assembly at build time)

The **presentation layer** (Pelican templates, theme, CSS, `pelicanconf.py`) lives in
the [`apache/solr-site`](https://github.com/apache/solr-site) repository, not here, so
the MCP pages share the rest of the Solr site's look and feel.

At site-build time, `solr-site` fetches this directory at a pinned ref and copies it
into its Pelican `content/` tree before running `pelican content -o output`:

- `content/pages/mcp/**` ← `docs/site/content/pages/mcp/**`
- `content/doap/solr-mcp.rdf` ← `docs/site/content/doap/solr-mcp.rdf`

This is wired in `solr-site`'s `build.sh` (local preview) and its Pelican GitHub
Actions workflows (`build-pelican.yml`, `pr-build-pelican.yml`). The published URLs are
unchanged from when the content lived in `solr-site` directly.

## Editing and previewing

- **Edit** the Markdown here; that is all most doc changes require.
- **Preview** with the full Solr theme by running `solr-site`'s `./build.sh -l` with a
  sibling `solr-mcp` checkout — the build picks up `../solr-mcp/docs/site/content`
  automatically when present, otherwise it clones the pinned ref.

## Why content here, theme there

A standalone build in this repo would have to vendor the Solr Pelican theme and would
drift from the rest of the site. Splitting **content (here)** from **presentation
(`solr-site`)** keeps a single themed build while letting documentation travel with the
code — mirroring how the Solr Reference Guide is assembled into the site.
