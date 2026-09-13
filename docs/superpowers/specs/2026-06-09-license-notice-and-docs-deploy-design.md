# Design: Binary-release LICENSE/NOTICE tooling + docs-in-code-repo deploy

Date: 2026-06-09
Author: adityamparikh (with Claude)
Addresses:
- apache/solr-mcp#138 review comment [`r3361858982`](https://github.com/apache/solr-mcp/pull/138#discussion_r3361858982) + follow-up `r3383830232`
- apache/solr-site#175 comment [`4664039372`](https://github.com/apache/solr-site/pull/175#issuecomment-4664039372)

These are two independent deliverables tracked in one spec because they were raised together.

---

## Deliverable 1 — Binary-release LICENSE/NOTICE (solr-mcp)

### Problem

PR #138 adds the base Apache-2.0 `LICENSE` and a minimal `NOTICE`, and bundles them
into the `META-INF/` of every jar. Per
[infra.apache.org/licensing-howto](https://infra.apache.org/licensing-howto.html):

- For the **source release**, those base files are correct — the source tree contains
  only ASF-authored code under Apache-2.0.
- For a **binary release**, the artifact bundles third-party bytecode, so:
  - `LICENSE` must additionally enumerate every bundled non-Apache dependency and
    point to its license (a link, not the full license text).
  - `NOTICE` must lift the relevant NOTICE snippets of bundled **Apache-licensed**
    dependencies (notably other ASF projects such as SolrJ).

The project's binary artifact is the Spring Boot fat jar (`bootJar`) — it embeds every
runtime dependency. There is no tgz/`distZip`/`installDist` packaging, so "binary
release" == the `bootJar`. (An SBOM was added on a separate branch; per JanHoy it is
complementary, not a substitute for LICENSE/NOTICE.)

### Approach — SBOM-driven, in a buildSrc convention plugin

