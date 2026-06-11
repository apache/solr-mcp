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
// ASF policy requires distinct LICENSE/NOTICE for the source form and the binary form,
// because the binary (the Spring Boot fat `bootJar`) bundles third-party bytecode. See
// https://infra.apache.org/licensing-howto.html. This plugin:
//
//   - bundles the base Apache-2.0 LICENSE + NOTICE into the source-form jars as-is;
//   - generates, for the bootJar, a LICENSE with a third-party appendix derived from the
//     CycloneDX SBOM and a NOTICE that lifts bundled dependencies' notices, and a gate
//     that fails the build for an unaccounted-for or disallowed dependency.
//
// Apply this AFTER the Spring Boot and CycloneDX plugins so `productionRuntimeClasspath`
// and the `cyclonedxBom` task exist.

import org.apache.solr.mcp.build.GenerateBinaryLicense
import org.apache.solr.mcp.build.GenerateBinaryNotice
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

val licenseFile = layout.projectDirectory.file("LICENSE")
val noticeFile = layout.projectDirectory.file("NOTICE")

// What actually ships inside the fat jar: productionRuntimeClasspath excludes
// test/compile-only and developmentOnly deps that the bootJar does not bundle.
val shippedClasspath = configurations.named("productionRuntimeClasspath")
val shippedArtifacts = shippedClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }

val shippedCoordinates =
    shippedArtifacts.map { set ->
        set.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()
    }

val jarNameToCoordinate =
    shippedArtifacts.map { set ->
        set.mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let { id ->
                artifact.file.name to "${id.group}:${id.module}:${id.version}"
            }
        }.toMap()
    }

val generateBinaryLicense =
    tasks.register<GenerateBinaryLicense>("generateBinaryLicense") {
        description = "Assembles the binary-release LICENSE (Apache-2.0 + SBOM-derived appendix)."
        group = "documentation"
        dependsOn("cyclonedxBom")
        baseLicense.set(licenseFile)
        policyFile.set(layout.projectDirectory.file("config/license-policy.json"))
        sbom.set(layout.buildDirectory.file("reports/application.cdx.json"))
        bundledCoordinates.set(shippedCoordinates)
        outputFile.set(layout.buildDirectory.file("generated/license/LICENSE"))
    }

val generateBinaryNotice =
    tasks.register<GenerateBinaryNotice>("generateBinaryNotice") {
        description = "Assembles the binary-release NOTICE (project NOTICE + bundled dependency notices)."
        group = "documentation"
        jars.from(shippedClasspath)
        coordinateByJarName.set(jarNameToCoordinate)
        baseNotice.set(noticeFile)
        outputFile.set(layout.buildDirectory.file("generated/license/NOTICE"))
    }

// Source-form artifacts (thin jar, -sources, -javadoc): base LICENSE + NOTICE as-is.
tasks.withType<Jar>().matching { it.name != "bootJar" }.configureEach {
    metaInf {
        from(licenseFile)
        from(noticeFile)
    }
}

// Binary artifact (Spring Boot fat jar): generated LICENSE + NOTICE.
tasks.named<Jar>("bootJar") {
    dependsOn(generateBinaryLicense, generateBinaryNotice)
    metaInf {
        from(generateBinaryLicense.flatMap { it.outputFile })
        from(generateBinaryNotice.flatMap { it.outputFile })
    }
}

// Gate: a bundled dependency that is unaccounted-for or disallowed fails the build.
tasks.named("check") { dependsOn(generateBinaryLicense) }
