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
    alias(libs.plugins.graalvm.native)
}

// GraalVM Native Image (Opt-In)
// =============================
// Invoked via `./gradlew ... -Pnative`. See docs/specs/graalvm-native-image.md.
// When true:
//   - AOT tasks (processAot) run with spring.profiles.active=stdio so that
//     security autoconfig exclusions from application-stdio.properties are
//     applied during hint generation.
//   - graalvmNative plugin configures nativeCompile / nativeTest tasks.
//   - Native Docker images use bootBuildImage (see separate configuration).
// Jib is always JVM-only; it is NOT used for native images.
val nativeBuild = project.hasProperty("native")

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
    // Forward solr.test.image system property to test JVMs for Solr version compatibility testing
    systemProperty("solr.test.image", System.getProperty("solr.test.image", "solr:9.9-slim"))
    if (name != "dockerIntegrationTest") {
        finalizedBy(tasks.jacocoTestReport)
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
// This task runs integration tests for the Docker image produced by Jib.
// It is separate from the regular test task and must be explicitly invoked.
//
// Usage:
//   ./gradlew dockerIntegrationTest
//
// Prerequisites:
//   - Docker must be installed and running
//   - The task will automatically build the Docker image using jibDockerBuild
//
// The task:
//   - Checks if Docker is available
//   - Builds the Docker image using Jib (if Docker is available)
//   - Runs tests tagged with "docker-integration"
//   - Uses the same test configuration as regular tests
//
// Notes:
//   - If Docker is not available, the task will fail with a helpful error message
//   - The test will verify the Docker image starts correctly and remains stable
//   - Tests run in isolation from regular unit tests
tasks.register<Test>("dockerIntegrationTest") {
    description = "Runs integration tests for the Docker image"
    group = "verification"

    // Always run this task, don't use Gradle's up-to-date checking
    // Docker images can change without Gradle knowing
    outputs.upToDateWhen { false }

    // Check if Docker is available
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

    // Depend on building the Docker image first (only if Docker is available)
    if (dockerAvailable) {
        if (nativeBuild) {
            dependsOn(tasks.named("bootBuildImage"))
        } else {
            dependsOn(tasks.jibDockerBuild)
        }
    }

    // Configure test task to only run docker integration tests
    useJUnitPlatform {
        includeTags("docker-integration")
    }

    // Use the same test classpath and configuration as regular tests
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Ensure this doesn't trigger the regular test task or jacocoTestReport
    mustRunAfter(tasks.test)

    // Set longer timeout for Docker tests
    systemProperty("junit.jupiter.execution.timeout.default", "5m")

    // When invoked as `./gradlew dockerIntegrationTest -Pnative`, the Jib
    // image produced is `solr-mcp:<version>-native`. BuildInfoReader only
    // knows the plain version, so pass the tag override through a system
    // property that the integration test can read.
    if (nativeBuild) {
        systemProperty("solr.mcp.docker.image.tag.suffix", "-native")
    }

    // Output test results
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    // Generate separate test report in a different directory
    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/dockerIntegrationTest"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/dockerIntegrationTest"))
    }
}

