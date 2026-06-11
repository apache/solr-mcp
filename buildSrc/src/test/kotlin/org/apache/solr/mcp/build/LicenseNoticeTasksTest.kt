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

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LicenseNoticeTasksTest {

    @TempDir
    lateinit var tempDir: File

    // ---- GenerateBinaryLicense ----------------------------------------------------

    @Test
    fun `license appendix lists bundled deps, applies overrides, and keeps the base text`() {
        val task = licenseTask()
        write("LICENSE", "APACHE-2.0 BASE TEXT").let(task.baseLicense::set)
        write(
            "policy.json",
            """{"allowedLicenses":["Apache-2.0","BSD-3-Clause"],
               "overrides":{"org.antlr:ST4":"BSD-3-Clause"}}""",
        ).let(task.policyFile::set)
        // ST4's SBOM license (BSD-4-Clause) is intentionally NOT allowed; the override fixes it.
        write(
            "sbom.json",
            """{"components":[
               {"group":"org.apache.solr","name":"solr-solrj","version":"10.0.0",
                "licenses":[{"license":{"id":"Apache-2.0"}}]},
               {"group":"org.antlr","name":"ST4","version":"4.3.4",
                "licenses":[{"license":{"id":"BSD-4-Clause"}}]}]}""",
        ).let(task.sbom::set)
        task.bundledCoordinates.set(listOf("org.apache.solr:solr-solrj:10.0.0", "org.antlr:ST4:4.3.4"))
        val out = File(tempDir, "out/LICENSE")
        task.outputFile.set(out)

        task.generate()

        val text = out.readText()
        assertTrue(text.startsWith("APACHE-2.0 BASE TEXT"), "base license text must be preserved")
        assertTrue(text.contains("- org.apache.solr:solr-solrj:10.0.0"), "SolrJ must be listed")
        assertTrue(text.contains("Apache-2.0 — https://spdx.org/licenses/Apache-2.0.html"))
        assertTrue(text.contains("BSD-3-Clause"), "override should correct ST4 to BSD-3-Clause")
        assertFalse(text.contains("BSD-4-Clause"), "the mislabelled SBOM value must not appear")
    }

    @Test
    fun `license gate fails when a bundled dependency carries a disallowed license`() {
        val task = licenseTask()
        write("LICENSE", "BASE").let(task.baseLicense::set)
        write("policy.json", """{"allowedLicenses":["Apache-2.0"],"overrides":{}}""").let(task.policyFile::set)
        write(
            "sbom.json",
            """{"components":[{"group":"x","name":"y","version":"1.0",
               "licenses":[{"license":{"id":"GPL-3.0-only"}}]}]}""",
        ).let(task.sbom::set)
        task.bundledCoordinates.set(listOf("x:y:1.0"))
        task.outputFile.set(File(tempDir, "out/LICENSE"))

        val ex = assertThrows(GradleException::class.java) { task.generate() }
        assertTrue(ex.message!!.contains("not in the license policy"))
        assertTrue(ex.message!!.contains("x:y:1.0 -> GPL-3.0-only"))
    }

    @Test
    fun `license gate fails when a bundled dependency is absent from the SBOM`() {
        val task = licenseTask()
        write("LICENSE", "BASE").let(task.baseLicense::set)
        write("policy.json", """{"allowedLicenses":["Apache-2.0"],"overrides":{}}""").let(task.policyFile::set)
        write("sbom.json", """{"components":[]}""").let(task.sbom::set)
        task.bundledCoordinates.set(listOf("missing:dep:1.0"))
        task.outputFile.set(File(tempDir, "out/LICENSE"))

        val ex = assertThrows(GradleException::class.java) { task.generate() }
        assertTrue(ex.message!!.contains("absent from the CycloneDX SBOM"))
        assertTrue(ex.message!!.contains("missing:dep:1.0"))
    }

    // ---- GenerateBinaryNotice -----------------------------------------------------

    @Test
    fun `notice aggregates bundled notices verbatim, de-duplicated and labelled`() {
        val task = noticeTask()
        write("NOTICE", "PROJECT NOTICE").let(task.baseNotice::set)
        val jarA = jarWithNotice("a.jar", "Shared notice text")
        val jarB = jarWithNotice("b.jar", "Shared notice text") // duplicate -> collapsed
        val jarC = jarWithNotice("c.jar", "Unique C notice")
        task.jars.from(jarA, jarB, jarC)
        task.coordinateByJarName.set(
            mapOf("a.jar" to "g:a:1", "b.jar" to "g:b:1", "c.jar" to "g:c:1"),
        )
        val out = File(tempDir, "out/NOTICE")
        task.outputFile.set(out)

        task.generate()

        val text = out.readText()
        assertTrue(text.startsWith("PROJECT NOTICE"), "project NOTICE must lead")
        assertEquals(1, occurrences(text, "Shared notice text"), "duplicate notices must collapse to one")
        assertTrue(text.contains("Unique C notice"))
        assertTrue(text.contains("From g:c:1:"), "each lifted notice must be attributed to its module")
    }

    @Test
    fun `notice with no dependency notices is just the project notice`() {
        val task = noticeTask()
        write("NOTICE", "PROJECT NOTICE").let(task.baseNotice::set)
        task.jars.from(jarWithoutNotice("plain.jar"))
        task.coordinateByJarName.set(mapOf("plain.jar" to "g:p:1"))
        val out = File(tempDir, "out/NOTICE")
        task.outputFile.set(out)

        task.generate()

        val text = out.readText()
        assertTrue(text.startsWith("PROJECT NOTICE"))
        assertFalse(text.contains("NOTICES FROM BUNDLED"), "no section header when there are no lifted notices")
    }

    // ---- helpers ------------------------------------------------------------------

    private fun project() = ProjectBuilder.builder().withProjectDir(tempDir).build()

    private fun licenseTask() =
        project().tasks.register("generateBinaryLicense", GenerateBinaryLicense::class.java).get()

    private fun noticeTask() =
        project().tasks.register("generateBinaryNotice", GenerateBinaryNotice::class.java).get()

    private fun write(name: String, content: String): File =
        File(tempDir, name).apply { parentFile.mkdirs(); writeText(content.trimIndent()) }

    private fun jarWithNotice(name: String, notice: String): File =
        File(tempDir, name).also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/NOTICE"))
                zip.write(notice.toByteArray())
                zip.closeEntry()
            }
        }

    private fun jarWithoutNotice(name: String): File =
        File(tempDir, name).also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray())
                zip.closeEntry()
            }
        }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}
