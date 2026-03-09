# Feature Specification: GraalVM Native Docker Image

**Feature Branch**: `001-graalvm-native-image`
**Created**: 2026-03-08
**Status**: Draft
**Input**: Use graalvm to build a native executable to be used with the docker image

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Operator Deploys Fast-Starting Container (Priority: P1)

An operator pulls the Solr MCP Server Docker image and runs it. The container becomes ready to accept MCP connections within one second of starting, compared to several seconds with the current JVM-based image. This enables faster restarts, scaling events, and cold-start deployments.

**Why this priority**: Startup speed is the primary value of a native executable in a containerized setting. This is the most visible, measurable improvement for operators.

**Independent Test**: Can be fully tested by pulling the native Docker image, running it pointed at a Solr instance, and measuring time-to-first-successful-MCP-tool-call. Delivers a production-usable, fast-starting server.

**Acceptance Scenarios**:

1. **Given** the native Docker image is available, **When** a container is started with valid Solr configuration, **Then** the server is ready to accept MCP connections within 1 second.
2. **Given** a running native container, **When** an AI assistant sends an MCP tool request (e.g., search), **Then** the server responds correctly within normal latency bounds.
3. **Given** a container with STDIO mode configured, **When** the server starts, **Then** no output other than valid MCP JSON-RPC messages appears on stdout.

---

### User Story 2 - Build Engineer Produces Native Image via Standard Pipeline (Priority: P2)

A build engineer or CI pipeline runs the existing standard build command and obtains a Docker image containing the native executable — no manual steps, additional tooling installations, or separate build commands are required.

**Why this priority**: Without a simple, repeatable build process, the native image cannot be maintained or released reliably. This is a prerequisite for shipping.

**Independent Test**: Can be fully tested by running the standard build command on a clean environment and verifying that a runnable Docker image is produced. Delivers a reproducible artifact.

**Acceptance Scenarios**:

1. **Given** a repository checkout, **When** the standard build command is run, **Then** a Docker image is produced without requiring additional manual steps.
2. **Given** a CI pipeline running the build, **When** the build completes successfully, **Then** a native Docker image artifact is available for deployment.
3. **Given** the build fails due to a compilation issue, **When** the engineer inspects output, **Then** a clear error message identifies the failing component.

---

### User Story 3 - Operator Runs Native Image in HTTP Mode (Priority: P3)

An operator runs the native Docker image in HTTP transport mode, with optional OAuth2 authentication. All MCP tools remain accessible and all security controls function identically to the JVM-based image.

**Why this priority**: HTTP mode is used for remote access and MCP Inspector scenarios. Security correctness in the native image is non-negotiable but secondary to STDIO mode, which covers the primary Claude Desktop use case.

**Independent Test**: Can be fully tested by running the native container with HTTP mode and OAuth2 configuration and verifying that authenticated tool calls succeed while unauthenticated calls are rejected.

**Acceptance Scenarios**:

1. **Given** the native container is started in HTTP mode, **When** an authenticated client invokes any MCP tool, **Then** the tool executes and returns a correct response.
2. **Given** HTTP mode with OAuth2 configured, **When** an unauthenticated request arrives, **Then** the server rejects it with an appropriate error.
3. **Given** the native container in HTTP mode, **When** a Solr collection is listed, **Then** the response matches the output from the equivalent JVM-based container.

---

### Edge Cases

- What happens when the native image encounters a reflection-dependent runtime path that was not captured at build time? (Server must start and produce a clear error for the affected operation, not silently corrupt state.)
- What happens when the native container is started without a reachable Solr instance? (Server must start successfully and return appropriate errors on MCP tool calls, as the JVM image does today.)
- What happens when the native image is run on a CPU architecture not included in the build matrix? (A clear, actionable error at container start — not a silent runtime failure.)

## Clarifications

### Session 2026-03-08

