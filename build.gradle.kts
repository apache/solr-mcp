import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    jacoco
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
}

group = "org.apache.solr"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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
