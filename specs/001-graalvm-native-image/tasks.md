# Tasks: GraalVM Native Docker Image

**Input**: Design documents from `/specs/001-graalvm-native-image/`
**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- **[Story]**: User story label (US1, US2, US3)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add GraalVM native image toolchain to the project's build system

- [ ] T001 Add `graalvm-buildtools-native = "0.11.4"` to `[versions]` and `graalvm-buildtools-native = { id = "org.graalvm.buildtools.native", version.ref = "graalvm-buildtools-native" }` to `[plugins]` in `gradle/libs.versions.toml`
- [ ] T002 [P] Add `jib-native-image-extension = "0.1.0"` to `[versions]` and `jib-native-image-extension = { module = "com.google.cloud.tools:jib-native-image-extension-gradle", version.ref = "jib-native-image-extension" }` to `[libraries]` in `gradle/libs.versions.toml`
- [ ] T003 Apply GraalVM native plugin in `build.gradle.kts` `plugins {}` block: `alias(libs.plugins.graalvm.buildtools.native)`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Wire native compilation and native Docker image build tasks — nothing in any user story can proceed until `./gradlew nativeCompile` succeeds.

**⚠️ CRITICAL**: No user story work can begin until T007 passes.

- [ ] T004 Add `graalvmNative {}` configuration block to `build.gradle.kts`: set `binaries.named("main")` with `imageName = "solr-mcp"`, `requiredVersion = "25"`, `mainClass = "org.apache.solr.mcp.server.Main"`, and `buildArgs.add("-H:+ReportExceptionStackTraces")`
- [ ] T005 Add `nativeJibDockerBuild` task configuration block to `build.gradle.kts`: configure `jib {}` variant with `from.image = "gcr.io/distroless/base-debian12"`, `to.image = "solr-mcp:$version-native"`, `to.tags = setOf("latest-native")`, remove `jvmFlags` (not applicable to native), add `pluginExtensions` block using `JibNativeImageExtension` with `properties = mapOf("imageName" to "solr-mcp")`
- [ ] T006 Wire `tasks.nativeJibDockerBuild { dependsOn(tasks.nativeCompile) }` in `build.gradle.kts`
- [ ] T007 Run `./gradlew spotlessApply` then `./gradlew nativeCompile` and verify it completes without error (Spring Boot AOT processing + GraalVM native-image compiler must both succeed)

**Checkpoint**: `build/native/nativeCompile/solr-mcp` binary exists — user story implementation can begin.

---

## Phase 3: User Story 1 — Operator Deploys Fast-Starting Container (Priority: P1) 🎯 MVP

**Goal**: Native Docker image starts in < 1 second and produces zero non-MCP output on stdout in STDIO mode.

**Independent Test**: `docker run --rm -i -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:latest-native` — server is ready in < 1 second and stdout contains only MCP JSON-RPC messages.

### Implementation for User Story 1

- [ ] T008 Create `src/main/java/org/apache/solr/mcp/server/config/GraalVmRuntimeHints.java` implementing `RuntimeHintsRegistrar`; register for reflection: `SolrDocument`, `SolrDocumentList`, `SimpleOrderedMap`, `NamedList`, `HttpJdkSolrClient`, `QueryResponse`, `SearchResponse`; annotate the nearest `@Configuration` class with `@ImportRuntimeHints(GraalVmRuntimeHints.class)`
- [ ] T009 [P] Write `src/test/java/org/apache/solr/mcp/server/config/GraalVmRuntimeHintsTest.java` using `RuntimeHintsPredicates` to assert each type registered in T008 is present in the hints; run with `./gradlew test --tests GraalVmRuntimeHintsTest` and confirm it passes
- [ ] T010 Run the native-image tracing agent against the running application: `./gradlew bootRun -Dspring.profiles.active=http -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/generated/`; exercise all four MCP tool categories (search, indexing, collection management, schema introspection) using the MCP Inspector, then stop the server
- [ ] T011 Curate and write `src/main/resources/META-INF/native-image/org/apache/solr/mcp/reflect-config.json` from the agent output in T010: keep runtime-needed SolrJ HTTP client internals, XML parser (DocumentBuilderFactoryImpl, SAXParser), and JWT processor classes; remove build-time-only entries
- [ ] T012 [P] Curate and write `src/main/resources/META-INF/native-image/org/apache/solr/mcp/resource-config.json` from the agent output in T010: include XML parser service loader entries (`META-INF/services/javax.xml.parsers.DocumentBuilderFactory`, `META-INF/services/javax.xml.parsers.SAXParserFactory`) and any i18n bundles
- [ ] T013 [P] Curate and write `src/main/resources/META-INF/native-image/org/apache/solr/mcp/proxy-config.json` from the agent output in T010: include dynamic proxy interfaces for Spring Security OAuth2 and any Spring AOP proxies captured
- [ ] T014 Run `./gradlew nativeJibDockerBuild` with the hints from T011–T013 in place; fix any `MissingReflectiveOperationException` or `ClassNotFoundException` at startup by adding entries to the appropriate hint file and rebuilding
- [ ] T015 Verify STDIO purity: run `docker run --rm -i -e SOLR_URL=http://host.docker.internal:8983/solr/ solr-mcp:<version>-native 2>/dev/null` and confirm stdout contains only valid MCP JSON-RPC; any non-JSON line is a failure
- [ ] T016 Verify startup time: time from `docker run` to first successful MCP tool response must be < 1 second (use `time` command or measure with MCP Inspector timestamp)
- [ ] T017 Verify memory: run `docker stats --no-stream` against the idle native container and the idle JVM container side-by-side; confirm native MEM USAGE is < 50% of JVM MEM USAGE

