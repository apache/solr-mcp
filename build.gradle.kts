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

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.util.zip.ZipFile

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

// GraalVM Native Image (Opt-In)
// =============================
// `-Pnative` is the single switch that controls all native-related behavior:
//   - applies the graalvm-native plugin (registers nativeCompile / nativeTest)
//   - Spring Boot's bootBuildImage auto-configures for native (Paketo native-image
//     buildpack) when graalvm-native is on the classpath
//   - dockerIntegrationTest tags the image accordingly
// Without `-Pnative`, the graalvm-native plugin is not applied and bootBuildImage
// produces a plain JVM Paketo image.
val nativeBuild = project.hasProperty("native")

// Native image profile selector: -Pprofile=stdio (default) or -Pprofile=http.
// Determines the Spring profile active during AOT, which decides whether the
// resulting native binary serves stdio or http transport.
val nativeProfile: String = (project.findProperty("profile") as String?) ?: "stdio"

if (nativeBuild) {
    apply(plugin = "org.graalvm.buildtools.native")
    require(nativeProfile == "stdio" || nativeProfile == "http") {
        "Invalid -Pprofile=$nativeProfile; expected 'stdio' or 'http'"
    }
}

// Shared GraalVM native-image arguments used by both graalvmNative (local builds)
// and bootBuildImage (Docker builds via Paketo buildpacks).
val nativeImageBuildArgs =
    listOf(
        "--no-fallback",
        "-H:+ReportExceptionStackTraces",
        "--initialize-at-build-time=io.opentelemetry.api",
        "--initialize-at-build-time=io.opentelemetry.context",
        "--initialize-at-build-time=io.opentelemetry.instrumentation.api",
        "--initialize-at-build-time=io.opentelemetry.instrumentation.logback",
    )

group = "org.apache.solr"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

// LICENSE / NOTICE bundling (ASF release policy)
// ==============================================
// ASF policy requires every distributed artifact to carry LICENSE and NOTICE, and the
// correct contents differ between the *source* form and the *binary* form:
//
//   - Source-form artifacts (thin `jar`, `-sources`, `-javadoc`) contain only
//     ASF-authored code, so the base Apache-2.0 LICENSE + NOTICE are sufficient.
//   - The *binary* artifact (the Spring Boot fat `bootJar`) bundles third-party
//     bytecode. Per https://infra.apache.org/licensing-howto.html its LICENSE must
//     additionally enumerate each bundled dependency and link its license, and its
//     NOTICE must lift the NOTICE snippets of bundled Apache-licensed dependencies.
//
// Tooling:
//   - `generateBinaryLicense` builds the binary LICENSE = base Apache-2.0 + an
//     appendix derived from the CycloneDX SBOM (`cyclonedxBom`), filtered to the
//     shipped runtime classpath. The SBOM already resolves a license for every
//     bundled component — including Gradle-module-metadata-only ASF artifacts such
//     as SolrJ that POM-only scanners miss — so no per-dependency list is hand-kept.
//     It doubles as a gate: a shipped module missing from the SBOM, or carrying a
//     license not in config/license-policy.json, fails the build.
//   - `generateBinaryNotice` builds the binary NOTICE by aggregating the actual
//     META-INF/NOTICE files embedded in the bundled jars (the Maven-Shade
//     ApacheNoticeResourceTransformer approach), verbatim and de-duplicated.
// See https://www.apache.org/legal/release-policy.html#licensing-documentation

val binaryLicenseFile = layout.buildDirectory.file("generated/license/LICENSE")
val binaryNoticeFile = layout.buildDirectory.file("generated/license/NOTICE")

// What actually ships inside the fat jar is `productionRuntimeClasspath` — it excludes
// test/compile-only AND `developmentOnly` deps (which the bootJar does not bundle).
val shippedClasspath = configurations.named("productionRuntimeClasspath")
val runtimeArtifacts = shippedClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }

