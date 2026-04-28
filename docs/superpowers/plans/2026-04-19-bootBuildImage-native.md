# bootBuildImage for Native Docker Builds

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken `nativeCompile` + Jib native Docker pipeline with `bootBuildImage`, which compiles the native binary inside a Linux builder container and works on any host OS/architecture.

**Architecture:** Jib remains for JVM Docker images (proven stdout-clean, multi-arch). `bootBuildImage` with `BP_NATIVE_IMAGE=true` handles native Docker images via Paketo buildpacks. The `graalvmNative` plugin config is retained for local `nativeCompile`/`nativeTest` tasks. OTel `--initialize-at-build-time` args are extracted to a shared variable used by both `graalvmNative` and `bootBuildImage`.

**Tech Stack:** Spring Boot 3.5.13 (`bootBuildImage`), GraalVM Native Build Tools 0.10.6, Paketo buildpacks, Jib 3.5.3

---

### Task 1: Extract shared native image args and simplify Jib

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Extract native image build args to a shared variable**

At the top of `build.gradle.kts`, after `val nativeBuild = project.hasProperty("native")`, remove `hostArch` and add:

```kotlin
// Shared GraalVM native-image arguments used by both graalvmNative (local builds)
// and bootBuildImage (Docker builds via Paketo buildpacks).
val nativeImageBuildArgs = listOf(
    "--no-fallback",
    "-H:+ReportExceptionStackTraces",
    "--initialize-at-build-time=io.opentelemetry.api",
    "--initialize-at-build-time=io.opentelemetry.context",
    "--initialize-at-build-time=io.opentelemetry.instrumentation.api",
    "--initialize-at-build-time=io.opentelemetry.instrumentation.logback",
)
```

- [ ] **Step 2: Update graalvmNative to use the shared variable**

Replace the `graalvmNative` block's `named("main")` section:

```kotlin
graalvmNative {
    binaries {
        named("main") {
            imageName.set("solr-mcp")
            buildArgs.addAll(nativeImageBuildArgs)
        }
    }
    binaries {
        named("test") {
            buildArgs.addAll(
                nativeImageBuildArgs.filter { it.startsWith("--initialize-at-build-time") },
                // opentelemetry-sdk-testing ServiceLoader provider
                "--initialize-at-build-time=io.opentelemetry.sdk",
                // AndroidFriendlyRandomHolder creates Random in <clinit>
                "--initialize-at-run-time=io.opentelemetry.sdk.internal.AndroidFriendlyRandomHolder",
            )
        }
    }
}
```

Note: the test binary drops `--no-fallback` and `-H:+ReportExceptionStackTraces` since those are build-specific. It reuses only the `--initialize-at-build-time` entries, plus adds the sdk/test-specific ones.

- [ ] **Step 3: Remove all nativeBuild conditionals from the Jib block**

The `jib { }` block should become purely JVM. Remove:
- `hostArch` variable (already done in step 1)
- `if (nativeBuild)` branch in `from.image` — always use `"eclipse-temurin:25-jre"`
- `if (nativeBuild) hostArch else "amd64"` in platforms — always list both `amd64` and `arm64`
- `if (nativeBuild)` branches in `to.image` and `to.tags` — always `"solr-mcp:$version"` and `setOf("latest")`
- `buildMap { ... if (nativeBuild) ... }` in `container.environment` — simplify to `mapOf("SPRING_DOCKER_COMPOSE_ENABLED" to "false")`
- `if (nativeBuild) emptyList() else listOf(...)` in `container.jvmFlags` — always the JVM flags list
- `if (nativeBuild) { entrypoint = ... } else { mainClass = ... }` — always `mainClass`
- `if (nativeBuild) { extraDirectories { ... } }` — remove entirely

The resulting `from` block:

```kotlin
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
```

The resulting `to` block:

```kotlin
to {
    image = "solr-mcp:$version"
    tags = setOf("latest")
}
```

The resulting `container` block:

```kotlin
container {
    environment = mapOf("SPRING_DOCKER_COMPOSE_ENABLED" to "false")
    jvmFlags = listOf(
        "-XX:+UseContainerSupport",
        "-XX:MaxRAMPercentage=75.0",
    )
    mainClass = "org.apache.solr.mcp.server.Main"
    ports = listOf("8080")
    labels.set(mapOf(
        "org.opencontainers.image.title" to "Solr MCP Server",
        "org.opencontainers.image.description" to "Spring AI MCP Server for Apache Solr",
        "org.opencontainers.image.version" to version.toString(),
        "org.opencontainers.image.vendor" to "Apache Software Foundation",
        "org.opencontainers.image.licenses" to "Apache-2.0",
        "io.modelcontextprotocol.server.name" to "io.github.apache/solr-mcp",
    ))
}
```