// Jib Plugin Configuration
// =========================
// Jib is a Gradle plugin that builds optimized Docker images without requiring Docker installed.
// It creates layered images for faster rebuilds and smaller image sizes.
//
// Key features:
// - Multi-platform support (amd64 and arm64)
// - No Docker daemon required
// - Reproducible builds
// - Optimized layering for faster deployments
//
// Building Images:
// ----------------
// 1. Build to Docker daemon (requires Docker installed):
//    ./gradlew jibDockerBuild
//    Creates image: solr-mcp:1.0.0-SNAPSHOT
//
// 2. Push to Docker Hub (requires authentication):
//    docker login
//    ./gradlew jib -Djib.to.image=dockerhub-username/solr-mcp:1.0.0-SNAPSHOT
//
// 3. Push to GitHub Container Registry (requires authentication):
//    echo $GITHUB_TOKEN | docker login ghcr.io -u GITHUB_USERNAME --password-stdin
//    ./gradlew jib -Djib.to.image=ghcr.io/github-username/solr-mcp:1.0.0-SNAPSHOT
//
// Authentication:
// ---------------
// For Docker Hub:
//   docker login
//
// For GitHub Container Registry:
//   Create a Personal Access Token (classic) with write:packages scope at:
//   https://github.com/settings/tokens
//   Then authenticate:
//   echo YOUR_TOKEN | docker login ghcr.io -u YOUR_USERNAME --password-stdin
//
// Alternative: Set credentials in ~/.gradle/gradle.properties:
//   jib.to.auth.username=YOUR_USERNAME
//   jib.to.auth.password=YOUR_TOKEN_OR_PASSWORD
//
// Docker Executable Configuration:
// ---------------------------------
// Jib needs to find the Docker executable to build images. By default, it uses these paths:
// - macOS: /usr/local/bin/docker
// - Linux: /usr/bin/docker
// - Windows: C:\Program Files\Docker\Docker\resources\bin\docker.exe
//
// If Docker is installed in a different location, set the DOCKER_EXECUTABLE environment variable:
//   export DOCKER_EXECUTABLE=/custom/path/to/docker
//   ./gradlew jibDockerBuild
//
// Or in gradle.properties:
//   systemProp.DOCKER_EXECUTABLE=/custom/path/to/docker
//
// Environment Variables:
// ----------------------
// The container is pre-configured with:
// - SPRING_DOCKER_COMPOSE_ENABLED=false (Docker Compose disabled in container)
// - SOLR_URL=http://host.docker.internal:8983/solr/ (default Solr connection)
//
// These can be overridden at runtime:
//   docker run -e SOLR_URL=http://custom-solr:8983/solr/ solr-mcp:1.0.0-SNAPSHOT
jib {
    // Configure Docker client executable path
    // This ensures Jib can find Docker even if it's not in Gradle's PATH
    // Can be overridden with environment variable: DOCKER_EXECUTABLE=/path/to/docker
    dockerClient {
        executable = System.getenv("DOCKER_EXECUTABLE") ?: when {
            // macOS with Docker Desktop
            org.gradle.internal.os.OperatingSystem
                .current()
                .isMacOsX -> "/usr/local/bin/docker"
            // Linux (most distributions)
            org.gradle.internal.os.OperatingSystem
                .current()
                .isLinux -> "/usr/bin/docker"
            // Windows with Docker Desktop
            org.gradle.internal.os.OperatingSystem
                .current()
                .isWindows -> "C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"
            // Fallback to PATH lookup
            else -> "docker"
        }
    }

    from {
        image = "eclipse-temurin:25-jre"

        // Multi-arch: build for both amd64 and arm64
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
        // Disable Spring Boot Docker Compose support when running in container.
        // Docker Compose integration is disabled in the container image.
        // It is only useful for local development (HTTP profile) where
        // the host has Docker and a compose.yaml. Inside a container,
        // Docker Compose cannot start sibling containers without a
        // Docker socket mount, so it must be turned off.
        // The application-stdio.properties also disables it for STDIO mode.
        environment = mapOf("SPRING_DOCKER_COMPOSE_ENABLED" to "false")

        jvmFlags =
            listOf(
                // Use container-aware memory settings
                "-XX:+UseContainerSupport",
                // Set max RAM percentage (default 75%)
                "-XX:MaxRAMPercentage=75.0",
            )

        // Explicitly set main class to avoid ASM scanning issues with newer Java versions
        mainClass = "org.apache.solr.mcp.server.Main"

        // Port exposures (for documentation purposes)
        // The application doesn't expose ports by default (STDIO mode)
        // If running in HTTP mode, the port would be 8080
        ports = listOf("8080")

        // Labels for image metadata
        labels.set(
            mapOf(
                "org.opencontainers.image.title" to "Solr MCP Server",
                "org.opencontainers.image.description" to "Spring AI MCP Server for Apache Solr",
                "org.opencontainers.image.version" to version.toString(),
                "org.opencontainers.image.vendor" to "Apache Software Foundation",
                "org.opencontainers.image.licenses" to "Apache-2.0",
                // MCP Registry annotation for server discovery
                "io.modelcontextprotocol.server.name" to "io.github.apache/solr-mcp",
            ),
        )
    }
}