// Assemble the binary-release LICENSE: base Apache-2.0 + an appendix of every bundled
// dependency and a link to its license, sourced from the CycloneDX SBOM.
val generateBinaryLicense by
    tasks.registering {
        description =
            "Assembles the binary-release LICENSE (Apache-2.0 + third-party appendix from the SBOM)."
        group = "documentation"
        dependsOn(tasks.named("cyclonedxBom"))
        val baseLicense = rootProject.file("LICENSE")
        val policyFile = rootProject.file("config/license-policy.json")
        val sbomFile = layout.buildDirectory.file("reports/application.cdx.json")
        val artifacts = runtimeArtifacts
        inputs.file(baseLicense)
        inputs.file(policyFile)
        inputs.file(sbomFile)
        inputs.files(shippedClasspath)
        outputs.file(binaryLicenseFile)
        doLast {
            val slurper = groovy.json.JsonSlurper()

            @Suppress("UNCHECKED_CAST")
            val policy = slurper.parse(policyFile) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val allowed = ((policy["allowedLicenses"] as? List<String>) ?: emptyList()).toSet()

            @Suppress("UNCHECKED_CAST")
            val overrides = (policy["overrides"] as? Map<String, String>) ?: emptyMap()

            // Licenses of an SBOM component as (label, url?) pairs, de-duped by label.
            // (Local lambda, not a local fun/data class — those choke the kts compiler.)
            val licsOf = fun(component: Map<String, Any?>): List<Pair<String, String?>> {
                val out = LinkedHashMap<String, String?>()

                @Suppress("UNCHECKED_CAST")
                (component["licenses"] as? List<Map<String, Any?>>)?.forEach { node ->
                    @Suppress("UNCHECKED_CAST")
                    val lo = node["license"] as? Map<String, Any?>
                    if (lo != null) {
                        val id = lo["id"] as? String
                        val label = id ?: (lo["name"] as? String) ?: "Unspecified"
                        val url =
                            (lo["url"] as? String)
                                ?: id?.let { "https://spdx.org/licenses/$it.html" }
                        if (!out.containsKey(label)) out[label] = url
                    } else {
                        (node["expression"] as? String)?.let { if (!out.containsKey(it)) out[it] = null }
                    }
                }
                return out.map { it.key to it.value }
            }

            @Suppress("UNCHECKED_CAST")
            val sbom = slurper.parse(sbomFile.get().asFile) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val components = (sbom["components"] as? List<Map<String, Any?>>) ?: emptyList()
            val byGa = HashMap<String, List<Pair<String, String?>>>()
            val byGav = HashMap<String, List<Pair<String, String?>>>()
            components.forEach { c ->
                val g = c["group"] as? String
                val n = c["name"] as? String
                if (g != null && n != null) {
                    val ls = licsOf(c)
                    byGa["$g:$n"] = ls
                    (c["version"] as? String)?.let { byGav["$g:$n:$it"] = ls }
                }
            }

            // Source of truth for what ships: the resolved runtime artifacts, as
            // (group:name, version) pairs, deduped by coordinate and sorted.
            val bundled =
                artifacts
                    .get()
                    .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                    .map { "${it.group}:${it.module}" to it.version }
                    .distinctBy { it.first }
                    .sortedBy { it.first.lowercase() }

            val notInSbom = mutableListOf<String>()
            val disallowed = mutableListOf<String>()
            val rows = StringBuilder()
            bundled.forEach { (ga, version) ->
                val lics: List<Pair<String, String?>> =
                    overrides[ga]?.let { listOf(it to ("https://spdx.org/licenses/$it.html" as String?)) }
                        ?: byGav["$ga:$version"] ?: byGa[ga] ?: emptyList()
                if (lics.isEmpty()) {
                    notInSbom.add("$ga:$version")
                    return@forEach
                }
                lics.forEach { (label, _) -> if (label !in allowed) disallowed.add("$ga:$version -> $label") }
                rows
                    .append("- ")
                    .append(ga)
                    .append(':')
                    .append(version)
                    .append('\n')
                lics.forEach { (label, url) ->
                    rows.append("    License: ").append(label)
                    if (!url.isNullOrBlank()) rows.append(" — ").append(url)
                    rows.append('\n')
                }
            }
            if (notInSbom.isNotEmpty()) {
                throw GradleException(
                    "Bundled dependencies absent from the CycloneDX SBOM:\n" +
                        notInSbom.joinToString("\n") { "  - $it" } +
                        "\nEnsure cyclonedxBom covers the runtime classpath.",
                )
            }
            if (disallowed.isNotEmpty()) {
                throw GradleException(
                    "Bundled dependencies with a license not in config/license-policy.json:\n" +
                        disallowed.joinToString("\n") { "  - $it" } +
                        "\nAfter verifying, add the license to allowedLicenses, or add a " +
                        "group:name -> SPDX-id entry to overrides if the SBOM mislabels it.",
                )
            }

            val sb = StringBuilder()
            sb.append(baseLicense.readText().trimEnd()).append("\n\n\n")
            sb.append("=".repeat(78)).append('\n')
            sb.append("APACHE SOLR MCP SERVER — THIRD-PARTY DEPENDENCY LICENSES\n")
            sb.append("=".repeat(78)).append("\n\n")
            sb.append(
                "The binary distribution (the Spring Boot executable JAR) bundles the\n" +
                    "third-party dependencies listed below, derived from the bundled CycloneDX\n" +
                    "SBOM (META-INF/sbom/application.cdx.json). Each is provided under the license\n" +
                    "noted; refer to the linked license text for the full terms.\n\n",
            )
            sb.append(rows)
            val target = binaryLicenseFile.get().asFile
            target.parentFile.mkdirs()
            target.writeText(sb.toString())
        }
    }

