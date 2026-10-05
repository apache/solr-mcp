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
import java.io.File

/** One license entry for a component: a display label and an optional link to its text. */
internal data class License(val label: String, val url: String?)

/**
 * Index of the licenses the CycloneDX SBOM reports per component, shared by the tasks that
 * turn the SBOM into human-readable output ([GenerateBinaryLicense],
 * [GenerateIpClearanceLicenseReport]).
 *
 * The version-keyed lookup is preferred so the exact shipped version wins; the coarser
 * `group:name` key is the fallback when versions differ between the SBOM and the classpath.
 * Licenses are reported as the SBOM declares them: no allow-list, no corrections.
 */
internal class SbomLicenses(sbomFile: File) {
    private val byGroupArtifact = HashMap<String, List<License>>()
    private val byGroupArtifactVersion = HashMap<String, List<License>>()

    init {
        @Suppress("UNCHECKED_CAST")
        val sbomJson = JsonSlurper().parse(sbomFile) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val components = (sbomJson["components"] as? List<Map<String, Any?>>).orEmpty()
        for (component in components) {
            val group = component["group"] as? String ?: continue
            val name = component["name"] as? String ?: continue
            val licenses = licensesOf(component)
            byGroupArtifact["$group:$name"] = licenses
            (component["version"] as? String)?.let { byGroupArtifactVersion["$group:$name:$it"] = licenses }
        }
    }

    /** Licenses for a `group:name:version` coordinate; empty when the SBOM does not cover it. */
    fun lookup(coordinate: String): List<License> =
        byGroupArtifactVersion[coordinate]
            ?: byGroupArtifact[coordinate.substringBeforeLast(':')]
            ?: emptyList()

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
                val url = (license["url"] as? String) ?: id?.let { "https://spdx.org/licenses/$it.html" }
                out.putIfAbsent(label, url)
            } else {
                (node["expression"] as? String)?.let { out.putIfAbsent(it, null) }
            }
        }
        return out.map { License(it.key, it.value) }
    }
}
