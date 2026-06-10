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

### Approach — plugin-generated appendix

Use the `com.github.jk1.dependency-license-report` Gradle plugin to generate the
third-party dependency appendix automatically, and gate the build so newly added
dependencies cannot slip in un-reviewed.

1. **Version catalog + plugin.** Add `licensereport` to `[plugins]` in
   `gradle/libs.versions.toml` and apply it in `build.gradle.kts`.
2. **Appendix renderer.** Configure `licenseReport` with a text renderer that emits an
   ASF-style appendix — one line per runtime dependency: `group:artifact:version —
   <license name> — <license url>`. Restrict the configuration to the runtime classpath
   (what actually ships in the fat jar), excluding test/compile-only deps.
3. **Binary `LICENSE`.** Define a build step that concatenates the base Apache-2.0
   `LICENSE` + a separator + the generated appendix into
   `build/generated/license/LICENSE` (the *binary* LICENSE). Bundle this file into the
   **`bootJar`** `META-INF/` only. The plain `jar`, sources jar, and javadoc jar keep
   the source-form base `LICENSE` (they are not fat).
4. **Binary `NOTICE`.** Maintain a checked-in `src/dist/NOTICE-binary` (base NOTICE +
   lifted snippets for the handful of bundled ASF deps). Bundle it into the `bootJar`
   `META-INF/`. The license-report output is the authoritative list of which ASF deps
   are present, so the snippet set is kept honest against it.
5. **Build gate.** Wire `checkLicense` (jk1) with an `config/allowed-licenses.json`
   into `check`/`build`. A dependency whose license is not in the allow-list fails the
   build — this is JanHoy's "check task that verifies newly added deps are mentioned".
6. **Verify.** `./gradlew build`, then
   `unzip -p build/libs/solr-mcp-*.jar META-INF/LICENSE | tail` and `... META-INF/NOTICE`
   to confirm the appendix and snippets are present in the fat jar.

### Out of scope (YAGNI)

- No Solr-style per-dependency `licenses/` folder — JanHoy explicitly said the rigid
  version is unnecessary.
- No tgz packaging — the project does not ship one.
- The source-form `LICENSE`/`NOTICE` from PR #138 are already correct and unchanged.

### Files touched

- `gradle/libs.versions.toml` — add plugin coordinate.
- `build.gradle.kts` — apply plugin, configure renderer, generate binary LICENSE,
  per-jar `metaInf` wiring, `checkLicense` config + hook into `check`.
- `config/allowed-licenses.json` — allow-list (new).
- `src/dist/NOTICE-binary` — binary NOTICE with ASF snippets (new).
- `CLAUDE.md` — short note on the LICENSE/NOTICE strategy.

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
