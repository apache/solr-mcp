# SBOM generation — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up CycloneDX SBOM generation so every JAR, Docker image, and GitHub release artifact ships a machine-readable Software Bill of Materials, and `/actuator/sbom` serves it at runtime.

**Architecture:** Apply the `org.cyclonedx.bom` Gradle plugin (1.10.0). Spring Boot 3.5's bootJar task auto-detects and embeds `META-INF/sbom/application.cdx.json`; the actuator auto-discovers that resource and serves it at `/actuator/sbom`. Both the Jib JVM image and Paketo native images package the bootJar contents, so SBOM coverage is automatic for every artifact — no per-image wiring. CI workflows upload the SBOM as a workflow artifact and attach it to GitHub Releases.

**Tech Stack:** Gradle Kotlin DSL with `libs.versions.toml`, Spring Boot 3.5.14, CycloneDX Gradle Plugin 1.10.0, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-06-05-sbom-generation-design.md`

---

## Pre-flight context for the implementer

Read these files before starting — they show what's already half-wired:

- `gradle/libs.versions.toml` — version catalog; you'll add a new `cyclonedx-plugin` version key and plugin alias here.
- `build.gradle.kts` — main build script; you'll add `alias(libs.plugins.cyclonedx)` in the `plugins { }` block and add a `cyclonedxBom { … }` configuration block.
- `src/main/resources/application-http.properties` — `sbom` is already listed in `management.endpoints.web.exposure.include` (line near bottom). You'll add one explicit-enablement line.
- `.github/workflows/build-and-publish.yml` — has existing `Upload JAR artifact` step pattern (around line 145); you'll add a parallel SBOM upload step.
- `.github/workflows/release-publish.yml` — already contains a `Generate SBOM (Software Bill of Materials)` step (`./gradlew cyclonedxBom || echo "SBOM generation not configured"`). Today it's a no-op because the plugin isn't applied. You'll remove the `|| echo …` fallback (it would now mask a real failure) and add upload/attach steps after it.
- `src/test/java/org/apache/solr/mcp/server/McpClientIntegrationTest.java` — boots HTTP profile with random port; you'll add a focused test method (or sibling test class) that does an HTTP GET on `/actuator/sbom`.
- `README.md` — sections are `## What's inside`, `## Get started`, `## Security`, `## Available MCP tools`, etc. (see `grep ^## README.md`). Add a new section before `## Documentation` (the last section).
- `CLAUDE.md` (project, at repo root) — has a "Common Commands" section and an architecture section. Add a one-line note in Common Commands and a brief paragraph in the architecture section about SBOM.

---

## File structure

**Modify:**
- `gradle/libs.versions.toml` — add CycloneDX plugin version + alias
- `build.gradle.kts` — apply plugin, add `cyclonedxBom { }` configuration
- `src/main/resources/application-http.properties` — add explicit endpoint enablement line
- `.github/workflows/build-and-publish.yml` — add SBOM upload step in `build` job
- `.github/workflows/release-publish.yml` — fix the existing SBOM step, add upload + GitHub Release attach
- `README.md` — new "## Supply chain & SBOM" section
- `CLAUDE.md` — short note in Common Commands + brief architecture paragraph

**Create:**
- `src/test/java/org/apache/solr/mcp/server/observability/SbomEndpointIntegrationTest.java` — focused HTTP integration test for `/actuator/sbom`

---

## Task 1: Add CycloneDX plugin to the version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add plugin version**

In the `[versions]` block, after the `graalvm-native = "0.10.6"` line, add:

```toml
cyclonedx-plugin = "1.10.0"
```

- [ ] **Step 2: Add plugin alias**

In the `[plugins]` block, at the bottom (after the `graalvm-native = ...` line), add:

```toml
cyclonedx = { id = "org.cyclonedx.bom", version.ref = "cyclonedx-plugin" }
```

- [ ] **Step 3: Verify catalog parses**

Run: `./gradlew help -q`
Expected: succeeds with no output. If it prints `Invalid catalog definition`, fix the syntax.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml
git commit -s -m "$(cat <<'EOF'
chore(deps): add CycloneDX Gradle plugin 1.10.0 to version catalog

Plugin will be applied in the next commit. Adding the catalog entry
first keeps build.gradle.kts changes reviewable in isolation.

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 2: Apply and configure the plugin in build.gradle.kts

**Files:**
- Modify: `build.gradle.kts` (`plugins { }` block, and a new top-level config block near the existing `springBoot { buildInfo() }` block)

