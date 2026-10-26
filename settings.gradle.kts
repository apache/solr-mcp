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

plugins {
    id("com.gradle.develocity") version "4.6.0"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.8.0"
}

rootProject.name = "solr-mcp"

// Build scans go to the ASF's shared instance; https://infra.apache.org/gradle.html
develocity {
    server = "https://develocity.apache.org"
    projectId = "solr-mcp"

    buildScan {
        // GitHub Actions sets CI=true on every runner.
        // Kept local to this block: a script-level val would be captured by the onlyIf lambda
        // below as a script reference, which the configuration cache cannot serialize.
        val isCiBuild = providers.environmentVariable("CI").isPresent
        uploadInBackground = !isCiBuild

        // Publish from CI, or locally when opted in with -Pscan, and only with an access key
        // (DEVELOCITY_ACCESS_KEY). Fork PRs receive no secrets, so they never publish.
        val isScanOptIn = providers.gradleProperty("scan").isPresent
        publishing.onlyIf { (isCiBuild || isScanOptIn) && it.isAuthenticated }

        obfuscation {
            ipAddresses { addresses -> addresses.map { "0.0.0.0" } }
        }

        tag("JDK${Runtime.version().feature()}")
        // https://docs.github.com/en/actions/reference/variables-reference#default-environment-variables
        if (providers.environmentVariable("GITHUB_BASE_REF").isPresent) { // only set for PRs
            tag("PR")
        }
        providers.environmentVariable("GITHUB_WORKFLOW").orNull?.let { tag(it) }
    }
}

// Repositories are declared once here (not per project) so every project resolves
// dependencies from the same place. See
// https://docs.gradle.org/current/userguide/best_practices_dependencies.html#set_up_repositories_in_settings
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
