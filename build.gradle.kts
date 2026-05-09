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
    implementation(libs.spring.boot.starter.validation)
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
        // against internal javac classes.
        eclipse()
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