No `extraDirectories` block.

- [ ] **Step 4: Remove the nativeCompile-to-Jib dependency wiring**

Delete this block at the bottom of `build.gradle.kts`:

```kotlin
// Under -Pnative, Jib packaging tasks must wait for the native binary
if (nativeBuild) {
    tasks
        .matching { it.name in setOf("jib", "jibDockerBuild", "jibBuildTar") }
        .configureEach { dependsOn("nativeCompile") }
}
```

- [ ] **Step 5: Verify Jib JVM build still works**

Run: `./gradlew jibDockerBuild`
Expected: Builds `solr-mcp:1.0.0-SNAPSHOT` and `solr-mcp:latest` without errors.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -s -m "refactor(build): simplify Jib to JVM-only, extract native args

Remove all nativeBuild conditionals from Jib config. Jib now produces
JVM images exclusively. Extract shared native image build args to a
variable for reuse by graalvmNative and bootBuildImage (next commit)."
```

---

### Task 2: Configure bootBuildImage for native Docker builds

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add bootBuildImage configuration**

Add this block after the `jib { }` block and before the `graalvmNative { }` block:

```kotlin
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
    imageName.set("solr-mcp:${version}-native")
    tags.set(listOf("solr-mcp:latest-native"))
    environment.set(
        mapOf(
            "BP_NATIVE_IMAGE" to "true",
            "BP_NATIVE_IMAGE_BUILD_ARGUMENTS" to nativeImageBuildArgs.joinToString(" "),
            "BP_JVM_VERSION" to "25",
        ),
    )
}
```

- [ ] **Step 2: Update dockerIntegrationTest to depend on bootBuildImage when -Pnative**

In the `dockerIntegrationTest` task, change the dependency block from:

```kotlin
if (dockerAvailable) {
    dependsOn(tasks.jibDockerBuild)
}
```

to:

```kotlin
if (dockerAvailable) {
    if (nativeBuild) {
        dependsOn(tasks.named("bootBuildImage"))
    } else {
        dependsOn(tasks.jibDockerBuild)
    }
}
```

- [ ] **Step 3: Build native Docker image**

Run: `./gradlew bootBuildImage`
Expected: Builds `solr-mcp:1.0.0-SNAPSHOT-native` and `solr-mcp:latest-native`. The Paketo builder compiles the native binary inside a Linux container. This will take several minutes on first run.

- [ ] **Step 4: Verify native image starts cleanly (stdout check)**

Run: `docker run --rm -e SPRING_PROFILES_ACTIVE=stdio -e SPRING_DOCKER_COMPOSE_ENABLED=false solr-mcp:1.0.0-SNAPSHOT-native`
Expected: The container starts. Verify that stdout is clean (no buildpack launcher logs). The process should block waiting for STDIO input. Kill with Ctrl+C after a few seconds.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -s -m "feat(build): use bootBuildImage for native Docker images

Configure bootBuildImage with BP_NATIVE_IMAGE=true so native compilation
happens inside the Paketo builder container. This works on any host OS
and CPU architecture, fixing the cross-OS binary mismatch that made the
previous nativeCompile+Jib pipeline produce broken images on macOS."
```

---

### Task 3: Update CI workflow

**Files:**
- Modify: `.github/workflows/native.yml`

- [ ] **Step 1: Update native-image job**

In the `native-image` job, change the build step from:

```yaml
-   name: Build native Docker image
    run: ./gradlew jibDockerBuild -Pnative --no-daemon
```

to:

```yaml
-   name: Build native Docker image
    run: ./gradlew bootBuildImage --no-daemon
```

And change the integration test step from:

```yaml
-   name: Run STDIO integration test against native image
    run: ./gradlew dockerIntegrationTest -Pnative --no-daemon
```

(this stays the same — `-Pnative` tells `dockerIntegrationTest` which image tag to use)

- [ ] **Step 2: Update benchmark job**

No change needed — the benchmark script will be updated in Task 4.

- [ ] **Step 3: Add timeout-minutes increase**

The `native-image` job currently has `timeout-minutes: 45`. `bootBuildImage` with native may take longer on first run because it downloads the Paketo builder image. Increase to `timeout-minutes: 60`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/native.yml
git commit -s -m "ci(native): use bootBuildImage instead of jibDockerBuild -Pnative"
```

---

### Task 4: Update benchmark script

**Files:**
- Modify: `scripts/benchmark-native.sh`

- [ ] **Step 1: Update the native build command**

Change line in `build_images()` from:

```bash
./gradlew jibDockerBuild -Pnative
```

to:

```bash
./gradlew bootBuildImage
```

- [ ] **Step 2: Update the header comment**

Change the prerequisites comment from:

```bash
#   - GraalVM JDK 25 on PATH (for the native build)
#   - Linux (the native build produces a linux/amd64 binary)
```

to:

```bash
#   - GraalVM JDK 25 on PATH (for the JVM build's processAot)
#   - Works on any OS/arch (native build runs inside Paketo builder container)
```

- [ ] **Step 3: Commit**

```bash
git add scripts/benchmark-native.sh
git commit -s -m "fix(benchmark): use bootBuildImage for native image build"
```

---

### Task 5: Update spec and documentation

**Files:**
- Modify: `docs/specs/graalvm-native-image.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Update spec section 3 (Non-Goals)**

