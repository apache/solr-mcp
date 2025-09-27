import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("org.sonarqube") version "6.2.0.5505"
    id("net.ltgt.errorprone") version "4.2.0"
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

extra["springAiVersion"] = "1.0.2"


dependencies {

    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("org.apache.solr:solr-solrj:9.8.1") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("org.apache.commons:commons-csv:1.10.0")

    // JSpecify for nullability annotations
    implementation("org.jspecify:jspecify:1.0.0")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    // Error Prone and NullAway for null safety analysis
    errorprone("com.google.errorprone:error_prone_core:2.38.0")
    errorprone("com.uber.nullaway:nullaway:0.12.7")

    compileOnly("org.projectlombok:lombok")

    annotationProcessor("org.projectlombok:lombok")

    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:solr:1.21.3")
    testImplementation("org.springframework.ai:spring-ai-starter-mcp-client")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        // Align Jetty family to 10.x compatible with SolrJ 9.x
        mavenBom("org.eclipse.jetty:jetty-bom:10.0.22")
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


tasks.named("sonar") {
    dependsOn("test", "jacocoTestReport")
}

sonar {
    properties {
        property("sonar.projectKey", "adityamparikh_solr-mcp-server")
        property("sonar.organization", "adityamparikh")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableAllChecks.set(true) // Other error prone checks are disabled
        option("NullAway:OnlyNullMarked", "true") // Enable nullness checks only in null-marked code
        error("NullAway") // bump checks from warnings (default) to errors
    }
}