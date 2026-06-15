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

// Convention plugin: ASF-compliant LICENSE / NOTICE for the source and binary forms.
//
// For readers new to Gradle: this `.gradle.kts` file under buildSrc is a "precompiled
// script plugin". Gradle compiles it into a plugin whose id is the file name
// (`org.apache.solr.mcp.license-notice`); the root build applies it with one line,
// `id("org.apache.solr.mcp.license-notice")`. The body below runs at *configuration*
// time: it creates the two generator tasks (defined in this same buildSrc as
// GenerateBinaryLicense / GenerateBinaryNotice), wires their inputs, and connects their
// outputs to the `bootJar` and `check` tasks. See buildSrc/README.md for the primer.
//
// ASF policy requires distinct LICENSE/NOTICE for the source form and the binary form,
// because the binary (the Spring Boot fat `bootJar`) bundles third-party bytecode. See
// https://infra.apache.org/licensing-howto.html. This plugin:
//
//   - bundles the base Apache-2.0 LICENSE + NOTICE into the source-form jars as-is;
//   - generates, for the bootJar, a LICENSE with a third-party appendix derived from the
//     CycloneDX SBOM and a NOTICE that lifts bundled dependencies' notices, with a
//     completeness gate that fails the build if a bundled dependency is missing from the
//     SBOM. Licenses are disclosed as the SBOM reports them; there is no license policy.
//
// Apply this AFTER the Spring Boot and CycloneDX plugins so `productionRuntimeClasspath`
// and the `cyclonedxBom` task exist.

import org.apache.solr.mcp.build.GenerateBinaryLicense
import org.apache.solr.mcp.build.GenerateBinaryNotice
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// The project's source-form LICENSE/NOTICE at the repo root (the plain Apache-2.0 text
// and the base NOTICE). They are bundled as-is into the non-fat jars, and are also the
// base that the generated binary files are built on top of.
val licenseFile = layout.projectDirectory.file("LICENSE")
val noticeFile = layout.projectDirectory.file("NOTICE")

// A Gradle "configuration" is a named set of dependencies. `productionRuntimeClasspath`
// is the one that actually ends up inside the fat jar — it excludes test/compile-only and
// developmentOnly deps. So this is exactly "what ships", which is what the binary
// LICENSE/NOTICE must describe.
val shippedClasspath = configurations.named("productionRuntimeClasspath")

// Resolve that configuration to its actual artifacts — each is a jar file plus the module
// identity it came from. `flatMap` keeps everything lazy: nothing is resolved here while
// the build is being configured; it is computed later, when a task that needs it runs.
// The result is a Provider<Set<ResolvedArtifactResult>>.
val shippedArtifacts = shippedClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }

// Derive the shipped dependencies as sorted, de-duplicated "group:name:version" strings.
// `mapNotNull { it... as? ModuleComponentIdentifier }` keeps only normal external modules
// and drops anything that isn't one (e.g. file dependencies). This feeds the LICENSE
// task's `bundledCoordinates` input.
val shippedCoordinates =
    shippedArtifacts.map { set ->
        set.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()
    }

// Map each shipped jar's *file name* to its "group:name:version". The NOTICE task opens
// the jar files and uses this map to label each lifted notice with the module it came
// from (at that point the file is all it has to go on).
val jarNameToCoordinate =
    shippedArtifacts.map { set ->
        set.mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let { id ->
                artifact.file.name to "${id.group}:${id.module}:${id.version}"
            }
        }.toMap()
    }

// Create (register) the LICENSE task and wire its inputs/output. `register` is lazy — the
// task is configured/run only if the build needs it. `dependsOn("cyclonedxBom")` ensures
// the SBOM exists before this runs; each `.set(...)` connects one declared input.
val generateBinaryLicense =
    tasks.register<GenerateBinaryLicense>("generateBinaryLicense") {
        description = "Assembles the binary-release LICENSE (Apache-2.0 + SBOM-derived appendix)."
        group = "documentation"
        dependsOn("cyclonedxBom")
        baseLicense.set(licenseFile)
        sbom.set(layout.buildDirectory.file("reports/application.cdx.json"))
        bundledCoordinates.set(shippedCoordinates)
        outputFile.set(layout.buildDirectory.file("generated/license/LICENSE"))
    }

// Same for the NOTICE task. `jars.from(shippedClasspath)` hands it the shipped jar files
// to scan for their `META-INF/NOTICE` entries.
val generateBinaryNotice =
    tasks.register<GenerateBinaryNotice>("generateBinaryNotice") {
        description = "Assembles the binary-release NOTICE (project NOTICE + bundled dependency notices)."
        group = "documentation"
        jars.from(shippedClasspath)
        coordinateByJarName.set(jarNameToCoordinate)
        baseNotice.set(noticeFile)
        outputFile.set(layout.buildDirectory.file("generated/license/NOTICE"))
    }

// `metaInf { from(file) }` adds files to a jar's `META-INF/` directory. The source-form
// artifacts — the thin `jar`, `-sources`, `-javadoc` (everything except `bootJar`) — get
// the base LICENSE/NOTICE unchanged. `configureEach` applies this to each matching jar
// task lazily.
tasks.withType<Jar>().matching { it.name != "bootJar" }.configureEach {
    metaInf {
        from(licenseFile)
        from(noticeFile)
    }
}

// The binary artifact (the Spring Boot fat `bootJar`) instead gets the *generated* files.
// `dependsOn(...)` makes the generators run first; `from(task.flatMap { it.outputFile })`
// bundles each task's output into `META-INF/` (the lazy flatMap also wires the task
// dependency automatically).
tasks.named<Jar>("bootJar") {
    dependsOn(generateBinaryLicense, generateBinaryNotice)
    metaInf {
        from(generateBinaryLicense.flatMap { it.outputFile })
        from(generateBinaryNotice.flatMap { it.outputFile })
    }
}

// Run the LICENSE task — and therefore its completeness gate — as part of `check`, so a
// plain `./gradlew build` fails if a bundled dependency is missing from the SBOM.
tasks.named("check") { dependsOn(generateBinaryLicense) }
