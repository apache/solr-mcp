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

// Convention plugin: Apache RAT (Release Audit Tool) license-header enforcement.
//
// For readers new to Gradle: this `.gradle.kts` file under buildSrc is a "precompiled
// script plugin". Gradle compiles it into a plugin whose id is the file name
// (`org.apache.solr.mcp.rat`); the root build applies it with one line,
// `id("org.apache.solr.mcp.rat")`. The `plugins {}` block below applies the third-party
// RAT plugin (made available to buildSrc by the `org.nosphere.apache.rat...` dependency
// in buildSrc/build.gradle.kts), and the body configures its `rat` task.
//
// RAT verifies that every scanned file carries an Apache license header. The plugin wires
// its `rat` task into `check`, so a plain `./gradlew build` runs it; the report lands at
// build/reports/rat/index.html.
//
// Exclusions come from two sources so patterns are not duplicated:
//   1. .gitignore — reused as the single source of truth for everything git already
//      ignores (build output, .gradle, IDE dirs, *.iml, out/, bin/, .vscode, etc.).
//      RatExcludes (in this same buildSrc) translates each entry into a RAT (Ant) glob;
//      see that class for the gitignore→glob mapping rules.
//   2. The explicit list below — only *tracked* files that RAT would scan but that
//      legitimately carry no Apache header (binaries, data without a comment syntax,
//      docs, tool/infra config, and LICENSE/NOTICE themselves).

import org.apache.solr.mcp.build.RatExcludes
import org.nosphere.apache.rat.RatTask

plugins {
    id("org.nosphere.apache.rat")
}

tasks.withType<RatTask>().configureEach {
    val gitignore = rootProject.file(".gitignore")
    if (gitignore.exists()) {
        excludes.addAll(RatExcludes.fromGitignore(gitignore.readLines()))
    }

    excludes.addAll(
        listOf(
            // Gradle wrapper (ships under its own license) + on-disk OS cruft
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/**",
            "**/.DS_Store",
            // Tracked dotfiles that take no header
            ".run/**",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
            ".tool-versions",
            ".env.example",
            // License/notice files themselves (no header by definition)
            "LICENSE",
            "NOTICE",
            // ASF infra metadata and tool config (no header by convention)
            ".asf.yaml",
            "config/**",
            // Local developer tooling not tracked by git — Claude Code worktrees/settings
            // and the Kotlin compiler cache (analogous to the gitignored .idea/.gradle).
            // Present only on some local checkouts, never in CI.
            ".claude/**",
            "**/.kotlin/**",
            // Tabular data — no comment syntax to hold a header
            "**/*.csv",
            // Documentation (markdown carries no license header)
            "**/*.md",
            "docs/**",
            "dev-docs/**",
            "security-docs/**",
            // Binary assets
            "images/**",
            "**/*.png",
            // Data / generated content (JSON cannot hold comments)
            "mydata/**",
            "**/*.json",
        ),
    )
}