> **As shipped (PR #138).** An earlier draft generated the appendix with the
> `com.github.jk1.dependency-license-report` plugin plus a hand-kept supplement for the
> Gradle-module-metadata-only ASF artifacts (SolrJ) that the POM-only plugin silently
> drops. We instead **derive the appendix from the CycloneDX SBOM** (PR #142), which
> already resolves a license for every component including SolrJ — so no supplement is
> needed and there is a single source of dependency data. PR #138 is therefore stacked
> on #142.

Implemented as the `org.apache.solr.mcp.license-notice` convention plugin under
`buildSrc/`, with two typed tasks. The root `build.gradle.kts` only applies the plugin.

1. **`GenerateBinaryLicense`.** Reads the CycloneDX SBOM
   (`build/reports/application.cdx.json`, the same SBOM embedded in the bootJar at
   `META-INF/sbom/application.cdx.json`), indexes each component's licenses by
   `group:name(:version)`, and emits the binary `LICENSE` = base Apache-2.0 + an appendix
   of every shipped dependency and a link to its license. "Shipped" = the resolved
   `productionRuntimeClasspath` (excludes test/compile-only and `developmentOnly` deps).
2. **`GenerateBinaryNotice`.** Builds the binary `NOTICE` = base NOTICE + the
   `META-INF/NOTICE` files lifted verbatim and de-duplicated from the bundled jars (the
   Maven-Shade `ApacheNoticeResourceTransformer` approach), so ASF dependency notices are
   carried and stay current with no hand-maintained snippets.
3. **`metaInf` wiring.** The `bootJar` bundles the generated LICENSE/NOTICE; the plain
   `jar`, sources, and javadoc jars keep the source-form base files.
4. **Licenses are disclosed as-reported.** The appendix is a disclosure, not a license
   policy. Licenses are listed exactly as the SBOM declares them — **no allow-list and no
   corrections** — so a few imprecise-but-permissive upstream labels appear as-is
   (`mcp-server-security` → `Apache-1.0`; ANTLR `ST4`/`antlr-runtime` →
   `BSD-4-Clause`/`BSD licence`); the preamble notes this and links each license. apache/solr
   itself keeps no allow-list (it uses a per-dependency `solr/licenses/` folder, which
   JanHoy said not to replicate), so neither do we.
5. **Completeness gate.** The only gate: `generateBinaryLicense` runs as part of
   `check`/`build` and **fails if a shipped dependency is absent from the SBOM**, so a
   dependency can never be silently omitted from the LICENSE. This is JanHoy's "check that
   newly added deps are accounted for"; it makes no judgement about license acceptability.
6. **Tests.** The two tasks are unit-tested with `ProjectBuilder`
   (`buildSrc/src/test/kotlin/.../LicenseNoticeTasksTest.kt`): appendix listing, SBOM
   name/URL handling, the completeness gate, and NOTICE de-duplication. `buildSrc`'s
   `test` runs in `./gradlew build`.
6. **Verify.** `./gradlew build`, then
   `unzip -p build/libs/solr-mcp-<v>.jar META-INF/LICENSE` / `... META-INF/NOTICE`
   to confirm the appendix and lifted notices are present in the fat jar.

### Out of scope (YAGNI)

- No Solr-style per-dependency `licenses/` folder — JanHoy explicitly said the rigid
  version is unnecessary.
- No tgz packaging — the project does not ship one; "binary release" == the `bootJar`.
- The source-form `LICENSE`/`NOTICE` are already correct and unchanged.

### Files touched

- `buildSrc/` — the `org.apache.solr.mcp.license-notice` convention plugin and the
  `GenerateBinaryLicense` / `GenerateBinaryNotice` typed tasks (+ their unit tests).
- `build.gradle.kts` — applies `id("org.apache.solr.mcp.license-notice")` (after the
  Spring Boot + CycloneDX plugins).
- `AGENTS.md` — "Release LICENSE / NOTICE" section.
- Depends on PR #142 (CycloneDX SBOM) for the `cyclonedxBom` task and plugin.

---

## Deliverable 2 — Docs source in code repo, deployed to site (solr-mcp + solr-site)

### Problem

JanHoy wants the MCP docs to live in the **solr-mcp** repo (so every feature PR carries
its documentation update) but still be **deployed to the Solr site**. The Solr ref guide
does this by building the static site elsewhere and copying the output into a location
mounted into the web server via `.htaccess`.

### Constraint discovered

solr-site is a **Pelican** site (`pelican content -o output`, published via ASF
`.asf.yaml` — `main`/`production` protected branches, `asf-staging`). PR #175 splits
cleanly into two layers:

- **Content** (the docs *source*): `content/pages/mcp/*.md`,
  `content/pages/mcp/clients/*.md`, `content/doap/solr-mcp.rdf` — plain markdown with
  Pelican front-matter.
- **Presentation** (the site's rendering layer): `themes/solr/templates/mcp/*.html`,
  `themes/solr/static/css/mcp.css`, `pelicanconf.py`, shared header/index edits.

The content reuses the solr-site theme. A fully standalone build inside solr-mcp would
have to vendor that theme and drift from the rest of the site.

### Approach — solr-site pulls content from solr-mcp at build time

Keep one themed Pelican build in solr-site; make solr-mcp the source of truth for the
**content** layer only.

1. **solr-mcp** owns the markdown. Move the content layer into
   `solr-mcp/docs/site/content/` (markdown + DOAP). Feature PRs edit docs here alongside
   code. A `docs/site/README.md` explains that these files are assembled into the Solr
   site at build time.
2. **solr-site** keeps the presentation layer (templates, CSS, `pelicanconf.py`).
3. **Assembly at build time.** solr-site's `build.sh` (and the CI build) fetches the
   `docs/site/content/` tree from solr-mcp at a **pinned ref** (a release tag, falling
   back to `main`) and copies it into `content/pages/mcp/` (and the DOAP into
   `content/doap/`) before running Pelican. Implemented as a `fetch_mcp_docs` step:
   shallow `git clone --depth 1 --branch <ref>` of solr-mcp into a temp dir, `rsync`
   the content into place. The pinned ref lives in one variable in `build.sh` /
   `pelicanconf.py` so bumping the published docs version is a one-line change.
4. **Remove** the moved markdown from solr-site so there is a single source of truth;
   leave a short note in solr-site explaining where MCP content now comes from.

This mirrors the ref-guide model (content built/owned elsewhere, assembled into the
site) while keeping the shared theme and a single Pelican build.

### Rejected alternative

**solr-mcp pushes built static HTML** into a `/mcp/` subdir of solr-site's `production`
branch via a release Action. Closest literal match to the `.htaccess`-mounted ref-guide
output, but requires vendoring the Pelican theme in solr-mcp (drift) and a cross-repo
push token. Rejected in favor of the pull model.

### Files touched

- **solr-mcp:** add `docs/site/content/**` (moved markdown + DOAP), `docs/site/README.md`.
- **solr-site:** `build.sh` (+ CI workflow) gains a `fetch_mcp_docs` step; remove
  `content/pages/mcp/**` and `content/doap/solr-mcp.rdf`; keep theme/templates/CSS/
  `pelicanconf.py`; add a note documenting the source-of-truth.

### Sequencing / review

This spans two repos and changes how an in-review PR (#175) is structured, so the
mechanism is proposed to JanHoy (reply on #175) and the cross-repo changes are staged on
branches for review — nothing is pushed to the public PRs without maintainer sign-off.