// Native Docker image via Spring Boot Buildpacks
// ===============================================
// `bootBuildImage` compiles the native binary inside a Paketo builder
// container, so it works on any host OS and CPU architecture (macOS
// Apple Silicon, Linux x86_64, etc.). Jib cannot do this because
// `nativeCompile` produces a host-OS binary (Mach-O on macOS) that
// cannot run in a Linux container.
//
// This task is always configured for native builds (BP_NATIVE_IMAGE=true).
// For JVM Docker images, use Jib via `./gradlew jibDockerBuild`.
//
// Usage:
//   ./gradlew bootBuildImage                   # Build native Docker image
//   ./gradlew dockerIntegrationTest -Pnative   # Test the native image
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    imageName.set("solr-mcp:$version-native")
    tags.set(listOf("solr-mcp:latest-native"))
    environment.set(
        mapOf(
            "BP_NATIVE_IMAGE" to "true",
            "BP_NATIVE_IMAGE_BUILD_ARGUMENTS" to nativeImageBuildArgs.joinToString(" "),
            "BP_JVM_VERSION" to "25",
        ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// GraalVM Native Image configuration
// ─────────────────────────────────────────────────────────────────────────────
// The `org.graalvm.buildtools.native` plugin registers `nativeCompile` and
// `nativeTest` tasks. They are only useful when a GraalVM toolchain is
// available; the plugin's presence does not affect regular JVM builds.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("solr-mcp")
            // Uses shared nativeImageBuildArgs which include --no-fallback,
            // -H:+ReportExceptionStackTraces, and OTel --initialize-at-build-time entries.
            // OTel instrumentation BOM 2.11.0 does not ship native-image metadata.
            // NOTE: do NOT use io.opentelemetry.instrumentation (too broad) — that
            // would catch Spring autoconfigure CGLIB proxies which cannot be build-time
            // initialized.
            buildArgs.addAll(nativeImageBuildArgs)
        }
    }
    // Tests that are incompatible with native image are annotated
    // @DisabledInNativeImage and skipped during nativeTest:
    //   - Mockito-based tests (ByteBuddy cannot generate classes at run time)
    //   - SolrJ indexing/query integration tests (response parsing uses
    //     reflection that lacks native-image metadata)
    // Collection management, schema, and observability integration tests
    // run normally in the native binary.
    binaries {
        named("test") {
            // The test binary inherits the OTel --initialize-at-build-time entries
            // from the shared args (filtering out --no-fallback and
            // -H:+ReportExceptionStackTraces), plus test-specific SDK entries.
            buildArgs.addAll(
                nativeImageBuildArgs.filter { it.startsWith("--initialize-at-build-time=") },
            )
            buildArgs.addAll(
                // opentelemetry-sdk-testing on the test classpath adds a ServiceLoader
                // provider (SettableContextStorageProvider) that is loaded at build time.
                "--initialize-at-build-time=io.opentelemetry.sdk",
                // AndroidFriendlyRandomHolder creates a java.util.Random in <clinit>,
                // which GraalVM forbids in the image heap (stale seed).
                "--initialize-at-run-time=io.opentelemetry.sdk.internal.AndroidFriendlyRandomHolder",
            )
        }
    }
}

// When -Pnative is present, pin spring.profiles.active=stdio on the AOT
// processor so application-stdio.properties (which excludes
// SecurityAutoConfiguration + ManagementWebSecurityAutoConfiguration) is
// applied while hint generation runs. Test AOT is intentionally left alone
// because individual @SpringBootTest classes set their own profiles.
if (nativeBuild) {
    tasks.named<JavaExec>("processAot") {
        args("--spring.profiles.active=stdio")
    }
}
