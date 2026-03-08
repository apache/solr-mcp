<!--
  SYNC IMPACT REPORT
  ==================
  Version change: N/A (blank template) → 1.0.0 (initial ratification on sb4 branch)

  Added principles (all new):
    - I. MCP Protocol Integrity
    - II. Solr Version Compatibility
    - III. Test-First Development (NON-NEGOTIABLE)
    - IV. Security by Default
    - V. Simplicity and YAGNI

  Added sections:
    - Technology Stack (sb4: Spring Boot 4.0.2, Spring AI 2.0.0-M2, SolrJ 10.0.0,
      Testcontainers 2.0.2, OpenTelemetry observability, Error Prone + NullAway)
    - Development Workflow

  Templates reviewed:
    - .specify/templates/plan-template.md  ✅ Constitution Check section present
    - .specify/templates/spec-template.md  ✅ No changes required
    - .specify/templates/tasks-template.md ✅ No changes required
    - .specify/templates/agent-file-template.md ✅ No changes required

  Deferred items:
    - None. All placeholders resolved.
-->

# Solr MCP Server Constitution

## Core Principles

### I. MCP Protocol Integrity

The server MUST maintain strict compliance with the Model Context Protocol specification
at all times. Specifically:

- STDIO transport MUST write ONLY MCP JSON-RPC messages to stdout; no logging, banners,
  or debug output is permitted on stdout in STDIO mode.
- HTTP transport MUST expose endpoints conforming to the MCP-over-HTTP specification;
  no custom deviations allowed without upstream specification change.
- New MCP tools MUST be exposed via `@McpTool` annotations; direct HTTP endpoint exposure
  of tool logic is not permitted.
- STDIO and HTTP transport modes MUST remain independently functional and must not share
  runtime state that could cause cross-mode interference.

**Rationale**: STDIO pollution breaks Claude Desktop integration silently, making
violations extremely hard to debug in production. Protocol compliance is non-negotiable
for any MCP server claiming broad AI-assistant compatibility.

### II. Solr Version Compatibility

All features and bug fixes MUST be verified against the full matrix of supported Solr
versions: **8.11, 9.4, 9.9, 9.10, and 10**.

- Solr-version-specific API differences (e.g., removed `/admin/mbeans` in Solr 10) MUST
  degrade gracefully — catching `RuntimeException`/`RemoteSolrException` and returning
  `null` rather than throwing.
- Tests MUST pass against all supported Solr Docker images via the `solr.test.image`
  system property.
- SolrJ client version mismatches (e.g., SolrJ 9.x against Solr 10 server) MUST be
  documented in CLAUDE.md and addressed when upstream artifacts are available.
- CI MUST include a Solr version matrix job covering all supported versions.

**Rationale**: Solr MCP targets a wide operator audience running different Solr versions.
Silent breakage on a single version is a critical regression.

### III. Test-First Development (NON-NEGOTIABLE)

Tests MUST be written before or alongside implementation. The Red-Green-Refactor cycle is
the mandatory development loop.

- Unit tests (`*Test.java`) MUST use mocked Solr dependencies and run without any external
  services.
- Integration tests (`*IntegrationTest.java`, `*DirectTest.java`) MUST use real Solr via
  Testcontainers; no mocking of Solr itself in integration tests.
- Docker image tests (`@Tag("docker-integration")`) MUST be run separately via
  `./gradlew dockerIntegrationTest` after `jibDockerBuild`.
- `./gradlew build` MUST pass (no test failures) before any commit is merged.
- Code coverage MUST be tracked via JaCoCo; regressions in coverage require explicit
  justification.

**Rationale**: The MCP protocol surface, Solr API compatibility, and security gates all
require verified behaviour. TDD prevents correctness regressions as the Solr version
matrix grows.

### IV. Security by Default

Security controls MUST be applied at the correct layer and MUST NOT be bypassable by
default configuration.

- Sensitive operations (collection creation, deletion, schema modification) MUST require
  authentication when the server is running in HTTP mode with OAuth2 configured.
