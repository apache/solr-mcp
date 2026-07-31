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

// This file builds buildSrc itself (not the main project). See buildSrc/README.md.
plugins {
    // Lets us write Gradle build logic — tasks and the convention plugin — in Kotlin,
    // and turns the `*.gradle.kts` files under src/main/kotlin into apply-by-id plugins.
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    // Hosts the Apache RAT plugin marker below — it is not published to Maven Central.
    gradlePluginPortal()
}

dependencies {
    // Makes the Apache RAT plugin (id `org.nosphere.apache.rat`) available to the
    // `org.apache.solr.mcp.rat` convention plugin's `plugins {}` block. The version lives
    // here (mirroring how the junit dep below is pinned) since buildSrc does not read the
    // root project's version catalog. Latest release as of writing; bump in lockstep with
    // the plugin's upstream releases.
    implementation("org.nosphere.apache.rat:org.nosphere.apache.rat.gradle.plugin:0.8.1")

    // Only used by the task unit tests under src/test (the main code needs no extra deps;
    // the Gradle API is provided by the kotlin-dsl plugin).
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
