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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Generates the binary-release `NOTICE`: this project's base NOTICE followed by the
 * `META-INF/NOTICE` files lifted verbatim (and de-duplicated) from the bundled jars —
 * the same approach as Maven Shade's `ApacheNoticeResourceTransformer`, so notices
 * required by bundled (notably ASF) dependencies are carried and stay current.
 *
 * For readers new to Gradle: this is a custom build *task*, created and configured by the
 * `org.apache.solr.mcp.license-notice` convention plugin and run as part of
 * `./gradlew build` / `bootJar`. The annotated `abstract val` properties are its declared
 * inputs and output (used for up-to-date checking and task ordering). See
 * `buildSrc/README.md` for a primer.
 */
abstract class GenerateBinaryNotice : DefaultTask() {

    /**
     * The bundled dependency jars to scan for `META-INF/NOTICE` entries. `@InputFiles`
     * marks the whole collection a file input, so the task re-runs when the set of jars
     * (or their contents) changes.
     */
    @get:InputFiles
    abstract val jars: ConfigurableFileCollection

    /**
     * Maps a jar's file name to its `"group:name:version"`, used to attribute each lifted
     * notice to the dependency it came from. `@Input` — a plain value input (string map).
     */
    @get:Input
    abstract val coordinateByJarName: MapProperty<String, String>

    /** This project's own repo-root `NOTICE`, written first. `@InputFile` (contents only). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseNotice: RegularFileProperty

    /**
     * Where the assembled binary `NOTICE` is written. `@OutputFile` enables up-to-date
     * skipping and lets the `bootJar` task depend on it.
     */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /** Gradle runs this method when the task executes (`@TaskAction`). */
    @TaskAction
    fun generate() {
        val coordinates = coordinateByJarName.get()
        // Match the conventional notice file names (NOTICE, NOTICE.txt, NOTICE.md) at the
        // root of META-INF, case-insensitively.
        val noticeEntry = Regex("(^|/)META-INF/NOTICE(\\.txt|\\.md)?$", RegexOption.IGNORE_CASE)
        // Tracks notice bodies already emitted so identical notices (common across related
        // modules, e.g. a multi-module library) appear once.
        val seen = LinkedHashSet<String>()
        val sections = StringBuilder()

        // Walk the bundled jars in a stable order (by module coordinate) so the output is
        // reproducible, and lift each jar's NOTICE entry verbatim.
        jars.files
            .filter { it.name.endsWith(".jar") }
            .sortedBy { coordinates[it.name] ?: it.name }
            .forEach { jar ->
                val label = coordinates[jar.name] ?: jar.name
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && noticeEntry.containsMatchIn(it.name) }
                        .forEach { entry ->
                            val text =
                                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText().trim()
                            // Only the first occurrence of a given notice body is kept,
                            // attributed to the module it came from.
                            if (text.isNotEmpty() && seen.add(text)) {
                                sections.append('\n').append("-".repeat(78)).append('\n')
                                sections.append("From ").append(label).append(":\n\n")
                                sections.append(text).append('\n')
                            }
                        }
                }
            }

        // Write the binary NOTICE: this project's NOTICE, then the aggregated dependency
        // notices under a header (omitted entirely if no dependency ships a NOTICE).
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            append(baseNotice.get().asFile.readText().trimEnd()).append('\n')
            if (sections.isNotEmpty()) {
                append("\n\n").append("=".repeat(78)).append('\n')
                append("NOTICES FROM BUNDLED THIRD-PARTY DEPENDENCIES (binary distribution)\n")
                append("=".repeat(78)).append('\n')
                append(sections)
            }
        })
    }
}