// Assemble the binary-release NOTICE by lifting the META-INF/NOTICE files embedded in
// the bundled jars (verbatim, de-duplicated) on top of this project's own NOTICE.
val generateBinaryNotice by
    tasks.registering {
        description = "Assembles the binary-release NOTICE (project NOTICE + bundled dependency notices)."
        group = "documentation"
        val baseNotice = rootProject.file("NOTICE")
        val artifacts = runtimeArtifacts
        inputs.file(baseNotice)
        inputs.files(shippedClasspath)
        outputs.file(binaryNoticeFile)
        doLast {
            val noticeEntry = Regex("(^|/)META-INF/NOTICE(\\.txt|\\.md)?$", RegexOption.IGNORE_CASE)
            val seen = LinkedHashSet<String>()
            val sections = StringBuilder()
            artifacts
                .get()
                .mapNotNull { art ->
                    (art.id.componentIdentifier as? ModuleComponentIdentifier)?.let { it to art.file }
                }.sortedBy { "${it.first.group}:${it.first.module}".lowercase() }
                .forEach { (id, jar) ->
                    if (!jar.name.endsWith(".jar")) return@forEach
                    ZipFile(jar).use { zip ->
                        zip
                            .entries()
                            .asSequence()
                            .filter { noticeEntry.containsMatchIn(it.name) && !it.isDirectory }
                            .forEach { entry ->
                                val text =
                                    zip
                                        .getInputStream(entry)
                                        .bufferedReader()
                                        .readText()
                                        .trim()
                                if (text.isNotEmpty() && seen.add(text)) {
                                    sections.append("\n").append("-".repeat(78)).append('\n')
                                    sections.append("From ${id.group}:${id.module}:${id.version}:\n\n")
                                    sections.append(text).append('\n')
                                }
                            }
                    }
                }
            val sb = StringBuilder()
            sb.append(baseNotice.readText().trimEnd()).append('\n')
            if (sections.isNotEmpty()) {
                sb.append("\n\n").append("=".repeat(78)).append('\n')
                sb.append("NOTICES FROM BUNDLED THIRD-PARTY DEPENDENCIES (binary distribution)\n")
                sb.append("=".repeat(78)).append('\n')
                sb.append(sections)
            }
            val target = binaryNoticeFile.get().asFile
            target.parentFile.mkdirs()
            target.writeText(sb.toString())
        }
    }

// Source-form artifacts: base LICENSE + NOTICE.
tasks.withType<Jar>().matching { it.name != "bootJar" }.configureEach {
    metaInf {
        from(rootProject.file("LICENSE"))
        from(rootProject.file("NOTICE"))
    }
}

// Binary artifact (Spring Boot fat jar): generated LICENSE (with SBOM-derived appendix)
// + generated NOTICE (with lifted dependency notices).
tasks.named<BootJar>("bootJar") {
    dependsOn(generateBinaryLicense, generateBinaryNotice)
    metaInf {
        from(binaryLicenseFile)
        from(binaryNoticeFile)
    }
}

// Fail the build when a bundled dependency is unaccounted for (generateBinaryLicense gate).
tasks.named("check") { dependsOn(generateBinaryLicense) }

