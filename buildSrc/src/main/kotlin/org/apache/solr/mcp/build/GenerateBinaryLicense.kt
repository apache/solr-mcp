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
 * listing every bundled dependency and a link to its license.
 *
 * License data is read from the CycloneDX SBOM (the same SBOM embedded in the bootJar),
 * keyed to the [bundledCoordinates] that actually ship. The SBOM resolves a license for
 * every component — including Gradle-module-metadata-only artifacts (e.g. SolrJ) that
 * POM-only scanners miss — so no per-dependency list is hand-maintained.
 *
 * The task also gates the build: it fails if a bundled coordinate is absent from the
 * SBOM, or carries a license not in the policy's `allowedLicenses`. The policy's
 * `overrides` map (group:name -> SPDX id) corrects the few components CycloneDX
 * mislabels.
 */
abstract class GenerateBinaryLicense : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val policyFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sbom: RegularFileProperty

    /** Shipped dependencies as "group:name:version", the source of truth for what to list. */
    @get:Input
    abstract val bundledCoordinates: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    private data class License(val label: String, val url: String?)

    @TaskAction
    fun generate() {
        val slurper = JsonSlurper()

        // 1. Load the policy: the set of licenses allowed in a binary release, plus
        //    group:name -> SPDX-id corrections for components CycloneDX mislabels.
        @Suppress("UNCHECKED_CAST")
        val policy = slurper.parse(policyFile.get().asFile) as Map<String, Any?>
        val allowed =
            (policy["allowedLicenses"] as? List<*>).orEmpty().filterIsInstance<String>().toSet()

        @Suppress("UNCHECKED_CAST")
        val overrides = (policy["overrides"] as? Map<String, String>).orEmpty()

        // 2. Index every SBOM component's licenses by "group:name" and "group:name:version".
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

        // 3. For each dependency that actually ships, resolve its license(s) — an override
        //    wins, else the SBOM lookup — and accumulate both the appendix text and two
        //    failure lists: deps the SBOM doesn't cover, and deps whose license isn't allowed.
        val notInSbom = mutableListOf<String>()
        val disallowed = mutableListOf<String>()
        val rows = StringBuilder()
        for (coordinate in bundledCoordinates.get()) {
            val groupArtifact = coordinate.substringBeforeLast(':')
            val licenses =
                overrides[groupArtifact]?.let { listOf(License(it, spdxUrl(it))) }
                    ?: byGroupArtifactVersion[coordinate]
                    ?: byGroupArtifact[groupArtifact]
                    ?: emptyList()
            if (licenses.isEmpty()) {
                notInSbom += coordinate
                continue
            }
            for (license in licenses) {
                if (license.label !in allowed) disallowed += "$coordinate -> ${license.label}"
            }
            rows.append("- ").append(coordinate).append('\n')
            for (license in licenses) {
                rows.append("    License: ").append(license.label)
                if (!license.url.isNullOrBlank()) rows.append(" — ").append(license.url)
                rows.append('\n')
            }
        }

        // 4. Gate the build: an uncovered or disallowed dependency must never ship silently
        //    in the binary LICENSE — fail loudly with the offending coordinates so the
        //    policy/SBOM is corrected before release. This is the "verify new deps are
        //    accounted for" check.
        if (notInSbom.isNotEmpty()) {
            throw GradleException(
                "Bundled dependencies absent from the CycloneDX SBOM:\n" +
                    notInSbom.joinToString("\n") { "  - $it" } +
                    "\nEnsure cyclonedxBom covers the runtime classpath.",
            )
        }
        if (disallowed.isNotEmpty()) {
            throw GradleException(
                "Bundled dependencies with a license not in the license policy:\n" +
                    disallowed.joinToString("\n") { "  - $it" } +
                    "\nAfter verifying, add the license to allowedLicenses, or add a " +
                    "group:name -> SPDX-id entry to overrides if the SBOM mislabels it.",
            )
        }

        // 5. Write the binary LICENSE: the base Apache-2.0 text, then the generated
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
                    "SBOM (META-INF/sbom/application.cdx.json). Each is provided under the license\n" +
                    "noted; refer to the linked license text for the full terms.\n\n",
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