- OAuth2/Auth0 integration MUST be the sole supported authentication mechanism for HTTP
  mode; custom auth schemes are not permitted without a constitution amendment.
- STDIO mode security relies on OS-level process isolation; no network auth is required
  or appropriate in STDIO mode.
- Security-relevant changes MUST include a security test or an explicit rationale for why
  a test is not possible.

**Rationale**: As an MCP server with write access to Solr, unauthenticated endpoints
could allow data destruction or exfiltration. Security MUST be on by default, not opt-in.

### V. Simplicity and YAGNI

Every design decision MUST use the minimum complexity required to satisfy current
requirements. Future-proofing is not a justification for added complexity.

- No new abstractions, helpers, or utility classes MUST be created for single-use
  operations.
- No error handling or fallback paths MUST be added for scenarios that cannot occur
  given the current architecture.
- Docstrings, comments, and type annotations MUST NOT be added to code that was not
  changed in the same PR.
- The strategy pattern in `indexing/documentcreator/` MUST remain the canonical example
  of justified abstraction (three or more format implementations sharing one interface).
- Each new MCP tool MUST justify its addition against existing tool surface area before
  being merged.

**Rationale**: Apache incubating projects attract many contributors. Excessive complexity
increases review burden, slows adoption, and masks protocol bugs.

## Technology Stack

- **Runtime**: Java 25+ (version centralized in `build.gradle.kts`)
- **Framework**: Spring Boot 4.0.2, Spring AI 2.0.0-M2
- **Build**: Gradle (Kotlin DSL) with `gradle/libs.versions.toml` version catalog
- **Containerization**: Jib (NOT Spring Boot Buildpacks — Buildpacks write to stdout,
  breaking STDIO transport)
- **Testing**: JUnit 5, Testcontainers 2.0.2, Mockito, JaCoCo
- **Observability**: Spring Boot OpenTelemetry starter, Micrometer OTLP registry,
  OpenTelemetry Logback appender
- **Formatting**: Spotless (MUST run `./gradlew spotlessApply` before every commit)
- **Static analysis**: Error Prone with NullAway
- **Solr client**: SolrJ 10.0.0

Technology stack changes MUST be proposed as a constitution amendment (MINOR or MAJOR
version bump depending on scope).

## Development Workflow

- **Commit convention**: Conventional Commits — `feat`, `fix`, `docs`, `style`,
  `refactor`, `test`, `chore`. Example: `feat(search): add fuzzy search support`
- **Sign-off**: Every commit MUST include `Signed-off-by:` (use `git commit -s`)
- **Formatting**: `./gradlew spotlessApply` MUST be run before committing; CI enforces
  `./gradlew spotlessCheck`
- **Build gate**: `./gradlew build` (full build with tests) MUST pass before merging
- **Branch naming**: `###-feature-name` (e.g., `042-oauth2-support`)
- **PRs**: MUST reference the spec or issue; MUST include test evidence for new behaviour

Constitution violations discovered during code review MUST be documented in the PR and
resolved before merge, or escalated as a constitution amendment.

## Governance

This constitution supersedes all other development practices documented elsewhere for
this project. In case of conflict, this document takes precedence.

**Amendment procedure**:
1. Open a PR with the proposed change to this file.
2. Increment `CONSTITUTION_VERSION` per semantic versioning rules:
   - MAJOR: backward-incompatible principle removal or redefinition
   - MINOR: new principle or section added / materially expanded guidance
   - PATCH: clarifications, wording fixes, non-semantic refinements
3. Update `LAST_AMENDED_DATE` to the merge date.
4. Run the consistency propagation checklist against all `.specify/templates/` files.
5. PR MUST be approved by at least one other contributor before merge.

**Compliance review**: All PRs and code reviews MUST verify compliance with the five Core
Principles above. The `Constitution Check` gate in `plan-template.md` provides the
per-feature checklist.

Refer to `CLAUDE.md` for runtime development commands, build instructions, and
environment variable reference.

**Version**: 1.0.0 | **Ratified**: 2026-03-06 | **Last Amended**: 2026-03-06
