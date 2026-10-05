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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import kotlinx.html.code
import kotlinx.html.li
import kotlinx.html.stream.appendHTML
import kotlinx.html.td
import kotlinx.html.tr
import kotlinx.html.ul

/**
 * Generates the dependency/license row for the Incubator IP-clearance status document
 * (`<project>-ip-clearance.xml`, the "Check and make sure that all items depended upon by
 * the project are covered by ... approved licenses" row of its "Verify distribution rights"
 * table), as an XML `<tr>` fragment ready to paste into that table.
 *
 * It is derived from the same inputs as the binary LICENSE ([GenerateBinaryLicense]): the
 * shipped dependency coordinates and the CycloneDX SBOM, so the two cannot drift. Each
 * dependency is listed with the license(s) the SBOM reports, exactly as reported.
 *
 * Like the LICENSE appendix, this is a disclosure, not a license policy: it carries no
 * allow-list and makes no Category A/B judgement. Whether the listed licenses are
 * acceptable is a human review before the checklist item is signed off.
 * A dependency missing from the SBOM fails the task, as in [GenerateBinaryLicense].
 */
abstract class GenerateIpClearanceLicenseReport : DefaultTask() {

    /** The generated CycloneDX SBOM (`application.cdx.json`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sbom: RegularFileProperty

    /** The dependencies that actually ship, as `"group:name:version"` strings. */
    @get:Input
    abstract val bundledCoordinates: ListProperty<String>

    /** Completion date shown in the row's first column (`YYYY-MM-dd`). */
    @get:Input
    abstract val date: Property<String>

    /** Where the XML fragment is written. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val sbomLicenses = SbomLicenses(sbom.get().asFile)

        val (missing, found) = bundledCoordinates.get()
            .associateWith { sbomLicenses.lookup(it) }
            .entries
            .partition { it.value.isEmpty() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Bundled dependencies absent from the CycloneDX SBOM:\n" +
                    missing.joinToString("\n") { "  - ${it.key}" } +
                    "\nEnsure cyclonedxBom covers the runtime classpath.",
            )
        }

        val items = found
            .associate { (coordinate, licenses) -> coordinate.substringBeforeLast(':') to licenses }
            .toSortedMap()

        val xml = buildString {
            appendHTML(prettyPrint = true).tr {
                td { +date.get() }
                td {
                    +CHECKLIST_WORDING
                    code { +"META-INF/LICENSE" }
                    +" of the executable JAR):"
                    ul {
                        items.forEach { (groupArtifact, licenses) ->
                            li { +"$groupArtifact \u2014 ${licenses.joinToString(" / ") { it.label }}" }
                        }
                    }
                }
            }
        }.prependIndent(ROW_INDENT)

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(xml + "\n")
    }

    private companion object {
        /** Indent that lines the row up with the sibling rows of the status document's table. */
        const val ROW_INDENT = "            "

        const val CHECKLIST_WORDING =
            "Check and make sure that all items depended upon by the project are covered by one or more " +
                "of the following approved licenses: Apache, BSD, Artistic, MIT/X, MIT/W3C, MPL 1.1, or " +
                "something with essentially the same terms. \u2014 All runtime dependencies bundled in the " +
                "release (derived from the CycloneDX SBOM, as reported there; the full list is also in "
    }
}