**Checkpoint**: User Story 1 fully functional — native container starts fast, stays silent on stdout, uses less memory.

---

## Phase 4: User Story 2 — Build Engineer Produces Native Image via Standard Pipeline (Priority: P2)

**Goal**: `./gradlew nativeJibDockerBuild` from a clean checkout produces the native image with no additional manual steps.

**Independent Test**: Clone repo, install GraalVM 25, run `./gradlew nativeJibDockerBuild` — native image tagged `solr-mcp:<version>-native` is available in local Docker daemon.

### Implementation for User Story 2

- [ ] T018 Update the `dockerIntegrationTest` task configuration in `build.gradle.kts` to read the system property `solr.mcp.image` (defaulting to the current JVM image name) and pass it to the Docker integration test container; this allows `./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native` to test against the native image
- [ ] T019 Run `./gradlew nativeJibDockerBuild && ./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native` and verify all existing Docker STDIO integration tests pass against the native image without test modification
- [ ] T020 [P] Update `CLAUDE.md` Common Commands section: add `./gradlew nativeCompile` (build native executable), `./gradlew nativeJibDockerBuild` (build native Docker image locally), `./gradlew nativeTest` (run tests as native), `./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native` (Docker integration tests against native image); add note that GraalVM 25+ must be on `JAVA_HOME`
- [ ] T021 Verify image tags: run `docker images | grep solr-mcp` and confirm both `solr-mcp:<version>-native` and `solr-mcp:latest-native` exist after `nativeJibDockerBuild`; verify `solr-mcp:<version>` (JVM image) is unchanged

**Checkpoint**: Any engineer with GraalVM 25 can run one command to produce and test the native image.

---

## Phase 5: User Story 3 — Operator Runs Native Image in HTTP Mode (Priority: P3)

**Goal**: Native image in HTTP mode with OAuth2 enforces authentication identically to the JVM image.

**Independent Test**: `docker run -e PROFILES=http -e SOLR_URL=... -e OAUTH2_ISSUER_URI=... -p 8080:8080 solr-mcp:<version>-native` — authenticated requests succeed, unauthenticated requests return 401.

### Implementation for User Story 3

- [ ] T022 Add OAuth2 JWT reflection entries to `src/main/resources/META-INF/native-image/org/apache/solr/mcp/reflect-config.json`: `NimbusJwtDecoder`, `NimbusReactiveJwtDecoder`, `com.nimbusds.jose.jwk.JWK` and its subtypes; also add `org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer` with `queryDeclaredMethods=true`; rebuild native image with `./gradlew nativeJibDockerBuild`
- [ ] T023 [P] Run Docker HTTP mode integration test against native image: `docker run --rm -p 8080:8080 -e PROFILES=http -e SOLR_URL=http://host.docker.internal:8983/solr/ -e OAUTH2_ISSUER_URI=<test-issuer> solr-mcp:<version>-native`; invoke each MCP tool category with a valid bearer token and verify correct responses
- [ ] T024 [P] Verify OAuth2 enforcement: send unauthenticated HTTP requests to the native container's MCP endpoint; confirm 401 responses for collection creation, deletion, and schema modification operations
- [ ] T025 Run `./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native` for HTTP mode integration tests; verify all pass

