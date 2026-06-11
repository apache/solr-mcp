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
package org.apache.solr.mcp.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the binary-release `LICENSE`: the base Apache-2.0 text plus an appendix
 * listing every bundled dependency and the license the CycloneDX SBOM reports for it.
 *
 * License data is read from the SBOM (the same SBOM embedded in the bootJar), keyed to
 * the [bundledCoordinates] that actually ship. The SBOM resolves a license for every
 * component — including Gradle-module-metadata-only artifacts (e.g. SolrJ) that POM-only
 * scanners miss — so no per-dependency list is hand-maintained.
 *
 * Licenses are reported **as the SBOM declares them**; the appendix is a disclosure, not
 * a license policy, so it carries no allow-list and applies no corrections (a few
 * upstream POMs report imprecise but still-permissive identifiers). The task's only gate
 * is completeness: it fails if a bundled coordinate is absent from the SBOM, so a
 * dependency can never be silently omitted from the LICENSE.
 *
 * For readers new to Gradle: this is a custom build *task* (a unit of build work). It is
 * created and configured by the `org.apache.solr.mcp.license-notice` convention plugin,
 * and runs as part of `./gradlew build` / `bootJar`. The annotated `abstract val`
 * properties below are its declared inputs and output — Gradle reads those annotations
 * to skip the task when nothing changed and to run it before whatever consumes its
 * output (here, the `bootJar`). See `buildSrc/README.md` for a fuller primer.
 */
abstract class GenerateBinaryLicense : DefaultTask() {

    /**
     * The repo-root Apache-2.0 `LICENSE` that the third-party appendix is appended to.
     * `@InputFile` marks it a file input, so the task re-runs if it changes. The path is
     * not part of the cache key (`PathSensitivity.NONE`) — only the contents matter.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseLicense: RegularFileProperty

    /**
     * The generated CycloneDX SBOM (`application.cdx.json`), read to find each bundled
     * dependency's license. `@InputFile`, so the task re-runs when the SBOM changes.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sbom: RegularFileProperty

    /**
     * The dependencies that actually ship, as `"group:name:version"` strings — the source
     * of truth for what to list. `@Input` marks it a plain *value* input (not a file), so
     * the task re-runs whenever the shipped dependency set changes.
     */
    @get:Input
    abstract val bundledCoordinates: ListProperty<String>

    /**
     * Where the assembled binary `LICENSE` is written. `@OutputFile` lets Gradle skip the
     * task when the output is already up to date, and lets the `bootJar` task depend on it.
     */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /** One license entry in the appendix: a display label and an optional link to its text. */
    private data class License(val label: String, val url: String?)

    /** Gradle runs this method when the task executes (`@TaskAction`). */
    @TaskAction
    fun generate() {
        val slurper = JsonSlurper()

        // 1. Index every SBOM component's licenses by "group:name" and "group:name:version".
        //    The version-keyed map is preferred so the exact shipped version wins; the
        //    coarser key is the fallback when versions differ between SBOM and classpath.
        @Suppress("UNCHECKED_CAST")
        val sbomJson = slurper.parse(sbom.get().asFile) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val components = (sbomJson["components"] as? List<Map<String, Any?>>).orEmpty()
        val byGroupArtifact = HashMap<String, List<License>>()
        val byGroupArtifactVersion = HashMap<String, List<License>>()
        for (component in components) {
            val group = component["group"] as? String ?: continue
            val name = component["name"] as? String ?: continue
            val licenses = licensesOf(component)
            byGroupArtifact["$group:$name"] = licenses
            (component["version"] as? String)?.let { byGroupArtifactVersion["$group:$name:$it"] = licenses }
        }

        // 2. For each dependency that actually ships, look up its license(s) in the SBOM and
        //    append a row. Collect any coordinate the SBOM does not cover for the gate below.
        val notInSbom = mutableListOf<String>()
        val rows = StringBuilder()
        for (coordinate in bundledCoordinates.get()) {
            val groupArtifact = coordinate.substringBeforeLast(':')
            val licenses =
                byGroupArtifactVersion[coordinate]
                    ?: byGroupArtifact[groupArtifact]
                    ?: emptyList()
            if (licenses.isEmpty()) {
                notInSbom += coordinate
                continue
            }
            rows.append("- ").append(coordinate).append('\n')
            for (license in licenses) {
                rows.append("    License: ").append(license.label)
                if (!license.url.isNullOrBlank()) rows.append(" — ").append(license.url)
                rows.append('\n')
            }
        }

        // 3. Completeness gate: a shipped dependency missing from the SBOM would be silently
        //    omitted from the LICENSE, so fail loudly. This is the "verify bundled deps are
        //    accounted for" check; it makes no judgement about which licenses are acceptable.
        if (notInSbom.isNotEmpty()) {
            throw GradleException(
                "Bundled dependencies absent from the CycloneDX SBOM:\n" +
                    notInSbom.joinToString("\n") { "  - $it" } +
                    "\nEnsure cyclonedxBom covers the runtime classpath.",
            )
        }

        // 4. Write the binary LICENSE: the base Apache-2.0 text, then the generated
        //    third-party appendix.
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            append(baseLicense.get().asFile.readText().trimEnd()).append("\n\n\n")
            append("=".repeat(78)).append('\n')
            append("APACHE SOLR MCP SERVER — THIRD-PARTY DEPENDENCY LICENSES\n")
            append("=".repeat(78)).append("\n\n")
            append(
                "The binary distribution (the Spring Boot executable JAR) bundles the\n" +
                    "third-party dependencies listed below, derived from the bundled CycloneDX\n" +
                    "SBOM. License identifiers are reported as the SBOM declares them (SPDX ids\n" +
                    "where available) and a few may be imprecise; consult each dependency's own\n" +
                    "license for the authoritative terms via the link shown. A machine-readable\n" +
                    "bill of materials (component versions, hashes, and licenses) is also bundled\n" +
                    "at META-INF/sbom/application.cdx.json.\n\n",
            )
            append(rows)
        })
    }

    /** Distinct (label, url?) licenses of an SBOM component; prefers SPDX id, else name/expression. */
    private fun licensesOf(component: Map<String, Any?>): List<License> {
        val out = LinkedHashMap<String, String?>()

        @Suppress("UNCHECKED_CAST")
        val nodes = component["licenses"] as? List<Map<String, Any?>> ?: return emptyList()
        for (node in nodes) {
            @Suppress("UNCHECKED_CAST")
            val license = node["license"] as? Map<String, Any?>
            if (license != null) {
                val id = license["id"] as? String
                val label = id ?: (license["name"] as? String) ?: "Unspecified"
                val url = (license["url"] as? String) ?: id?.let { spdxUrl(it) }
                out.putIfAbsent(label, url)
            } else {
                (node["expression"] as? String)?.let { out.putIfAbsent(it, null) }
            }
        }
        return out.map { License(it.key, it.value) }
    }

    private fun spdxUrl(spdxId: String): String = "https://spdx.org/licenses/$spdxId.html"
}