- [ ] **Step 1: Apply the plugin**

In `build.gradle.kts`, locate the `plugins { … }` block (top of file, around line 19-30). Add a new alias line after the `alias(libs.plugins.graalvm.native) apply false` line:

```kotlin
    alias(libs.plugins.cyclonedx)
```

The final block looks like:

```kotlin
plugins {
    java
    `maven-publish`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    jacoco
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
    alias(libs.plugins.jib)
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.cyclonedx)
}
```

- [ ] **Step 2: Add `cyclonedxBom` configuration block**

Find the `springBoot { buildInfo() }` block (around line 195-197). Immediately AFTER it, insert:

```kotlin
// CycloneDX SBOM (Software Bill of Materials)
// ==========================================
// Spring Boot 3.3+ automatically embeds the generated SBOM into the bootable
// JAR at META-INF/sbom/application.cdx.json when the file name matches
// `application.cdx`. The actuator then serves it at /actuator/sbom (HTTP
// profile only — see application-http.properties).
//
// One SBOM, three distribution channels:
//   1. Embedded in the bootable JAR (META-INF/sbom/application.cdx.json)
//   2. Embedded in every Docker image (Jib + Paketo both package bootJar contents)
//   3. Surfaced at /actuator/sbom for live introspection (HTTP profile)
//
// The `bootJar` task automatically depends on `cyclonedxBom` once the plugin
// is applied — no manual `dependsOn` wiring needed.
tasks.cyclonedxBom {
    outputName.set("application.cdx")
    outputFormat.set("json")
    schemaVersion.set("1.5")
    projectType.set("application")
    includeConfigs.set(listOf("runtimeClasspath"))
    skipConfigs.set(listOf("testRuntimeClasspath", "errorprone", "annotationProcessor"))
}
```

- [ ] **Step 3: Run formatter and build the SBOM**

Run:
```bash
./gradlew spotlessApply
./gradlew cyclonedxBom -q
```
Expected: both succeed. After the second command, `build/reports/application.cdx.json` exists.

- [ ] **Step 4: Verify SBOM shape**

Run:
```bash
test -f build/reports/application.cdx.json && \
  grep -q '"bomFormat" : "CycloneDX"' build/reports/application.cdx.json && \
  grep -q '"specVersion" : "1.5"' build/reports/application.cdx.json && \
  echo "SBOM OK"
```
Expected: prints `SBOM OK`. If grep fails because the JSON is minified, swap `grep -q '"bomFormat":"CycloneDX"'` (no spaces).

- [ ] **Step 5: Verify the SBOM is embedded in the bootJar**

Run:
```bash
./gradlew bootJar -q
unzip -l build/libs/solr-mcp-*.jar | grep -F 'META-INF/sbom/application.cdx.json'
```
Expected: one line of output showing the path exists in the JAR.

If the file is NOT present: the bootJar task didn't pick it up. Check that `outputName` is exactly `application.cdx` (not `application.cdx.json`) — Spring Boot appends the format extension itself.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -s -m "$(cat <<'EOF'
feat(build): wire CycloneDX plugin to generate and embed SBOM

Spring Boot 3.5's bootJar auto-embeds META-INF/sbom/application.cdx.json
when the file name matches `application.cdx`. The Jib JVM image and both
Paketo native images package the bootJar contents, so every distribution
artifact now carries an embedded CycloneDX 1.5 SBOM.

Plugin config:
- outputFormat=json (actuator only consumes JSON)
- includeConfigs=runtimeClasspath only — test/errorprone deps excluded
- schemaVersion=1.5

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 3: Enable the /actuator/sbom endpoint explicitly

**Files:**
- Modify: `src/main/resources/application-http.properties`

`sbom` is already in `management.endpoints.web.exposure.include`. We're adding an explicit `enabled=true` line so the project's convention (be explicit about endpoint state) is satisfied and so any future scan reading just the properties file sees the intent.

- [ ] **Step 1: Add the property**

Find the line that begins with `# observability` (or `management.endpoints.web.exposure.include=...`). On a new line immediately after the `management.endpoints.web.exposure.include=...` line, add:

```properties
management.endpoint.sbom.enabled=true
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application-http.properties
git commit -s -m "$(cat <<'EOF'
feat(actuator): enable /actuator/sbom endpoint explicitly

`sbom` was already in management.endpoints.web.exposure.include; this
makes the endpoint enablement explicit so the file conveys intent
without relying on Spring Boot defaults.

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 4: Add a focused HTTP integration test for /actuator/sbom

**Files:**
- Create: `src/test/java/org/apache/solr/mcp/server/observability/SbomEndpointIntegrationTest.java`

The test boots the HTTP profile (which mirrors `McpClientIntegrationTest`'s setup), hits `/actuator/sbom` over HTTP, and asserts the response is valid CycloneDX JSON.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/apache/solr/mcp/server/observability/SbomEndpointIntegrationTest.java` with:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the CycloneDX SBOM is served at /actuator/sbom in HTTP mode. The
 * SBOM is generated at build time by the cyclonedx Gradle plugin and embedded
 * in the bootJar at META-INF/sbom/application.cdx.json; the actuator
 * auto-discovers and serves it from there.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"http.security.enabled=false", "spring.docker.compose.enabled=false"})
@ActiveProfiles("http")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SbomEndpointIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	void sbomEndpointReturnsCycloneDxJson() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/actuator/sbom/application"))
				.GET()
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(ct -> assertThat(ct).contains("application/vnd.cyclonedx+json"));
		assertThat(response.body()).contains("\"bomFormat\"").contains("CycloneDX");
	}
}
```

**Note on the URL:** Spring Boot's SBOM actuator exposes each embedded SBOM under `/actuator/sbom/{id}`. The default id for the application SBOM is `application` (derived from the file basename `application.cdx`). If the test fails with 404 because the id differs, hit `/actuator/sbom` first (an index) to discover the right id, then update the URL.

- [ ] **Step 2: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests org.apache.solr.mcp.server.observability.SbomEndpointIntegrationTest -i
```
Expected: PASS. If FAIL with status 404, see the note above and adjust the URL. If FAIL because of compilation, check that `assertj` is on the test classpath (it is — pulled in by `spring-boot-starter-test`).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/apache/solr/mcp/server/observability/SbomEndpointIntegrationTest.java
git commit -s -m "$(cat <<'EOF'
test(observability): verify /actuator/sbom serves CycloneDX JSON

Focused HTTP integration test that boots the http profile with the
existing TestcontainersConfiguration and asserts the SBOM endpoint
returns 200 with CycloneDX content.

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 5: Upload SBOM as workflow artifact in build-and-publish.yml

**Files:**
- Modify: `.github/workflows/build-and-publish.yml`

- [ ] **Step 1: Add an upload step after the existing JAR upload**

Find the step labeled `Upload JAR artifact` (around line 145-150). Immediately after it, add a new step:

```yaml
            # Upload the CycloneDX SBOM produced during the build
            # build/reports/application.cdx.json is generated by the cyclonedx
            # Gradle plugin and is also embedded in the bootable JAR
            -   name: Upload SBOM artifact
                if: always()
                uses: actions/upload-artifact@v4
                with:
                    name: solr-mcp-sbom
                    path: build/reports/application.cdx.json
                    retention-days: 30
```

The `if: always()` mirrors the test-results pattern and ensures the SBOM is captured even if a downstream test fails (useful for debugging dependency-related test failures). Retention is 30 days (longer than the 7-day artifact retention) because SBOMs are useful for after-the-fact supply-chain investigation.

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/build-and-publish.yml
git commit -s -m "$(cat <<'EOF'
ci: upload CycloneDX SBOM as workflow artifact

Mirrors the existing JAR/test-results/coverage upload pattern. Retains
the SBOM for 30 days (vs the standard 7) since supply-chain
investigations often happen well after a build.

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 6: Fix and extend the release-publish.yml SBOM step

**Files:**
- Modify: `.github/workflows/release-publish.yml`

The workflow already has a `Generate SBOM (Software Bill of Materials)` step that runs `./gradlew cyclonedxBom || echo "SBOM generation not configured"`. With the plugin applied, that `|| echo` fallback would mask real failures. Replace it with a strict invocation and add an upload + GitHub Release attachment.

- [ ] **Step 1: Locate the existing step**

In `release-publish.yml`, search for `Generate SBOM`. You'll find:

```yaml
      - name: Generate SBOM (Software Bill of Materials)
        run: |
          # Generate SBOM for the release
          # This helps with supply chain security
          ./gradlew cyclonedxBom || echo "SBOM generation not configured"
```

- [ ] **Step 2: Replace it with the strict invocation + upload + attach**

Replace the step above with:

```yaml
      - name: Generate SBOM (Software Bill of Materials)
        run: ./gradlew cyclonedxBom

      - name: Upload SBOM as workflow artifact
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: solr-mcp-sbom-${{ inputs.release_version }}
          path: build/reports/application.cdx.json
          retention-days: 90

      - name: Attach SBOM to GitHub Release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          RELEASE_VERSION: ${{ inputs.release_version }}
        run: |
          # Rename to include the version so the asset is unambiguous on the release page
          cp build/reports/application.cdx.json "solr-mcp-${RELEASE_VERSION}.cdx.json"
          # --clobber lets re-runs of this workflow replace a previously uploaded SBOM
          # If the v<version> GitHub Release does not exist yet, log and continue —
          # the workflow artifact above is still captured.
          if gh release view "v${RELEASE_VERSION}" >/dev/null 2>&1; then
            gh release upload "v${RELEASE_VERSION}" "solr-mcp-${RELEASE_VERSION}.cdx.json" --clobber
          else
            echo "GitHub Release v${RELEASE_VERSION} does not exist yet; SBOM available as workflow artifact only."
          fi
```

The 90-day retention is longer than build-and-publish (30 days) because release SBOMs have an asynchronous secondary consumer: PMC members downloading them weeks after a vote.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release-publish.yml
git commit -s -m "$(cat <<'EOF'
ci(release): strict SBOM generation + upload + release attachment

The existing Generate SBOM step swallowed errors with `|| echo "..."`,
masking failures now that the plugin is wired. Removes the fallback,
uploads the SBOM as a 90-day workflow artifact, and attaches it to the
v<version> GitHub Release when one exists (graceful fallback otherwise
since the source release of record lives at dist.apache.org, not GitHub).

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 7: Document SBOM in README.md

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a new section before `## Documentation`**

Find the `## Documentation` section (last `##` heading in the file, around line 456). Immediately BEFORE it, insert:

```markdown
## Supply chain & SBOM

Every released JAR and Docker image ships a [CycloneDX](https://cyclonedx.org/)
1.5 Software Bill of Materials so downstream consumers can audit and scan the
dependency graph.

### Where the SBOM lives

- **Inside every JAR and image:** `META-INF/sbom/application.cdx.json` —
  embedded by the Spring Boot Gradle plugin at build time. The Jib JVM image
  (`solr-mcp:<v>`) and both Paketo native images (`solr-mcp:<v>-native-stdio`,
  `solr-mcp:<v>-native-http`) all package the bootJar contents, so the SBOM
  ships with every distribution channel.
- **HTTP endpoint** (`http` profile only): `GET /actuator/sbom/application`
  returns the same SBOM as `application/vnd.cyclonedx+json`.
- **GitHub Releases:** the release workflow attaches
  `solr-mcp-<version>.cdx.json` to every official ASF release.
- **CI artifacts:** every `Build and Publish` run uploads `solr-mcp-sbom`
  (CycloneDX JSON) to the workflow run page; downloadable for 30 days.

### Fetch the SBOM

From a running HTTP-mode server:

```bash
curl -s http://localhost:8080/actuator/sbom/application > application.cdx.json
```

From the local build (no server required):

```bash
./gradlew cyclonedxBom
cat build/reports/application.cdx.json
```

### Scan the SBOM

```bash
# Trivy
trivy sbom application.cdx.json

# Grype
grype sbom:application.cdx.json
```

Both tools natively consume CycloneDX 1.5 and report CVEs against the
listed components.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -s -m "$(cat <<'EOF'
docs(readme): document SBOM location, retrieval, and scanning

New 'Supply chain & SBOM' section covers all four distribution
channels (embedded in JAR/image, /actuator/sbom endpoint, GitHub
Release asset, CI workflow artifact) and shows trivy/grype usage.

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 8: Note SBOM in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

CLAUDE.md is project-level guidance for AI assistants. Two small additions: a build command and an architecture note.

- [ ] **Step 1: Add a command line under "Common Commands"**

Find the `## Common Commands` section. Within the fenced bash block, locate the `# Code formatting (REQUIRED before commit)` group. Immediately BEFORE that group, insert:

```bash
# SBOM (Software Bill of Materials)
./gradlew cyclonedxBom                       # Generate build/reports/application.cdx.json

```

(Keep the trailing blank line so the existing groups stay visually separated.)

- [ ] **Step 2: Add an architecture note**

Find the `### Logging Architecture` section. Immediately BEFORE it, insert a new section:

```markdown
### SBOM Architecture

CycloneDX SBOM generation is wired via `org.cyclonedx.bom` (`tasks.cyclonedxBom` in
`build.gradle.kts`). The Spring Boot Gradle plugin embeds the generated file
into the bootJar at `META-INF/sbom/application.cdx.json`; the actuator
auto-discovers it and serves it at `/actuator/sbom/application` in the `http`
profile (enabled in `application-http.properties`). Both the Jib JVM image and
the Paketo native images package the bootJar contents, so every distribution
artifact ships the SBOM without per-image wiring.

Spec: [docs/superpowers/specs/2026-06-05-sbom-generation-design.md](docs/superpowers/specs/2026-06-05-sbom-generation-design.md)
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -s -m "$(cat <<'EOF'
docs(claude): note SBOM generation in commands + architecture

Records the cyclonedxBom command and how the SBOM flows through
bootJar → actuator → Docker images, so future agents have the
mental model when working on related code.

Signed-off-by: Aditya Parikh <aditya.m.parikh@gmail.com>
EOF
)"
```

---

## Task 9: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full build**

Run:
```bash
./gradlew spotlessApply build
```
Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 2: Confirm SBOM artifacts present**

Run:
```bash
ls -lh build/reports/application.cdx.json && \
  unzip -l build/libs/solr-mcp-*.jar | grep -F 'META-INF/sbom/application.cdx.json'
```
Expected: file exists; one line in JAR listing for the embedded SBOM.

- [ ] **Step 3: Inspect SBOM head**

Run:
```bash
head -20 build/reports/application.cdx.json
```
Expected: includes `"bomFormat" : "CycloneDX"` and `"specVersion" : "1.5"`.

- [ ] **Step 4: Push the branch and open the PR**

```bash
git push -u origin worktree-add-sbom-generation
gh pr create --title "feat(build): generate CycloneDX SBOM for every release artifact" --body "$(cat <<'EOF'
## Summary

- Wires CycloneDX 1.5 SBOM generation into every build, embeds it in the
  bootJar at `META-INF/sbom/application.cdx.json`, and exposes it at
  `/actuator/sbom/application` in HTTP mode.
- Jib JVM image and both Paketo native images ship the SBOM for free via
  bootJar packaging — no per-image wiring.
- `build-and-publish.yml` uploads the SBOM as a 30-day workflow artifact;
  `release-publish.yml` uploads as a 90-day artifact and attaches it to the
  matching GitHub Release.
- README documents location, retrieval, and scanning with trivy/grype.

Spec: `docs/superpowers/specs/2026-06-05-sbom-generation-design.md`

## Test plan

- [x] `./gradlew build` is green
- [x] `build/reports/application.cdx.json` produced (`bomFormat: CycloneDX`, `specVersion: 1.5`)
- [x] SBOM is embedded in `build/libs/solr-mcp-*.jar` at `META-INF/sbom/application.cdx.json`
- [x] `SbomEndpointIntegrationTest` passes (`/actuator/sbom/application` returns 200 + CycloneDX JSON)
- [ ] CI green on this PR
- [ ] Manual sanity-check after merge: pull the resulting Jib image, `docker run` it with `PROFILES=http`, `curl /actuator/sbom/application`
EOF
)"
```

---

## Self-review

**Spec coverage check:**

- ✅ CycloneDX Gradle plugin applied — Task 1, 2
- ✅ `outputName=application.cdx`, `outputFormat=json`, `schemaVersion=1.5` — Task 2
- ✅ `includeConfigs=runtimeClasspath`, exclude test/errorprone — Task 2
- ✅ Embedded in bootJar at `META-INF/sbom/application.cdx.json` — verified in Task 2 step 5
- ✅ `/actuator/sbom` exposed by default in HTTP profile — Task 3 (and pre-existing exposure list)
- ✅ Workflow artifact in build-and-publish.yml — Task 5
- ✅ Workflow artifact + GitHub Release asset in release-publish.yml — Task 6
- ✅ README documents location, retrieval, scanning — Task 7
- ✅ CLAUDE.md notes the plugin + endpoint — Task 8
- ✅ One focused HTTP integration test asserting CycloneDX response — Task 4
- ✅ Build green at end — Task 9

**Placeholder scan:** No TBD / TODO / "implement later" found. Every code/config block is complete and copyable.

**Type/name consistency:** `application.cdx` used consistently as `outputName`; the embedded path is consistently `META-INF/sbom/application.cdx.json`; endpoint URL `/actuator/sbom/application` consistent between Task 4 (test), Task 7 (README), Task 8 (CLAUDE.md). The plugin task name `cyclonedxBom` is consistent across Tasks 2, 6, and 9.