**Checkpoint**: All three user stories independently functional and tested.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T026 Create `.github/workflows/native-image.yml`: define matrix strategy with two jobs — `native-amd64` on `ubuntu-latest` and `native-arm64` on `ubuntu-latest-arm`; each job installs GraalVM 25 (using `graalvm/setup-graalvm` action), runs `./gradlew nativeJibDockerBuild`, pushes arch-specific image (e.g., `solr-mcp:<version>-native-amd64`); add `native-manifest` job that runs after both and uses `docker buildx imagetools create` to merge into multi-arch `solr-mcp:<version>-native` manifest; trigger on release branches and nightly schedule
- [ ] T027 [P] Update `.specify/memory/constitution.md` Technology Stack section: add `- **Native Image**: GraalVM 25+ with \`org.graalvm.buildtools.native\` Gradle plugin; native images tagged \`solr-mcp:<version>-native\`; packaged via Jib native image extension with \`gcr.io/distroless/base-debian12\` base`; increment `CONSTITUTION_VERSION` to `1.1.0`; update `LAST_AMENDED_DATE` to `2026-03-08`; run constitution consistency propagation checklist against all `.specify/templates/` files
- [ ] T028 [P] Run `./gradlew spotlessApply` across all changed files; then run `./gradlew build` (full JVM build with tests) and confirm it passes with zero failures and no regressions
- [ ] T029 Run Solr version matrix against the native Docker image: execute `./gradlew dockerIntegrationTest -Dsolr.mcp.image=solr-mcp:<version>-native -Dsolr.test.image=solr:8.11-slim`, repeat for `solr:9.4-slim`, `solr:9.9-slim`, `solr:9.10-slim`, `solr:10-slim`; all must pass (SC-006 acceptance gate)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately. T001 and T002 are parallel (different sections of same file); T003 depends on T001.
- **Foundational (Phase 2)**: Depends on Setup completion — **BLOCKS all user stories**. T004 and T005 touch `build.gradle.kts` sequentially; T007 depends on T004–T006.
- **User Stories (Phases 3–5)**: All depend on Foundational phase (T007 gate). Can proceed in priority order or in parallel if capacity allows.
- **Polish (Phase 6)**: Depends on all user stories complete.

### User Story Dependencies

- **US1 (P1)**: Starts after T007. T008 and T009 parallel. T010 → T011/T012/T013 (parallel) → T014 → T015/T016/T017.
- **US2 (P2)**: Starts after US1 complete (requires working native image from US1). T018 → T019 → T020/T021 parallel.
- **US3 (P3)**: Starts after US1 complete (requires working native image). T022 → T023/T024 parallel → T025.

### Parallel Opportunities Within User Story 1

```
T008 (GraalVmRuntimeHints.java)  ──┐
T009 (GraalVmRuntimeHintsTest)   ──┘ parallel

T010 (agent tracing)
  └──> T011 (reflect-config.json)  ──┐
       T012 (resource-config.json) ──┤ parallel
       T013 (proxy-config.json)    ──┘
             └──> T014 (nativeJibDockerBuild)
                    └──> T015 / T016 / T017 (verification, parallel)
```

---

## Implementation Strategy

### MVP (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T003)
2. Complete Phase 2: Foundational (T004–T007) — gate: `nativeCompile` succeeds
3. Complete Phase 3: US1 (T008–T017)
4. **STOP and VALIDATE**: `docker run` native image in STDIO mode — sub-second startup, silent stdout
5. Proceed to US2 once MVP validated

### Incremental Delivery

1. Setup + Foundational → `nativeCompile` works
2. US1 → Native image boots fast and stays silent → **MVP demo**
3. US2 → Build pipeline complete → engineers can produce native image
4. US3 → HTTP mode + OAuth2 verified → full feature parity
5. Polish → CI, constitution amendment, Solr matrix — production-ready

---

## Notes

- GraalVM 25 must be on `JAVA_HOME` for T007 and all subsequent native tasks
- Agent tracing (T010) requires exercising **all** MCP tool categories — incomplete tracing causes runtime failures
- Each hint file edit requires a full `./gradlew nativeJibDockerBuild` rebuild (~5–10 min); batch hint changes before rebuilding
- `./gradlew build` (JVM) must remain passing throughout — native compilation is opt-in and does not affect the JVM build path
- Commit after each phase checkpoint with `git commit -s` (Signed-off-by required per convention)