- Q: Should the native image replace the JVM image or coexist as a separate artifact? → A: Coexist — both images released; native image tagged with `-native` suffix (e.g., `solr-mcp:<version>-native`); JVM image tag and behaviour unchanged.
- Research: Can Spring Boot Cloud Native Buildpacks (`bootBuildImage`) replace Jib for native image packaging? → No. The CNB lifecycle launcher (`/cnb/lifecycle/launcher`) is the entrypoint in every Buildpacks-built image and writes to stdout at container startup (sourcing profile scripts, setting environment variables) before exec-ing the application. This is specified CNB behaviour, not a bug. No fix was found in the buildpacks/lifecycle or Paketo projects as of March 2026. The Paketo Java Native Image Buildpack (`BP_NATIVE_IMAGE=true`) still uses the launcher wrapper. Jib sets the native binary directly as the container entrypoint with no wrapper process, producing zero stdout at startup. Jib remains the correct choice for STDIO mode compliance.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The build system MUST produce a Docker image containing a natively compiled server executable that does not require a JVM in the container at runtime.
- **FR-002**: The native Docker image MUST support both STDIO and HTTP transport modes, selectable via the existing `PROFILES` environment variable.
- **FR-003**: The native Docker image MUST expose all existing MCP tools (search, indexing, collection management, schema introspection) with identical behaviour to the JVM-based image.
- **FR-004**: In STDIO mode, the native server MUST write only valid MCP JSON-RPC messages to stdout — no startup banners, log lines, or diagnostic output.
- **FR-005**: The native Docker image MUST accept the same environment variables as the JVM-based image: `SOLR_URL`, `PROFILES`, and `OAUTH2_ISSUER_URI`.
- **FR-006**: All existing Docker image integration tests MUST pass against the native image without modification.
- **FR-007**: The native Docker image MUST be produced for both `linux/amd64` and `linux/arm64` platforms, matching the current multi-platform support.
- **FR-008**: When OAuth2 is configured in HTTP mode, the native image MUST enforce authentication on sensitive operations (collection creation, deletion, schema modification) identically to the JVM image.
- **FR-009**: The native Docker image MUST be released alongside the JVM-based image as an additional artifact. The JVM image tag (`solr-mcp:<version>`) MUST remain unchanged; the native image MUST use the tag suffix `-native` (e.g., `solr-mcp:<version>-native`). Neither image variant replaces the other.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The native container is ready to accept its first MCP tool call within 1 second of container start, measured from `docker run` to first successful response.
- **SC-002**: The native container uses no more than 50% of the peak memory consumed by the equivalent JVM-based container under the same idle workload.
- **SC-003**: 100% of existing Docker image integration tests pass against the native image with no test modifications.
- **SC-004**: The native Docker image is produced by a single standard build command — no additional manual steps beyond what the JVM image requires.
- **SC-005**: The native Docker image is smaller than the current JVM-based image.
- **SC-006**: All four MCP tool categories (search, indexing, collection management, schema) return correct results in the native container across all supported Solr versions (8.11, 9.4, 9.9, 9.10, 10).

## Assumptions

- Jib is used to package the native executable into the Docker image. Spring Boot Cloud Native Buildpacks (`bootBuildImage`) are explicitly ruled out: the CNB lifecycle launcher writes to stdout at container startup, violating STDIO mode purity. This was confirmed via research in March 2026 — no fix exists in the CNB lifecycle or Paketo projects. Even with `BP_NATIVE_IMAGE=true`, Paketo images retain the launcher wrapper.
- Native image compilation will be integrated into the existing Gradle build, not added as a separate build script.
- The current multi-platform Docker image targets (amd64, arm64) remain the required build output.
- Native image compilation requires GraalVM 25+ to be available in the build environment (local and CI). CI workflows must include a step to install GraalVM 25 before invoking native compilation.
- SolrJ's network communication and serialization will be verified to work in the native image; any missing reflection hints will be added as part of this feature.
- This feature does not change the server's runtime API surface, configuration schema, or MCP protocol behaviour — only the packaging and startup characteristics change.
- A constitution amendment is required to formally add native image compilation to the Technology Stack section, as it represents a new build-time tool.
