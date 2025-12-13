# Docker Publishing Guide for Apache Solr MCP

This guide documents the Docker image publishing process for Apache Solr MCP, including nightly builds, release candidates, and official releases.

## Overview

The Solr MCP project publishes Docker images to multiple registries:

1. **GitHub Container Registry (GHCR)**: `ghcr.io/apache/solr-mcp`
2. **Docker Hub Official**: `apache/solr-mcp` (requires Apache PMC credentials)
3. **Docker Hub Nightly**: `apache/solr-mcp-nightly` (for pre-release builds)

## Build System

The project uses **Jib** (Google's containerization plugin) for building Docker images:
- No Docker daemon required for building
- Multi-platform support (linux/amd64 and linux/arm64)
- Optimized layering for faster deployments
- Reproducible builds

## Publishing Workflows

### 1. Development Builds (Per-merge to GHCR)

Currently, this repository does not define an automated workflow for per-merge dev images. The recommended practice is to publish per-merge or ad-hoc development images to GHCR (not Docker Hub under `apache/`). This keeps the `apache/` namespace reserved for nightlies and voted releases.

- Suggested images (if/when automated):
  - `ghcr.io/{owner}/solr-mcp:VERSION-SNAPSHOT-SHA`
  - `ghcr.io/{owner}/solr-mcp:latest`
- No ASF vote required for dev images (they are not releases and must be clearly marked as such).

### 2. Nightly Builds

**Workflow**: `.github/workflows/nightly-build.yml`

- **Schedule**: Daily at 2 AM UTC
- **Images Published**:
  - `apache/solr-mcp-nightly:nightly-YYYYMMDD-SHA`
  - `apache/solr-mcp-nightly:latest-nightly`
- **Artifacts**:
  - Source tarball to `https://nightlies.apache.org/solr/mcp/`
  - GitHub pre-release with build artifacts
- **No ASF vote required**

### 3. Official Releases

**Workflow**: `.github/workflows/release-publish.yml`

- **Trigger**: Manual (after ASF vote passes)
- **Prerequisites**:
  - 72-hour ASF voting period completed
  - Successful PMC vote
  - Release artifacts signed by Release Manager
- **Images Published**:
  - `apache/solr-mcp:VERSION`
  - `apache/solr-mcp:latest`
  - `apache/solr-mcp:MAJOR`
  - `apache/solr-mcp:MAJOR.MINOR`
  - `ghcr.io/apache/solr-mcp:VERSION`

## ASF Policy Notes

- The authoritative ASF release is the signed source distribution published to the ASF distribution system (`dist.apache.org` / `downloads.apache.org` mirrors). Docker images and other binaries are considered convenience binaries and must be built from the voted source, but they are not the release of record.
- Releases require a minimum 72-hour vote with at least three +1 binding PMC votes. Only after the vote passes may convenience binaries (e.g., Docker images) be published.
- Release artifacts must be signed by the Release Manager using their PGP key that is present in the project `KEYS` file. Automated signing via ASF Infra may be possible but must be explicitly arranged with INFRA; manual RM signing remains the baseline.
- Nightly and per-merge builds are allowed as non-release artifacts. They must be clearly marked as such and must not be uploaded to the ASF release distribution system.

## ASF Release Process

### Step 1: Create Release Candidate

```bash
# Tag the release candidate
git tag -s v1.0.0-rc1 -m "Release candidate 1 for version 1.0.0"

# Create source distribution
tar czf solr-mcp-1.0.0-rc1-src.tar.gz --exclude='.git' --exclude='build' .

# Sign the release
gpg --armor --detach-sign solr-mcp-1.0.0-rc1-src.tar.gz

# Generate checksums
sha512sum solr-mcp-1.0.0-rc1-src.tar.gz > solr-mcp-1.0.0-rc1-src.tar.gz.sha512
```

### Step 2: Stage for Voting

1. Upload release candidate to Apache staging area
2. Send vote email to dev@solr.apache.org
3. Wait for 72-hour voting period

### Step 3: After Successful Vote

Trigger the release publish workflow:

```bash
# Via GitHub UI
# Go to Actions → Release Publish → Run workflow

# Fill in:
# - Release version: 1.0.0
# - Release candidate: rc1
# - Vote thread URL: https://lists.apache.org/...
# - Use ASF code signing: true (if available)
```

## Building Docker Images Locally

### Using Gradle with Jib

```bash
# Build to local Docker daemon
./gradlew jibDockerBuild

# Build and push to registry
./gradlew jib -Djib.to.image=myregistry/solr-mcp:my-tag \
              -Djib.to.auth.username=USERNAME \
              -Djib.to.auth.password=TOKEN
```

### Multi-platform Build

The Jib configuration in `build.gradle.kts` automatically builds for:
- `linux/amd64` (x86_64)
- `linux/arm64` (Apple Silicon, ARM servers)

## Registry Authentication

### GitHub Container Registry

GHCR uses the built-in `GITHUB_TOKEN` in workflows. For local pushing:

```bash
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
```

### Docker Hub

For Apache official images, set these secrets in GitHub:
- `DOCKERHUB_APACHE_USERNAME`
- `DOCKERHUB_APACHE_TOKEN`

Create token at: https://hub.docker.com/settings/security

### Apache Nightlies

Requires Apache committer credentials:
- `APACHE_NIGHTLIES_USER`
- `APACHE_NIGHTLIES_KEY`

## MCP Registry Integration

The Docker image includes MCP metadata labels:

```dockerfile
LABEL io.modelcontextprotocol.server.name="io.github.apache/solr-mcp"
```

After release, the image should be registered with the MCP registry for discoverability.

## Security Considerations

### Code Signing

For official releases, we aim to use ASF's code signing infrastructure:

1. Contact ASF INFRA for setup requirements
2. Enable in release workflow with `sign_with_asf_infra: true`
3. Signatures will be automatically applied to artifacts

### Image Scanning

All images should be scanned for vulnerabilities:

```bash
# Using Docker Scout
docker scout cves apache/solr-mcp:latest

# Using Trivy
trivy image apache/solr-mcp:latest
```

### SBOM Generation

Software Bill of Materials can be generated:

```bash
# If CycloneDX is configured
./gradlew cyclonedxBom
```

## Troubleshooting

### Jib Build Issues

If Jib can't find Docker:

```bash
# Set Docker executable path
export DOCKER_EXECUTABLE=/usr/local/bin/docker
./gradlew jibDockerBuild
```

### Authentication Failures

For registry authentication issues:

1. Verify credentials are correct
2. Check token permissions (write:packages for GHCR)
3. Ensure tokens haven't expired

### Multi-platform Build Failures

If ARM64 builds fail:

1. Ensure base image supports ARM64
2. Check that all dependencies are multi-platform compatible
3. Consider using emulation for testing: `docker run --platform linux/arm64`

## Versioning Strategy

- **Main branch**: `VERSION-SNAPSHOT-SHA` (e.g., `0.0.1-SNAPSHOT-a1b2c3d`)
- **Nightly**: `nightly-YYYYMMDD-SHA` (e.g., `nightly-20240115-a1b2c3d`)
- **Release**: Semantic versioning `MAJOR.MINOR.PATCH` (e.g., `1.0.0`)

## Release Checklist

- [ ] Create release branch
- [ ] Update version in `build.gradle.kts`
- [ ] Update CHANGELOG.md
- [ ] Tag release candidate
- [ ] Create source distribution
- [ ] Sign artifacts with GPG (RM key listed in project `KEYS`)
- [ ] Generate checksums (.sha512)
- [ ] Upload to Apache staging
- [ ] Send vote email (minimum 72 hours; need ≥3 +1 PMC binding votes)
- [ ] After vote passes: publish signed source release to dist.apache.org (mirrors)
- [ ] Verify `KEYS` is up to date and signatures/sha512 verify
- [ ] Trigger the Release Publish workflow to push Docker images (convenience binaries)
- [ ] Verify Docker images are published to Docker Hub and GHCR
- [ ] Update MCP registry (optional)
- [ ] Announce release (dev@ / user@ / website)
- [ ] Close GitHub milestone

## Contact

For questions about the release process:
- Apache Solr Dev List: dev@solr.apache.org
- ASF INFRA: https://infra.apache.org/

For Docker/Jib specific issues:
- Create issue at: https://github.com/apache/solr-mcp/issues