// Maven Publishing Configuration
// ==============================
// This configuration enables publishing the project artifacts to Maven repositories.
// The publishing block defines what artifacts are published and where they go.
//
// Artifacts Published:
// -------------------
// - Main JAR: The compiled application JAR
// - Sources JAR: Source code for IDE navigation and debugging
// - Javadoc JAR: Generated API documentation
//
// Publishing to Maven Local:
// -------------------------
// To install artifacts to your local Maven repository (~/.m2/repository):
//   ./gradlew publishToMavenLocal
//
// This is useful for:
// - Testing the library locally before publishing to a remote repository
// - Sharing artifacts between local projects during development
// - Verifying the published POM and artifact structure
//
// After publishing, artifacts will be available at:
//   ~/.m2/repository/org/apache/solr/solr-mcp/{version}/
//
// The publication includes:
// - solr-mcp-{version}.jar (main artifact)
// - solr-mcp-{version}-sources.jar (source code)
// - solr-mcp-{version}-javadoc.jar (API documentation)
// - solr-mcp-{version}.pom (Maven POM with dependencies)
publishing {
    publications {
        create<MavenPublication>("maven") {
            // Include the main JAR and all artifacts from the java component
            // This automatically includes sources and javadoc JARs when
            // withSourcesJar() and withJavadocJar() are configured above
            from(components["java"])
        }
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {

    developmentOnly(libs.bundles.spring.boot.dev)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.solr.solrj)
    implementation(libs.commons.csv)
    // JSpecify for nullability annotations
    implementation(libs.jspecify)

    implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.11.0"))
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
    implementation(libs.micrometer.tracing.bridge.otel)

    implementation("io.micrometer:micrometer-registry-prometheus")

    // Security
    implementation(libs.mcp.server.security)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    // Error Prone and NullAway for null safety analysis
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

// Configures Spring Boot plugin to generate build metadata at build time
// This creates META-INF/build-info.properties containing:
//   - build.artifact: The artifact name (e.g., "solr-mcp")
//   - build.group: The group ID (e.g., "org.apache.solr")
//   - build.name: The project name
//   - build.version: The version (e.g., "1.0.0-SNAPSHOT")
//   - build.time: The timestamp when the build was executed
//
// When it executes:
//   - bootBuildInfo task runs before processResources during any build
//   - Triggered by: ./gradlew build, bootJar, test, classes, etc.
//   - The generated file is included in the JAR's classpath
//   - Tests can access it via: getResourceAsStream("/META-INF/build-info.properties")
//
// Use cases:
//   - Runtime version introspection via Spring Boot Actuator
//   - Dynamic JAR path resolution in tests (e.g., ClientStdio.java)
//   - Application metadata exposure through /actuator/info endpoint
springBoot {
    buildInfo()
}

tasks.withType<Test> {
    useJUnitPlatform {
        // Only exclude docker integration tests from regular test runs, not from dockerIntegrationTest
        if (name != "dockerIntegrationTest") {
            excludeTags("docker-integration")
        }
    }
    // McpClientStdioIntegrationTest spawns `java -jar build/libs/<bootJar>` as a
    // subprocess. Without an explicit dependency, `:test` runs before `:bootJar`
    // (e.g., when invoked transitively by `nativeTest`), the jar is missing, the
    // subprocess silently fails, and the MCP client times out on initialize().
    if (name != "dockerIntegrationTest") {
        dependsOn(tasks.bootJar)
    }
    // Forward solr.test.image system property to test JVMs for Solr version compatibility testing
    systemProperty("solr.test.image", System.getProperty("solr.test.image", "solr:9.9-slim"))
    if (name != "dockerIntegrationTest") {
        finalizedBy(tasks.jacocoTestReport)
    }
}

tasks.register<Test>("unitTest") {
    description = "Runs unit tests only (no Testcontainers)"
    group = "verification"

    useJUnitPlatform {
        excludeTags("integration", "docker-integration")
    }

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    finalizedBy(tasks.jacocoTestReport)

    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/unitTest"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/unitTest"))
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-based integration tests"
    group = "verification"

    useJUnitPlatform {
        includeTags("integration")
    }

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    systemProperty("solr.test.image", System.getProperty("solr.test.image", "solr:9.9-slim"))

    mustRunAfter(tasks.named("unitTest"))
    finalizedBy(tasks.jacocoTestReport)

    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/integrationTest"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/integrationTest"))
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    // Exclude docker integration tests from coverage
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/DockerImageStdioIntegrationTest*.class",
                        "**/DockerImageHttpIntegrationTest*.class",
                    )
                }
            },
        ),
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableAllChecks.set(true) // Other error prone checks are disabled
        option("NullAway:OnlyNullMarked", "true") // Enable nullness checks only in null-marked code
        error("NullAway") // bump checks from warnings (default) to errors
    }
}

tasks.build {
    dependsOn(tasks.spotlessApply)
}

