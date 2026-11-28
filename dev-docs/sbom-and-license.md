# SBOM, LICENSE and NOTICE

How the build produces the CycloneDX SBOM and the binary-form LICENSE/NOTICE derived from
it. `AGENTS.md` keeps the rules; this file has the reasoning and history behind them. For
generating, locating and scanning the SBOM as a user, see the SBOM sections of
[DEVELOPMENT.md](DEVELOPMENT.md).

## SBOM

CycloneDX SBOM generation is wired by applying the `org.cyclonedx.bom` plugin,
version **3.4.1**. Spring Boot 4's `CyclonedxPluginAction` recognises 3.x and
auto-configures the `cyclonedxBom` task (type `org.cyclonedx.gradle.CyclonedxAggregateTask`):
it writes `build/reports/cyclonedx/application.cdx.json`, embeds that in the bootJar at
`META-INF/sbom/application.cdx.json`, and sets the `Sbom-Format` / `Sbom-Location`
manifest headers the actuator needs. The actuator then serves it at
`/actuator/sbom/application` in the `http` profile (enabled via
`application-http.properties`). Both the Jib JVM image and the Paketo native images
package the bootJar contents, so every distribution artifact ships the SBOM without
per-image wiring.

What the build still configures by hand is **scope**. cyclonedx 3.x splits the work
across two tasks: `cyclonedxDirectBom` (`CyclonedxDirectTask`) resolves the dependency
graph and owns `includeConfigs`, while `cyclonedxBom` only aggregates its output. Left
at defaults the direct task scans every configuration, which adds ~100 test/build-only
components (JUnit, AssertJ, ByteBuddy, docker-java, JaCoCo, Error Prone, NullAway) that
are not in the fat jar. `build.gradle.kts` therefore sets
`includeConfigs = [productionRuntimeClasspath]` **on `cyclonedxDirectBom`**, giving an
SBOM that lists exactly the shipped classpath `generateBinaryLicense`'s completeness gate
checks against.

> **Do not drop that scoping.** Nothing in the build would catch it: the LICENSE appendix
> filters to shipped coordinates, and the completeness gate only fails on *missing*
> entries, never extra ones. The over-broad SBOM would ship silently and register as
> false-positive CVEs in scanners that read it as a manifest of the artifact's contents.

Historical note: this used to pin cyclonedx to **2.4.1**, because 3.x once failed at
configuration time on Gradle 9.4.1 (a variant-mutation conflict on `:cyclonedxDirectBom`).
That is fixed as of 3.4.1. The pin had a cost that was not obvious: because Spring Boot's
action bails on an unrecognised plugin version, and jar-embedding is part of that same
action, **the pinned build shipped no SBOM at all** — no `META-INF/sbom/` entry and no
`Sbom-*` manifest headers in the bootJar, hence nothing for `/actuator/sbom/application`
to serve. `./gradlew cyclonedxBom` still produced a report under `build/reports/`, which
is why this went unnoticed. Resolved along with
[#186](https://github.com/apache/solr-mcp/issues/186).

## Release LICENSE / NOTICE

ASF policy requires distinct LICENSE/NOTICE for the *source* form and the *binary*
form, because the binary (Spring Boot fat `bootJar`) bundles third-party bytecode.
See [infra.apache.org/licensing-howto](https://infra.apache.org/licensing-howto.html).

- **Source form** (thin `jar`, `-sources`, `-javadoc`): the base `LICENSE` (Apache-2.0)
  and `NOTICE` at the repo root, bundled into `META-INF/` as-is.
- **Binary form** (`bootJar`): generated at build time and bundled into its `META-INF/`:
  - `generateBinaryLicense` → `LICENSE` = base Apache-2.0 + an appendix listing every
    bundled `productionRuntimeClasspath` dependency and a link to its license. Licenses
    are read from the **CycloneDX SBOM** (`cyclonedxBom`, the same SBOM embedded at
    `META-INF/sbom/application.cdx.json`), filtered to the shipped classpath. The SBOM
    resolves a license for every bundled component — including Gradle-module-metadata
    -only ASF artifacts such as `solr-solrj`/`solr-api` that POM-only scanners miss — so
    no per-dependency list is hand-maintained.
  - `generateBinaryNotice` → `NOTICE` = base NOTICE + the `META-INF/NOTICE` files lifted
    verbatim (de-duplicated) from the bundled jars (Maven-Shade
    `ApacheNoticeResourceTransformer` approach).
- **Where / when they appear:** both binary files are regenerated on every build — the
  two tasks run ahead of `bootJar` (and in `check`), so any `./gradlew build` / `bootJar`
  produces them. They live at `META-INF/LICENSE` and `META-INF/NOTICE` inside the fat jar
  (`build/libs/solr-mcp-<v>.jar`), and therefore inside every published **Docker image**
  too, since the Jib JVM image and the Paketo native images both package the bootJar
  contents. Inspect a built artifact with
  `unzip -p build/libs/solr-mcp-<v>.jar META-INF/LICENSE` (or `META-INF/NOTICE`); the
  generator also writes them to `build/generated/license/` for local viewing. The
  source-form jars (thin `jar`, `-sources`, `-javadoc`) instead carry the repo-root base
  files unchanged.
- **Licenses are disclosed as the SBOM reports them** (SPDX ids where available). The
  appendix is a disclosure, not a license policy: there is **no allow-list and no
  corrections**, so a few imprecise-but-permissive upstream labels appear as-is (e.g.
  `mcp-server-security` shows `Apache-1.0`, ANTLR shows `BSD-4-Clause`/`BSD licence`); the
  appendix preamble says so and links each license. All bundled deps are ASF Category A/B.
- **Completeness gate** (`generateBinaryLicense`, run as part of `check`/`build`): the
  *only* gate — fails if a bundled dependency is missing from the SBOM, so a dependency
  can never be silently omitted from the LICENSE. It makes no judgement about which
  licenses are acceptable. (Unlike apache/solr's `solr/licenses/` folder, which JanHoy
  said not to replicate, there is no per-dependency license/checksum store here.)
- This builds on the SBOM generation (see [SBOM](#sbom) above); the SBOM remains the
  machine-readable bill of materials, and LICENSE/NOTICE are the human-readable legal
  artifacts derived from it.
- **Implementation:** the `org.apache.solr.mcp.license-notice` convention plugin in
  `buildSrc/` (typed `GenerateBinaryLicense` / `GenerateBinaryNotice` tasks). The root
  `build.gradle.kts` only applies the plugin. The tasks are unit-tested in
  `buildSrc/src/test/kotlin/.../LicenseNoticeTasksTest.kt` (appendix listing, SBOM
  name/URL handling, the completeness gate, and NOTICE de-duplication); `buildSrc`'s
  `test` runs as part of `./gradlew build`, because `buildSrc/build.gradle.kts` finalizes its
  `jar` with `test` (Gradle 8+ otherwise builds only the jar the root build needs).
