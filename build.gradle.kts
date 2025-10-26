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
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    jacoco
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
    alias(libs.plugins.jib)
}

group = "org.apache.solr"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.solr.solrj) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.commons.csv)
    // JSpecify for nullability annotations
    implementation(libs.jspecify)

    // Error Prone and NullAway for null safety analysis
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
        // Align Jetty family to 10.x compatible with SolrJ 9.x
        mavenBom("org.eclipse.jetty:jetty-bom:${libs.versions.jetty.get()}")
    }
}

// Configures Spring Boot plugin to generate build metadata at build time
// This creates META-INF/build-info.properties containing:
//   - build.artifact: The artifact name (e.g., "solr-mcp-server")
//   - build.group: The group ID (e.g., "org.apache.solr")
//   - build.name: The project name
//   - build.version: The version (e.g., "0.0.1-SNAPSHOT")
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
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
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
        googleJavaFormat().aosp().reflowLongStrings()
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
//    Creates image: solr-mcp-server:0.0.1-SNAPSHOT
//
// 2. Build to local tar file (no Docker required):
//    ./gradlew jibBuildTar
//    Creates: build/jib-image.tar
//    Load with: docker load < build/jib-image.tar
//
// 3. Push to Docker Hub (requires authentication):
//    docker login
//    ./gradlew jib -Djib.to.image=dockerhub-username/solr-mcp-server:0.0.1-SNAPSHOT
//
// 4. Push to GitHub Container Registry (requires authentication):
//    echo $GITHUB_TOKEN | docker login ghcr.io -u GITHUB_USERNAME --password-stdin
//    ./gradlew jib -Djib.to.image=ghcr.io/github-username/solr-mcp-server:0.0.1-SNAPSHOT
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
// Environment Variables:
// ----------------------
// The container is pre-configured with:
// - SPRING_DOCKER_COMPOSE_ENABLED=false (Docker Compose disabled in container)
// - SOLR_URL=http://host.docker.internal:8983/solr/ (default Solr connection)
//
// These can be overridden at runtime:
//   docker run -e SOLR_URL=http://custom-solr:8983/solr/ solr-mcp-server:0.0.1-SNAPSHOT
jib {
    from {
        // Use Eclipse Temurin JRE 21 as the base image
        // Temurin is the open-source build of OpenJDK from Adoptium
        image = "eclipse-temurin:21-jre"

        // Multi-platform support for both AMD64 and ARM64 architectures
        // This allows the image to run on x86_64 machines and Apple Silicon (M1/M2/M3)
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
        // Default image name (can be overridden with -Djib.to.image=...)
        // Format: repository/image-name:tag
        image = "solr-mcp-server:${version}"

        // Tags to apply to the image
        // The version tag is applied by default, plus "latest" tag
        tags = setOf("latest")
    }

    container {
        // Container environment variables
        // These are baked into the image but can be overridden at runtime
        environment = mapOf(
            // Disable Spring Boot Docker Compose support when running in container
            "SPRING_DOCKER_COMPOSE_ENABLED" to "false",

            // Default Solr URL using host.docker.internal to reach host machine
            // On Linux, use --add-host=host.docker.internal:host-gateway
            "SOLR_URL" to "http://host.docker.internal:8983/solr/"
        )

        // JVM flags for containerized environments
        // These optimize the JVM for running in containers
        jvmFlags = listOf(
            // Use container-aware memory settings
            "-XX:+UseContainerSupport",
            // Set max RAM percentage (default 75%)
            "-XX:MaxRAMPercentage=75.0"
        )

        // Main class to run (auto-detected from Spring Boot plugin)
        // mainClass is automatically set by Spring Boot Gradle plugin

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
                "org.opencontainers.image.licenses" to "Apache-2.0"
            )
        )
    }
}
