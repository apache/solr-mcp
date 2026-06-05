# SBOM generation — design

**Status:** draft, pending user approval
**Branch:** `worktree-add-sbom-generation`

## Problem

The Solr MCP server ships as a JAR, a Jib JVM Docker image, and two Paketo native
images, but produces no Software Bill of Materials. Downstream consumers — Apache
release reviewers, supply-chain scanners (Trivy, Grype, Dependency-Track),
container-registry attestation tooling — have no machine-readable inventory of
what dependencies ship inside the binary. SBOM coverage is increasingly an
Apache release-policy expectation and a precondition for SLSA / CycloneDX VEX
workflows downstream.

Curiously, `application-http.properties` already lists `sbom` in
`management.endpoints.web.exposure.include`. The endpoint config is half-wired
already; today the actuator returns 404 because no SBOM is generated.

## Scope

In scope:

- Generate a CycloneDX 1.5 SBOM (`application.cdx.json`) on every `./gradlew build`.
- Embed the SBOM in the bootable JAR at `META-INF/sbom/application.cdx.json` so
  it ships with every distribution (JAR, Jib JVM image, both Paketo native
  images).
- Expose `GET /actuator/sbom` in the HTTP profile (config already partially in
  place; finish the wiring).
- Attach the SBOM as a release artifact in `build-and-publish.yml` and
  `release-publish.yml` (workflow artifact + GitHub Release asset).
- Document the SBOM in `README.md` (location, endpoint, scanning) and in
  `CLAUDE.md` (build-system and native-image notes).
- One small HTTP integration-test assertion that `/actuator/sbom` returns
  200 + a CycloneDX-shaped body.

Out of scope (intentional, can be follow-ups):

- SPDX format alongside CycloneDX — the plugin supports
  `outputFormat = "all"`; can layer on without redesign.
- Cosign / SLSA provenance signing — separate concern; would add another moving
  part to maintain.
- Dependency-Track upload from CI — requires an externally-hosted server.
- SBOM for transitive native-image runtime libraries that GraalVM links in —
  the CycloneDX plugin reports Gradle dependencies, which already covers what
  ends up in the binary.

## Tool choice: CycloneDX Gradle plugin

The project is on Spring Boot 3.5.14, which has first-class CycloneDX
integration since 3.3.0:

- Applying `org.cyclonedx.bom` makes the Spring Boot Gradle plugin automatically
  embed the generated `application.cdx.json` into the bootable JAR at
  `META-INF/sbom/application.cdx.json`.
- Spring Boot's actuator auto-discovers that resource and serves it at
  `/actuator/sbom` (CycloneDX-format) when the endpoint is exposed.
- The Jib JVM image and both Paketo native images package the bootJar contents,
  so the SBOM ships with every artifact for free — no per-image wiring.

CycloneDX (vs SPDX) is the de-facto Apache ecosystem standard, what Spring Boot
natively integrates with, and what Trivy/Grype/Dependency-Track ingest natively.

Plugin version: `1.10.0` (latest stable as of 2026-06; supports Gradle 8+ and
CycloneDX 1.5).

## Architecture

### Build wiring

```
gradle/libs.versions.toml             ← new version key + plugin alias
build.gradle.kts                      ← apply alias(libs.plugins.cyclonedx)
                                      ← cyclonedxBom { … } configuration block
```

`cyclonedxBom` configuration:

- `outputFormat = "json"` — Spring Boot's actuator only consumes JSON; XML adds
  build cost and disk for no consumer.
- `outputName = "application.cdx"` — Spring Boot expects exactly this name to
  embed it. (Default is `bom`, which Spring Boot would not detect.)
- `includeConfigs = listOf("runtimeClasspath")` — only ship what's actually in
  the binary; exclude test/errorprone/build-time-only deps.
- `skipConfigs = listOf("testRuntimeClasspath", "errorprone")` — defense in depth.
- `schemaVersion = "1.5"` — current stable; matches Spring Boot's expectations.
- `projectType = "application"` — accurate for a Spring Boot service.

`build` and `bootJar` automatically depend on `cyclonedxBom` once the Spring
Boot plugin sees it on the classpath; no manual `dependsOn` needed.

### Runtime wiring

`application-http.properties` already exposes `sbom` via
`management.endpoints.web.exposure.include`. The remaining work:

- Add `management.endpoint.sbom.enabled=true` (explicit, even though it
  defaults true, because the project's convention is to be explicit about
  endpoint enablement for the LGTM stack to discover).
- No change to `application-stdio.properties` — actuator HTTP endpoints don't
  apply in stdio mode.

### CI wiring

`build-and-publish.yml`: after `./gradlew build`, add an `actions/upload-artifact`
step that uploads `build/reports/application.cdx.json`. Retained 30 days
(default), accessible from the run page.

`release-publish.yml`: same upload step, plus `gh release upload <tag>
build/reports/application.cdx.json`. The SBOM appears alongside source tarballs
on the GitHub Release page.

`native.yml`: no change. The native-image build inherits the SBOM via the bootJar
input.

### Documentation

`README.md`: new "## Supply chain & SBOM" section near the bottom, covering:

- Where the SBOM lives (`META-INF/sbom/application.cdx.json` inside every JAR
  and image).
- How to fetch it from a running server: `curl http://localhost:8080/actuator/sbom`.
- How to extract it from a Docker image:
  `docker run --rm --entrypoint cat solr-mcp:latest /workspace/META-INF/sbom/application.cdx.json`
  (Jib path) or via the release asset.
- How to scan: `trivy sbom application.cdx.json` and
  `grype sbom:application.cdx.json` examples.

`CLAUDE.md`: brief note in the build-system section that CycloneDX is wired and
the SBOM ships embedded; reference the spec.

## Testing

- `./gradlew build` produces `build/reports/application.cdx.json`. Verify
  manually post-merge.
- Add a focused HTTP-profile test (or extend `McpClientIntegrationTest`, which
  already boots the HTTP profile) with an assertion that
  `GET /actuator/sbom` returns 200, `Content-Type: application/vnd.cyclonedx+json`,
  and the JSON body contains `"bomFormat": "CycloneDX"`. One small assertion
  — no separate test class.
- Existing Docker integration tests already verify image startup. The SBOM
  being present in the image is implicit via the bootJar packaging — no new
  Docker test added.

## Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| Plugin adds significant build time | CycloneDX plugin runs once at JAR-assembly, typically <2s on this dependency graph. Measure before/after; report in PR. |
| Native-image build fails because of SBOM resource | Spring Boot already registers `META-INF/sbom/*` as a runtime resource hint; the existing native build should work unchanged. Verify with `./gradlew nativeCompile -Pnative` post-merge. |
| Actuator endpoint leaks info in production | SBOM contents are public (every dependency name + version is already in the JAR's manifest). Endpoint exposure is opt-in by being in the explicit `include` list. Documented. |
| Plugin version drift | Pinned in `libs.versions.toml`; Renovate / Dependabot will surface upgrades on schedule. |

## Acceptance criteria

1. `./gradlew build` produces `build/reports/application.cdx.json` with
   `bomFormat: CycloneDX`, `specVersion: 1.5`.
2. `./gradlew bootJar` produces a JAR containing
   `META-INF/sbom/application.cdx.json`.
3. `GET /actuator/sbom` returns 200 + valid CycloneDX JSON in HTTP profile.
4. `build-and-publish.yml` uploads the SBOM as a workflow artifact.
5. `release-publish.yml` attaches the SBOM to the GitHub Release.
6. `README.md` documents the SBOM under a clearly named section.
7. `./gradlew spotlessCheck build` is green.