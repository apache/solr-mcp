<!--
Sync Impact Report
- Version change: 0.0.0 (template) → 1.0.0 (initial ratification)
- Added sections: Core Principles (5), Technology Stack, Development Workflow, Governance
- Templates requiring updates: ✅ All templates are stock from github/spec-kit — no updates needed
- Follow-up TODOs: None
-->

# Solr MCP Server Constitution

## Core Principles

### I. MCP Protocol Fidelity

All features MUST conform to the Model Context Protocol specification. Tools are the primary interface — each tool MUST be self-contained, stateless, and annotated with `@McpTool`. STDIO transport MUST remain the default mode, and no feature may produce stdout output that could corrupt the MCP wire protocol. HTTP transport is an alternative for remote access and development tooling.

### II. Solr Version Compatibility

The server MUST maintain backward compatibility across supported Solr versions (8.11, 9.x, 10.x). New features MUST NOT hard-depend on version-specific Solr APIs without graceful degradation for older versions. Integration tests MUST pass against all supported Solr versions using the configurable `solr.test.image` system property. Breaking changes in upstream Solr (e.g., removed endpoints, renamed metrics) MUST be handled at the adapter layer, not exposed to MCP tool consumers.

### III. Test Coverage at Every Layer

Unit tests (`*Test.java`) with mocked dependencies MUST accompany every service class. Integration tests (`*IntegrationTest.java`) using Testcontainers MUST validate real Solr interactions for any feature that touches SolrJ or Solr APIs. Docker image tests (`@Tag("docker-integration")`) MUST verify containerized deployments. The full test suite MUST pass via `./gradlew build` before any code is merged.

### IV. Clean Separation of Concerns

Each MCP tool domain (search, indexing, collection management, schema) MUST reside in its own package with a dedicated service class. Cross-cutting concerns (configuration, security, transport) MUST be isolated in the `config/` or `security/` packages. Strategy pattern MUST be used for pluggable behaviors (e.g., document format creators). No service class may directly depend on another service class — shared logic MUST be extracted into utility classes.

### V. Simplicity and Minimalism

Features MUST solve a concrete, demonstrable user need — no speculative abstractions. Prefer fewer, well-tested MCP tools over many thin wrappers. Configuration MUST use environment variables with sensible defaults. Dependencies MUST be managed centrally in `gradle/libs.versions.toml`. Code formatting is enforced by Spotless and MUST pass `./gradlew spotlessCheck` before merge.

## Technology Stack

- **Language:** Java 25+
- **Framework:** Spring Boot 3.5.x, Spring AI 1.1.x
- **Build:** Gradle with Kotlin DSL, version catalogs (`libs.versions.toml`)
- **Testing:** JUnit 5, Testcontainers, JaCoCo for coverage
- **Containerization:** Jib (not Spring Boot Buildpacks — Buildpacks pollute stdout, breaking STDIO transport)
- **Formatting:** Spotless (Google Java Format)
- **Solr Client:** SolrJ 9.x (upgrade to 10.x when available on Maven Central)
- **CI:** GitHub Actions

## Development Workflow

1. **Branching:** Feature branches off `main`. Conventional commit messages (`feat`, `fix`, `docs`, `refactor`, `test`, `chore`).
2. **Formatting:** Run `./gradlew spotlessApply` before committing. CI enforces `spotlessCheck`.
3. **Testing:** `./gradlew build` runs the full suite (unit + integration). Docker tests run separately via `./gradlew dockerIntegrationTest`.
4. **Solr compatibility:** Test against multiple Solr versions using `-Dsolr.test.image=solr:<version>-slim`.
5. **Pull requests:** All changes go through PR review. CI MUST pass before merge.
6. **Spec-driven development:** For non-trivial features, use the `/speckit.*` workflow (specify → plan → tasks → implement) to produce design artifacts before writing code.

## Governance

This constitution is the authoritative source for project principles. All PRs and code reviews MUST verify compliance with these principles. Deviations require explicit justification in the PR description and approval from a maintainer.

Amendments to this constitution follow semantic versioning:
- **MAJOR:** Removing or redefining a core principle.
- **MINOR:** Adding a new principle or materially expanding guidance.
- **PATCH:** Clarifications, wording improvements, non-semantic refinements.

Amendments MUST be proposed via PR, documented with rationale, and update the version and date below.

Use `CLAUDE.md` for runtime development guidance that supplements (but does not override) this constitution.

**Version**: 1.0.0 | **Ratified**: 2026-03-08 | **Last Amended**: 2026-03-08