spotless {
    java {
        target("src/**/*.java")
        // Use Eclipse JDT formatter to avoid google-java-format's incompatibility
        // with cutting-edge JDKs (e.g., 25) which can trigger NoSuchMethodError
        // against internal javac classes. Override only the annotation-argument
        // alignment so multi-arg @Mcp* annotations render one-arg-per-line.
        eclipse().configFile("config/spotless/eclipse-java-formatter.properties")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        importOrder()
        formatAnnotations()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

// Docker Integration Test Task
// =============================
// Runs integration tests against the appropriate Docker image:
//
//   ./gradlew dockerIntegrationTest                            # Jib JVM image — both stdio + http
//   ./gradlew dockerIntegrationTest -Pnative                   # Paketo native-stdio image
//   ./gradlew dockerIntegrationTest -Pnative -Pprofile=http    # Paketo native-http image
//
// Test selection per image mode (Image × Mode matrix in CLAUDE.md):
//   Jib JVM: stdio smoke + http endpoint + MCP stdio (Jib has clean stdout)
//   Native stdio: stdio smoke + MCP stdio (no http servlet beans)
//   Native http: http endpoint test (AOT'd for servlet)
tasks.register<Test>("dockerIntegrationTest") {
    description = "Runs integration tests for the Docker image"
    group = "verification"

    // Always run this task, don't use Gradle's up-to-date checking
    outputs.upToDateWhen { false }

    val dockerAvailable =
        try {
            val process = ProcessBuilder("docker", "info").start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }

    if (!dockerAvailable) {
        doFirst {
            throw GradleException(
                "Docker is not available. Please ensure Docker is installed and running.",
            )
        }
    }

    if (dockerAvailable) {
        if (nativeBuild) {
            dependsOn(tasks.named("bootBuildImage"))
        } else {
            dependsOn(tasks.jibDockerBuild)
        }
    }

    useJUnitPlatform {
        includeTags("docker-integration")
    }

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    mustRunAfter(tasks.test)
    systemProperty("junit.jupiter.execution.timeout.default", "5m")

    if (nativeBuild) {
        // Native images are tagged solr-mcp:<v>-native-<profile>; tests append
        // this suffix to BuildInfoReader.getDockerImageName().
        systemProperty("solr.mcp.docker.image.tag.suffix", "-native-$nativeProfile")
        if (nativeProfile == "stdio") {
            // stdio binary has no servlet beans → HTTP test would fail.
            exclude("**/DockerImageHttpIntegrationTest*")
        } else {
            // http binary has no stdio MCP transport → stdio MCP test would fail.
            // Smoke-only stdio test (DockerImageStdioIntegrationTest) is also
            // skipped because it spawns the container expecting stdin to stay open.
            exclude("**/DockerImageMcpClientStdioIntegrationTest*")
            exclude("**/DockerImageStdioIntegrationTest*")
        }
    }
    // For Jib JVM (no -Pnative): no exclusions — all three test classes run.

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/dockerIntegrationTest"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/dockerIntegrationTest"))
    }
}

// Docker images: Jib (JVM) + Paketo bootBuildImage (native, per-profile)
// ======================================================================
// Three image artifacts cover the full stdio/http × jvm/native matrix:
//
//   ./gradlew jibDockerBuild                              # JVM:    solr-mcp:<v>           (both stdio + http via PROFILES)
//   ./gradlew bootBuildImage -Pnative                     # Native: solr-mcp:<v>-native-stdio   (stdio only, AOT pinned)
//   ./gradlew bootBuildImage -Pnative -Pprofile=http      # Native: solr-mcp:<v>-native-http    (http only, AOT pinned)
//
// Why three images:
// - Jib's JVM image has clean stdout (java -jar entrypoint, no launcher script),
//   so a single image serves both stdio and http via runtime PROFILES.
// - Paketo's JVM image is unsuitable for stdio (libjvm helpers pollute stdout —
//   see https://github.com/paketo-buildpacks/libjvm/issues/482).
// - Native images must AOT-pin to one profile because Spring AOT bakes
//   spring.main.web-application-type into the binary; activating both profiles
//   picks `servlet` (http overrides stdio) and forces Tomcat to start regardless
//   of runtime PROFILES, breaking stdio. Hence one native image per profile.
//
// Multi-arch (amd64 + arm64) is handled in CI via a GitHub Actions matrix.

