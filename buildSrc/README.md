<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# buildSrc — project build logic

This directory holds the project's custom build logic, written in Kotlin. Today that is
two ASF-compliance concerns:

- assembling the **binary-release `LICENSE` and `NOTICE`** files bundled inside the
  executable JAR (the end-user view of *what* these contain lives on the
  [Licensing & Notices](https://solr.apache.org/mcp/licensing.html) docs page); and
- enforcing **Apache license headers** on source files via Apache RAT.

If you don't work with Gradle day-to-day, this README explains what each piece is and how
they fit together.

## What is `buildSrc`?

`buildSrc` is a Gradle convention: **any code you put under `buildSrc/` is compiled
automatically before the main build and made available to `build.gradle.kts`.** You
don't declare a dependency on it or publish it anywhere — Gradle just picks it up. It is
the standard place to keep custom build logic so the root `build.gradle.kts` stays
small. (Think of it as a tiny library that only this project's build uses.)

## What's in here

| File | Role |
|------|------|
| `src/main/kotlin/.../GenerateBinaryLicense.kt` | A custom Gradle **task** that writes the binary `LICENSE` (Apache-2.0 text + a generated third-party dependency appendix). |
| `src/main/kotlin/.../GenerateBinaryNotice.kt`  | A custom Gradle **task** that writes the binary `NOTICE` (our `NOTICE` + the `NOTICE` files of bundled dependencies). |
| `src/main/kotlin/org.apache.solr.mcp.license-notice.gradle.kts` | A **convention plugin** that creates the two tasks above and wires them into the build. |
| `src/test/kotlin/.../LicenseNoticeTasksTest.kt` | Unit tests for the two tasks. |
| `src/main/kotlin/.../RatExcludes.kt` | Pure helper that translates `.gitignore` entries into Apache RAT (Ant-style) exclude globs. |
| `src/main/kotlin/org.apache.solr.mcp.rat.gradle.kts` | A **convention plugin** that applies Apache RAT and configures its excludes (`.gitignore`-derived + an explicit list). |
| `src/test/kotlin/.../RatExcludesTest.kt` | Unit tests for the gitignore→glob translation. |
| `build.gradle.kts` | Builds `buildSrc` itself (enables Kotlin + the RAT plugin + the test dependencies). |

## Gradle concepts, for Java developers

A handful of Gradle terms show up in the code. Here is the minimum to read it:

- **Task** — a single unit of build work with declared *inputs* and *outputs*, a bit
  like one rule in a `Makefile`. Gradle decides whether a task needs to run by comparing
  its inputs/outputs to the last run. We write a task by subclassing `DefaultTask`.
- **`@TaskAction`** — the method Gradle calls to actually do the work when the task runs.
  It's effectively the task's "main".
- **Input / output annotations** (`@InputFile`, `@InputFiles`, `@Input`, `@OutputFile`) —
  these declare what a task reads and writes. They are not decoration: Gradle uses them
  to (1) **skip** the task when nothing changed (incremental builds), and (2) **order**
  tasks so a producer runs before whoever consumes its output. `@InputFile`/`@InputFiles`
  are file inputs; `@Input` is a plain value (a string, list, map); `@OutputFile` is a
  produced file.
- **`Property` / `Provider` types** (`RegularFileProperty`, `ListProperty`,
  `MapProperty`, `ConfigurableFileCollection`) — Gradle's "lazy" typed holders for a
  value. The convention plugin `.set(...)`s them while the build is being *configured*;
  the task `.get()`s them later when it actually *runs*. This lazy split is why the task
  declares `abstract val foo: …Property` instead of a plain field.
- **Convention plugin** — a `.gradle.kts` file under `buildSrc` that Gradle compiles into
  a plugin you can apply by id. Applying it (one line in the root build) registers our
  tasks and connects them to the rest of the build, so the conventions live here instead
  of being copy-pasted into `build.gradle.kts`.
- **`productionRuntimeClasspath`** — the set of dependency jars that actually end up
  inside the Spring Boot fat jar. It excludes test-only, compile-only, and
  `developmentOnly` dependencies. "What ships" is exactly what the binary LICENSE/NOTICE
  must describe, which is why both tasks are driven by it.

## How it runs

1. The root `build.gradle.kts` applies the plugin: `id("org.apache.solr.mcp.license-notice")`.
2. The plugin registers `generateBinaryLicense` and `generateBinaryNotice`, and makes the
   `bootJar` task depend on them (and the `check` task depend on `generateBinaryLicense`).
3. On a build, the CycloneDX `cyclonedxBom` task produces the SBOM, then:
   - `generateBinaryLicense` reads the SBOM + the list of shipped dependencies and writes
     `build/generated/license/LICENSE`. It **fails the build** if a shipped dependency is
     missing from the SBOM (so nothing can ship unlisted).
   - `generateBinaryNotice` scans the shipped jars for their `META-INF/NOTICE` files and
     writes `build/generated/license/NOTICE`.
4. `bootJar` copies those two files into the JAR's `META-INF/`. The source-form jars
   (thin `jar`, `-sources`, `-javadoc`) instead carry the plain repo-root `LICENSE` /
   `NOTICE`.

See the `## Release LICENSE / NOTICE` section in the repository's `AGENTS.md` for the
policy rationale, and the [Licensing & Notices](https://solr.apache.org/mcp/licensing.html)
docs page for the consumer-facing explanation.