Remove this non-goal:

```
- Cross-compiling native binaries from macOS to Linux — native build happens
  in a Linux context (CI or a Linux dev box / container).
```

- [ ] **Step 2: Rewrite spec section 4 (High-Level Approach)**

Replace the current section 4 with an approach that describes:

1. `bootBuildImage` with `BP_NATIVE_IMAGE=true` compiles the native binary inside a Paketo builder container.
2. This works on any host OS/architecture because the build happens inside Linux.
3. Jib remains for JVM images (proven stdout-clean for STDIO).
4. The `graalvmNative` plugin is retained for local `nativeCompile` and `nativeTest`.

Update section 4.1 to explain why Jib is used for JVM but not native:
- Jib: great for JVM (multi-arch, no stdout pollution), but can't cross-compile native binaries.
- `bootBuildImage`: compiles inside a Linux builder container, producing a Linux-native binary regardless of host OS. The native binary is the entrypoint — no buildpack launcher, so no stdout pollution.

- [ ] **Step 3: Update spec section 5 (Gradle Changes)**

Update sections 5.3 and 5.4 to reflect the new architecture:
- `bootBuildImage` configuration instead of Jib native config
- `nativeImageBuildArgs` shared variable
- Updated commands table

Remove section 5.4 (Jib config for native) entirely — replace with bootBuildImage config.

- [ ] **Step 4: Update spec section 9 (CI)**

Update the CI steps to use `bootBuildImage` instead of `jibDockerBuild -Pnative`.

- [ ] **Step 5: Update AGENTS.md native image section**

Update the "GraalVM Native Image (Opt-In)" section to reflect:
- `bootBuildImage` for Docker images instead of `nativeCompile` + Jib
- Works on any OS/arch
- Updated commands: `./gradlew bootBuildImage` replaces `./gradlew jibDockerBuild -Pnative`

Update the Common Commands section:

```bash
# Native image (experimental, requires GraalVM JDK 25)
./gradlew nativeCompile -Pnative            # Compile native binary (host OS only)
./gradlew bootBuildImage                     # Build native Docker image (any OS/arch)
./gradlew nativeTest                         # Run tests as native image
./gradlew dockerIntegrationTest -Pnative     # Docker integration tests (native)
```

- [ ] **Step 6: Commit**

```bash
git add docs/specs/graalvm-native-image.md AGENTS.md
git commit -s -m "docs: update spec and AGENTS.md for bootBuildImage native builds

Rewrite spec sections 3-5, 9 to describe bootBuildImage approach.
Remove cross-compilation non-goal (no longer applicable).
Update AGENTS.md commands and native image documentation."
```

---

### Task 6: Run full verification

- [ ] **Step 1: Run JVM build and tests**

Run: `./gradlew build`
Expected: All tests pass, spotless formatting applied.

- [ ] **Step 2: Run JVM Docker integration test**

Run: `./gradlew dockerIntegrationTest`
Expected: Jib builds JVM image, integration tests pass.

- [ ] **Step 3: Run native Docker build**

Run: `./gradlew bootBuildImage`
Expected: Paketo builder compiles native image successfully.

- [ ] **Step 4: Run native Docker integration test**

Run: `./gradlew dockerIntegrationTest -Pnative`
Expected: Integration tests pass against the native image.

- [ ] **Step 5: Verify stdout is clean**

Run: `docker run --rm -e SPRING_PROFILES_ACTIVE=stdio -e SPRING_DOCKER_COMPOSE_ENABLED=false solr-mcp:1.0.0-SNAPSHOT-native 2>/dev/null | head -c 100`
Expected: No output on stdout (the process waits for STDIO input). If any buildpack launcher output appears, flag it.

---

### Follow-up (not part of this plan)

- **Reflection hints audit:** Remove `SolrNativeHints.java` hints one at a time and re-run `dockerIntegrationTest -Pnative`. The custom `JsonResponseParser` constructs all SolrJ data types directly — reflection hints are likely unnecessary. Types like `LukeResponse`, `FacetField`, and `SchemaRepresentation` already work without hints.
- **HTTP profile native image:** Extend native support to the HTTP profile (OAuth2, actuator, Prometheus).