// Jib JVM image — clean stdout, multi-arch, both stdio and http modes.
jib {
    dockerClient {
        executable = System.getenv("DOCKER_EXECUTABLE")
            ?: when {
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isMacOsX -> "/usr/local/bin/docker"
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isLinux -> "/usr/bin/docker"
                org.gradle.internal.os.OperatingSystem
                    .current()
                    .isWindows ->
                    "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"
                else -> "docker"
            }
    }
    from {
        image = "eclipse-temurin:25-jre"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = "solr-mcp:$version"
        tags = setOf("latest")
    }
    container {
        environment =
            mapOf(
                "PROFILES" to "stdio",
                "SPRING_DOCKER_COMPOSE_ENABLED" to "false",
            )
        jvmFlags = listOf("-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0")
        mainClass = "org.apache.solr.mcp.server.Main"
        ports = listOf("8080")
        labels.set(
            mapOf(
                "org.opencontainers.image.title" to "Solr MCP Server",
                "org.opencontainers.image.description" to "Spring AI MCP Server for Apache Solr",
                "org.opencontainers.image.version" to version.toString(),
                "org.opencontainers.image.vendor" to "Apache Software Foundation",
                "org.opencontainers.image.licenses" to "Apache-2.0",
                "io.modelcontextprotocol.server.name" to "io.github.apache/solr-mcp",
            ),
        )
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    if (nativeBuild) {
        imageName.set("solr-mcp:$version-native-$nativeProfile")
        tags.set(listOf("solr-mcp:latest-native-$nativeProfile"))
        environment.set(
            mapOf(
                "BP_JVM_VERSION" to "25",
                "BP_NATIVE_IMAGE_BUILD_ARGUMENTS" to nativeImageBuildArgs.joinToString(" "),
                "SPRING_PROFILES_ACTIVE" to nativeProfile,
                "BPE_DEFAULT_PROFILES" to nativeProfile,
                "BPE_DEFAULT_SPRING_DOCKER_COMPOSE_ENABLED" to "false",
            ),
        )
    }
    // When -Pnative is not set, this task is unreachable (graalvm-native plugin
    // not applied → Spring Boot's auto-config doesn't extend bootBuildImage for
    // native, but the task still exists). We use Jib for JVM images, so this
    // branch is intentionally a no-op rather than producing a confusing image.
}

// ─────────────────────────────────────────────────────────────────────────────
// GraalVM Native Image configuration (only applied when -Pnative is set)
// ─────────────────────────────────────────────────────────────────────────────
// The `org.graalvm.buildtools.native` plugin registers `nativeCompile` and
// `nativeTest` tasks and triggers Spring Boot's bootBuildImage to use the
// Paketo native-image buildpack.
//
// AOT runs with the stdio profile only. The http profile sets
// spring.main.web-application-type=servlet, which Spring AOT bakes in at
// build time — activating both profiles produces a binary that always starts
// Tomcat regardless of runtime PROFILES, breaking STDIO. The native image is
// therefore STDIO-only.
if (nativeBuild) {
    extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension>("graalvmNative") {
        binaries {
            named("main") {
                imageName.set("solr-mcp")
                buildArgs.addAll(nativeImageBuildArgs)
            }
            named("test") {
                // Test binary inherits OTel --initialize-at-build-time entries from the
                // shared args (filtering out --no-fallback and -H:+ReportExceptionStackTraces),
                // plus test-specific SDK entries.
                buildArgs.addAll(
                    nativeImageBuildArgs.filter { it.startsWith("--initialize-at-build-time=") },
                )
                buildArgs.addAll(
                    // opentelemetry-sdk-testing adds a ServiceLoader provider
                    // (SettableContextStorageProvider) loaded at build time.
                    "--initialize-at-build-time=io.opentelemetry.sdk",
                    // AndroidFriendlyRandomHolder creates a java.util.Random in <clinit>,
                    // which GraalVM forbids in the image heap (stale seed).
                    "--initialize-at-run-time=io.opentelemetry.sdk.internal.AndroidFriendlyRandomHolder",
                    // The GraalVM native JUnit launcher embeds test discovery results
                    // (InternalTestPlan, descriptors, TestTag, etc.) in the image heap.
                    "--initialize-at-build-time=org.junit.platform.launcher",
                    "--initialize-at-build-time=org.junit.platform.engine",
                    "--initialize-at-build-time=org.junit.jupiter.engine.descriptor",
                )
            }
        }
    }
    tasks.named<JavaExec>("processAot") {
        args("--spring.profiles.active=$nativeProfile")
    }
}
