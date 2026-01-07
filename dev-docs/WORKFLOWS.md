# GitHub Actions Workflows Guide

This guide explains when and how to use each GitHub Actions workflow in the project.

## Quick Reference

| Workflow                                       | Purpose               | Trigger              | Status     | Use For                |
|------------------------------------------------|-----------------------|----------------------|------------|------------------------|
| [build-and-publish.yml](#build-and-publishyml) | Development CI/CD     | Automatic (push/PR)  | ✅ Active   | Daily development      |
| [auto-release.yml](#auto-releaseyml)           | Automated releases    | Automatic (merge)    | ✅ Active   | Version tagging        |
| [release-publish.yml](#release-publishyml)     | Official ASF releases | Manual (after vote)  | ✅ Active   | Production releases    |
| [nightly-build.yml](#nightly-buildyml)         | Nightly builds        | Scheduled (2 AM UTC) | ✅ Active   | Latest unstable builds |
| [atr-release-test.yml](#atr-release-testyml)   | ATR testing           | Manual (safe mode)   | ✅ Ready    | Testing ATR workflow   |
| [atr-release.yml](#atr-releaseyml)             | ATR production        | Manual (blocked)     | ⚠️ Blocked | Future ATR releases    |

## Semantic Versioning

This project uses the [git-semver-plugin](https://github.com/jmongard/Git.SemVersioning.Gradle) for automatic version management based on [Conventional Commits](https://www.conventionalcommits.org/).

### How Versioning Works

```
git tag v1.0.0
    │
    ├── fix: handle null values      → patch bump (1.0.1)
    ├── feat: add new search filter  → minor bump (1.1.0)
    └── feat!: breaking API change   → major bump (2.0.0)

Current version: 1.1.0-SNAPSHOT (calculated from commits)
```

### Version Commands

```bash
# Check current calculated version
./gradlew printVersion

# View changelog from commits
./gradlew printChangeLog

# Create a release (tag + commit)
./gradlew releaseVersion
```

### Commit Message Format

Use conventional commit prefixes to control version bumps:

| Prefix | Version Bump | Example |
|--------|--------------|---------|
| `fix:` | Patch (0.0.X) | `fix: handle null pointer in search` |
| `feat:` | Minor (0.X.0) | `feat: add faceted search support` |
| `feat!:` or `BREAKING CHANGE:` | Major (X.0.0) | `feat!: redesign query API` |
| `docs:`, `chore:`, `test:`, `ci:` | No bump | `docs: update README` |

## Decision Tree: Which Workflow Should I Use?

```
┌─────────────────────────────────────────────────────────────┐
│ START: What do you need to do?                              │
└────────────────────┬────────────────────────────────────────┘
                     │
    ┌────────────────┼────────────────┬───────────────┐
    │                │                │               │
    ▼                ▼                ▼               ▼
┌─────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ Develop │    │ Release  │    │ Official │    │   Test   │
│  Code   │    │ Version  │    │ ASF Rel  │    │    ATR   │
└────┬────┘    └─────┬────┘    └─────┬────┘    └─────┬────┘
     │               │               │               │
     ▼               ▼               ▼               ▼
┌─────────────┐ ┌───────────┐ ┌───────────┐ ┌──────────┐
│build-and-   │ │auto-      │ │ release-  │ │atr-      │
│publish.yml  │ │release    │ │ publish   │ │release-  │
│             │ │  .yml     │ │   .yml    │ │test.yml  │
│✅ Automatic │ │           │ │           │ │          │
│   on PR     │ │✅ Auto on │ │✅ Manual  │ │✅ Manual │
│             │ │merge main │ │after vote │ │safe mode │
└─────────────┘ └─────┬─────┘ └───────────┘ └──────────┘
                      │
                      ▼
              ┌───────────────┐
              │ Creates tag   │
              │ v1.0.0        │
              │ + CHANGELOG   │
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │build-and-     │
              │publish.yml    │
              │(triggered by  │
              │ v* tag)       │
              └───────────────┘
```

---

## Detailed Workflow Documentation

### build-and-publish.yml

**Purpose**: Continuous integration and deployment for development

#### When to Use

- ✅ Automatic on every commit to `main`
- ✅ Automatic on pull requests (build + test only)
- ✅ When you merge a PR and want development images published

#### When NOT to Use

- ❌ For official Apache releases (use `release-publish.yml`)
- ❌ For production deployments

#### Triggers

```yaml
on:
  push:
    branches: [main]
    tags: ['v*']  # ⚠️ Avoid using tags; prefer release-publish.yml
  pull_request:
    branches: [main]
  workflow_dispatch:  # Manual trigger
```

#### What It Does

1. **Builds** the project with Gradle
2. **Runs tests** and generates coverage reports
3. **Publishes Docker images** to:
    - GitHub Container Registry: `ghcr.io/OWNER/solr-mcp:VERSION-SHA`
    - Docker Hub: `DOCKERHUB_USERNAME/solr-mcp:VERSION-SHA` (if secrets configured)

#### Image Tagging Strategy

- **Main branch**: `VERSION-SNAPSHOT-SHA` + `latest`
    - Example: `1.0.0-SNAPSHOT-a1b2c3d`, `latest`
- **Tags** (discouraged): `VERSION` + `latest`
    - Example: `1.0.0`, `latest`

#### Required Secrets

- `DOCKERHUB_USERNAME` (optional) - Your Docker Hub username
- `DOCKERHUB_TOKEN` (optional) - Docker Hub access token
- `GITHUB_TOKEN` (automatic) - For GHCR publishing

#### How to Use

**Automatic (Recommended)**:

```bash
# Just merge your PR - workflow runs automatically
git checkout main
git merge feature-branch
git push origin main
```

**Manual Trigger**:

```bash
# Via GitHub UI: Actions → Build and Publish → Run workflow
# Or via CLI:
gh workflow run build-and-publish.yml
```

#### Example Use Cases

- Merging a PR with new features
- Testing changes in a development environment
- Creating preview builds for testing

---

### auto-release.yml

**Purpose**: Automated version tagging and changelog generation on merge to main

#### When to Use

- ✅ Automatic on every merge to `main`
- ✅ Creates version tags based on conventional commits
- ✅ Generates and updates CHANGELOG.md
- ✅ Triggers Docker image publishing via build-and-publish.yml

#### When NOT to Use

- ❌ This is fully automatic - no manual intervention needed
- ❌ For official ASF releases (use `release-publish.yml` after vote)

#### Triggers

```yaml
on:
  push:
    branches:
      - main
```

#### What It Does

1. **Calculates version** from git tags and conventional commits
2. **Generates changelog** from commit messages
3. **Creates release commit** with updated CHANGELOG.md
4. **Creates version tag** (e.g., `v1.0.0`)
5. **Pushes tag** which triggers `build-and-publish.yml`
6. **Creates GitHub Release** with changelog

#### Version Calculation

The [git-semver-plugin](https://github.com/jmongard/Git.SemVersioning.Gradle) analyzes commits since the last tag:

```
v1.0.0 (last tag)
   │
   ├── fix: bug fix           → 1.0.1
   ├── feat: new feature      → 1.1.0
   └── feat!: breaking change → 2.0.0
```

#### How It Works

```
PR Merged to main
       │
       ▼
┌──────────────────────┐
│ 1. Calculate version │  ./gradlew printVersion
│    (from commits)    │  → 1.1.0-SNAPSHOT
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ 2. Generate changelog│  ./gradlew printChangeLog
│    (from commits)    │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ 3. Create release    │  git tag v1.1.0
│    commit + tag      │  git push --tags
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ 4. GitHub Release    │  Created automatically
│    with changelog    │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ build-and-publish    │  Triggered by v* tag
│ publishes Docker     │
└──────────────────────┘
```

#### Example Commit Messages

```bash
# These trigger version bumps:
git commit -m "feat: add new search filter"      # Minor bump
git commit -m "fix: handle null pointer"         # Patch bump
git commit -m "feat!: redesign API"              # Major bump

# These don't trigger version bumps:
git commit -m "docs: update README"
git commit -m "chore: update dependencies"
git commit -m "test: add unit tests"
```

#### Skipping Releases

Release commits are automatically skipped to prevent infinite loops:

```yaml
if: "!startsWith(github.event.head_commit.message, 'chore(release):')"
```

---

### release-publish.yml

**Purpose**: Official Apache Software Foundation release publishing

#### When to Use

- ✅ ONLY after ASF vote passes (72+ hours, 3+ PMC votes)
- ✅ For official production releases
- ✅ When publishing to `apache/solr-mcp` namespace

#### When NOT to Use

- ❌ Before ASF vote completes
- ❌ For development builds (use `build-and-publish.yml`)
- ❌ For testing (use `atr-release-test.yml`)

#### Triggers

```yaml
on:
  workflow_dispatch:  # ONLY manual trigger
    inputs:
      release_version:    # e.g., 1.0.0
      release_candidate:  # e.g., rc1
      vote_thread_url:    # (optional)
      sign_with_asf_infra: # (future)
```

#### What It Does

1. **Validates** release tag exists (`v1.0.0-rc1`)
2. **Builds** the project from approved RC tag
3. **Signs** artifacts (placeholder for future automation)
4. **Publishes Docker images** to:
    - Docker Hub: `apache/solr-mcp:1.0.0`, `apache/solr-mcp:1.0`, `apache/solr-mcp:1`, `apache/solr-mcp:latest`
    - GitHub Container Registry: `ghcr.io/apache/solr-mcp:1.0.0`, `latest`
5. **Publishes to MCP Registry** for discoverability
6. **Creates GitHub Release** with artifacts

#### Image Tagging Strategy

- **Full version**: `1.0.0`
- **Minor version**: `1.0`
- **Major version**: `1`
- **Latest**: `latest`

#### Required Secrets

- `DOCKERHUB_APACHE_USERNAME` - Apache PMC Docker Hub credentials
- `DOCKERHUB_APACHE_TOKEN` - Apache PMC Docker Hub token
- `GITHUB_TOKEN` (automatic) - For GHCR and GitHub releases

#### How to Use

**Step-by-Step Process**:

1. **Create Release Candidate**:
   ```bash
   git tag v1.0.0-rc1 -m "Release candidate 1 for version 1.0.0"
   git push origin v1.0.0-rc1
   ```

2. **Call ASF Vote** (email to dev@solr.apache.org):
   ```
   Subject: [VOTE] Release Apache Solr MCP 1.0.0 RC1

   [Include vote email content per ASF guidelines]
   ```

3. **Wait for Vote** (minimum 72 hours, need 3+ binding +1 votes)

4. **After Vote Passes**, trigger workflow:
   ```bash
   # Via GitHub UI: Actions → Release Publish → Run workflow
   # Fill in:
   #   release_version: 1.0.0
   #   release_candidate: rc1
   #   vote_thread_url: https://lists.apache.org/...

   # Or via CLI:
   gh workflow run release-publish.yml \
     -f release_version=1.0.0 \
     -f release_candidate=rc1 \
     -f vote_thread_url=https://lists.apache.org/thread/...
   ```

5. **Verify Publication**:
   ```bash
   # Check Docker Hub
   docker pull apache/solr-mcp:1.0.0

   # Check MCP Registry
   curl "https://registry.modelcontextprotocol.io/v0/servers?search=io.github.apache/solr-mcp" | jq .
   ```

6. **Announce Release** (email to announce@apache.org)

#### Example Use Cases

- Publishing version 1.0.0 after successful vote
- Creating official production releases
- Updating MCP Registry with new release

---

### nightly-build.yml

**Purpose**: Automated nightly builds for testing latest changes

#### When to Use

- ✅ Automatic daily at 2 AM UTC
- ✅ For testing bleeding-edge changes
- ✅ When you want the absolute latest build

#### When NOT to Use

- ❌ For production use (unstable)
- ❌ For official releases

#### Triggers

```yaml
on:
  schedule:
    - cron: '0 2 * * *'  # 2 AM UTC daily
  workflow_dispatch:
    inputs:
      skip_docker:  # Skip Docker publishing
```

#### What It Does

1. **Builds** the project from `main` branch
2. **Creates source distribution** with date stamp
3. **Publishes Docker image** to:
    - Docker Hub: `apache/solr-mcp-nightly:nightly-YYYYMMDD-SHA`
    - Tag: `latest-nightly`
4. **Uploads to Apache Nightlies** (if configured)
5. **Creates GitHub pre-release** with artifacts
6. **Cleans up** old nightly builds (keeps last 7)

#### Image Tagging Strategy

- **Nightly tag**: `nightly-20250112-a1b2c3d`
- **Latest nightly**: `latest-nightly`

#### Required Secrets

- `DOCKERHUB_APACHE_USERNAME` - Apache PMC Docker Hub credentials
- `DOCKERHUB_APACHE_TOKEN` - Apache PMC Docker Hub token
- `APACHE_NIGHTLIES_USER` (optional) - For nightlies.apache.org uploads
- `APACHE_NIGHTLIES_KEY` (optional) - SSH key for nightlies

#### How to Use

**Automatic (Default)**:

```bash
# Runs automatically every night at 2 AM UTC
# No action needed
```

**Manual Trigger**:

```bash
# Via GitHub UI: Actions → Nightly Build → Run workflow
# Or via CLI:
gh workflow run nightly-build.yml

# Skip Docker publishing:
gh workflow run nightly-build.yml -f skip_docker=true
```

**Using Nightly Images**:

```bash
# Pull today's nightly
docker pull apache/solr-mcp-nightly:latest-nightly

# Pull specific date
docker pull apache/solr-mcp-nightly:nightly-20250112-a1b2c3d
```

#### Example Use Cases

- Testing unreleased features
- Catching bugs early in development
- Integration testing with latest changes
- Providing preview builds to early adopters

---

### atr-release-test.yml

**Purpose**: Test Apache Trusted Releases (ATR) workflow safely

#### When to Use

- ✅ Testing ATR workflow logic before onboarding
- ✅ Validating artifact creation and checksums
- ✅ Learning how ATR works
- ✅ Testing ATR uploads after onboarding (with `dry_run=false`)

#### When NOT to Use

- ❌ For production releases (use `atr-release.yml` when ready)
- ❌ When you need guaranteed uploads

#### Triggers

```yaml
on:
  workflow_dispatch:
    inputs:
      release_version:   # Default: 0.0.1-test
      release_candidate: # Default: rc1
      dry_run:          # Default: true (SAFE MODE)
      skip_compose:     # Skip if already done
      skip_vote:        # Skip vote phase
```

#### What It Does

1. **Builds** test artifacts (creates test tag if needed)
2. **Creates distributions** with checksums
3. **Simulates GPG signing** (test mode)
4. **Dry Run Mode** (default):
    - Shows what would be uploaded to ATR
    - No actual uploads
    - Completely safe
5. **Real Mode** (`dry_run=false`):
    - Actually uploads to ATR
    - Requires ASF_USERNAME secret
    - Requires ATR onboarding
6. **Generates vote email template**
7. **Tests connectivity** to ATR platform

#### Required Secrets (for Real Mode)

- `ASF_USERNAME` - Your ASF ID (only needed if `dry_run=false`)

#### How to Use

**Safe Testing (Recommended First)**:

```bash
# Via GitHub UI: Actions → ATR Release Process (TEST) → Run workflow
# Leave all defaults (dry_run=true)

# Or via CLI:
gh workflow run atr-release-test.yml \
  -f release_version=0.0.1-test \
  -f release_candidate=rc1 \
  -f dry_run=true
```

**Real ATR Upload (After Onboarding)**:

```bash
# ONLY after ATR onboarding approved
gh workflow run atr-release-test.yml \
  -f release_version=0.0.1-test \
  -f release_candidate=rc1 \
  -f dry_run=false  # ⚠️ Will actually upload to ATR
```

**Testing Complete Flow**:

```bash
# 1. Test compose phase
gh workflow run atr-release-test.yml \
  -f dry_run=false \
  -f skip_vote=false

# 2. (Manually send test vote email)

# 3. Test finish phase
gh workflow run atr-release-test.yml \
  -f skip_compose=true \
  -f skip_vote=true \
  -f dry_run=false
```

#### Example Use Cases

- Learning ATR workflow before onboarding
- Validating artifact generation
- Testing ATR connectivity
- Practicing release process

---

### atr-release.yml

**Purpose**: Apache Trusted Releases (ATR) automated release process

#### Current Status

⚠️ **BLOCKED** - Cannot be used until prerequisites are met

#### Prerequisites Required

Before this workflow can be used, you must complete:

1. **Implement Automated Release Signing**
    - Add trusted hardware verification gate
    - Request ASF signing key from INFRA (2-4 week process)
    - Integrate automated signing in workflows
    - See: https://infra.apache.org/release-signing.html

2. **Request ATR Onboarding**
    - Email dev@tooling.apache.org
    - Provide evidence of automated signing
    - Wait for approval (1-2 weeks)

3. **Configure Secrets**
    - Add `ASF_USERNAME` secret with your ASF ID

For complete implementation guide, see: [dev-docs/ATR_TESTING_GUIDE.md](ATR_TESTING_GUIDE.md)

#### When to Use (Future)

- ✅ After automated signing is implemented
- ✅ After ATR onboarding is approved
- ✅ For fully automated ASF releases

#### What It Will Do (When Ready)

1. **Compose**: Build and upload artifacts to ATR
2. **Vote**: Generate vote email template (manual voting)
3. **Finish**: Resolve vote and announce release
4. **Publish**: Trigger Docker and MCP Registry publishing

#### Current Workaround

Until ATR is ready, use `release-publish.yml` for official releases.

#### How to Use (When Ready)

**After Prerequisites Met**:

```bash
# 1. Compose and upload to ATR
gh workflow run atr-release.yml \
  -f release_version=1.0.0 \
  -f release_candidate=rc1

# 2. Send vote email (manual)

# 3. After vote passes, finalize
gh workflow run atr-release.yml \
  -f release_version=1.0.0 \
  -f release_candidate=rc1 \
  -f skip_compose=true \
  -f skip_vote=true
```

---

## Workflow Comparison Matrix

| Feature              | build-and-publish | auto-release    | release-publish | nightly-build      | atr-release-test | atr-release |
|----------------------|-------------------|-----------------|-----------------|--------------------| -----------------|-------------|
| **Status**           | ✅ Active          | ✅ Active        | ✅ Active        | ✅ Active           | ✅ Ready          | ⚠️ Blocked  |
| **Trigger**          | Automatic         | Auto (merge)    | Manual          | Scheduled          | Manual           | Manual      |
| **Creates Tags**     | ❌ No              | ✅ Yes           | ❌ No            | ❌ No               | ❌ No             | ❌ No        |
| **Changelog**        | ❌ No              | ✅ Yes           | ❌ No            | ❌ No               | ❌ No             | ❌ No        |
| **Docker Namespace** | Personal/GHCR     | N/A             | `apache/*`      | `apache/*-nightly` | Test             | `apache/*`  |
| **MCP Registry**     | ❌ No              | ❌ No            | ✅ Yes           | ❌ No               | ❌ No             | ✅ Yes       |
| **ASF Vote**         | ❌ Not required    | ❌ Not required  | ✅ Required      | ❌ Not required     | ❌ Not required   | ✅ Required  |
| **Signing**          | ❌ No              | ❌ No            | ⚠️ Manual       | ❌ No               | ⚠️ Simulated     | ✅ Automated |
| **Production Ready** | ❌ No              | ✅ Yes           | ✅ Yes           | ❌ No               | ❌ No             | ⚠️ Future   |
| **Can Test Now**     | ✅ Yes             | ✅ Yes           | ✅ Yes           | ✅ Yes              | ✅ Yes            | ❌ No        |

---

## Common Scenarios

### Scenario 1: I merged a PR and want to test the changes

**Use**: `build-and-publish.yml` + `auto-release.yml` (both automatic)

```bash
# 1. Merge your PR with conventional commit messages
git merge feature-branch  # e.g., "feat: add new search filter"
git push origin main

# 2. auto-release.yml automatically:
#    - Calculates version (e.g., 1.1.0)
#    - Generates CHANGELOG.md
#    - Creates tag v1.1.0
#    - Creates GitHub Release

# 3. build-and-publish.yml automatically:
#    - Builds Docker image
#    - Publishes to ghcr.io/apache/solr-mcp:1.1.0
```

### Scenario 2: I want to create a version release

**Use**: Automatic via `auto-release.yml`

```bash
# Just merge PRs with conventional commits - releases happen automatically!

# Example commits that trigger releases:
git commit -m "feat: add faceted search"    # → Minor version bump
git commit -m "fix: handle null pointer"    # → Patch version bump
git commit -m "feat!: new query API"        # → Major version bump

# Check what version will be released:
./gradlew printVersion
```

### Scenario 3: I want to create an official ASF release

**Use**: `release-publish.yml` (manual after vote)

```bash
# 1. Create RC tag
git tag v1.0.0-rc1 -m "Release candidate 1"
git push origin v1.0.0-rc1

# 2. Call ASF vote (email to dev@solr.apache.org)
# 3. Wait 72+ hours for vote to pass
# 4. Trigger workflow
gh workflow run release-publish.yml \
  -f release_version=1.0.0 \
  -f release_candidate=rc1
```

### Scenario 4: I want to test the latest unreleased code

**Use**: `nightly-build.yml` (automatic daily)

```bash
# Pull the latest nightly
docker pull apache/solr-mcp-nightly:latest-nightly
```

### Scenario 5: I want to prepare for ATR

**Use**: `atr-release-test.yml` (manual testing)

```bash
# Test the workflow safely
gh workflow run atr-release-test.yml \
  -f dry_run=true  # Safe mode - no uploads
```

### Scenario 6: I'm ready to use ATR for releases

**Use**: `atr-release.yml` (blocked - see prerequisites)

```
❌ Cannot use yet
✅ Complete prerequisites first:
   1. Implement automated signing
   2. Request ATR onboarding
   3. See: dev-docs/ATR_TESTING_GUIDE.md
```

---

## Troubleshooting

### "Docker Hub credentials not configured"

**Solution**: Add secrets to repository:

```bash
gh secret set DOCKERHUB_USERNAME --body "your-username"
gh secret set DOCKERHUB_TOKEN --body "your-access-token"
```

### "Release tag not found"

**Solution**: Ensure you've created and pushed the tag:

```bash
git tag v1.0.0-rc1 -m "Release candidate 1"
git push origin v1.0.0-rc1
```

### "ASF_USERNAME not configured"

**Solution**: Add ASF username secret (required for ATR):

```bash
gh secret set ASF_USERNAME --body "your-asf-id"
```

### "ATR platform unreachable"

**Solution**: Check ATR status:

- Platform: https://release-test.apache.org
- Health: https://release-test.apache.org/health
- Contact: dev@tooling.apache.org

### "Vote hasn't passed yet"

**Solution**: Wait for ASF vote to complete:

- Minimum 72 hours
- Need 3+ binding +1 votes from PMC
- No binding -1 votes

---

## Related Documentation

- [ATR Testing Guide](ATR_TESTING_GUIDE.md) - Complete ATR implementation path
- [Docker Publishing](DOCKER_PUBLISHING.md) - Docker image publishing details
- [Development Guide](DEVELOPMENT.md) - Development workflow
- [Deployment Guide](DEPLOYMENT.md) - Deployment procedures

---

## Getting Help

- **Workflow Issues**: Check GitHub Actions logs
- **Docker Issues**: See [DOCKER_PUBLISHING.md](DOCKER_PUBLISHING.md)
- **ATR Questions**: Email dev@tooling.apache.org
- **ASF Process**: Email dev@solr.apache.org
- **General Help**: File a GitHub issue

---

## Quick Command Reference

```bash
# Semantic versioning commands (git-semver-plugin)
./gradlew printVersion        # Show current calculated version
./gradlew printChangeLog      # Show changelog from commits
./gradlew releaseVersion      # Create release commit + tag

# Trigger workflows manually
gh workflow run build-and-publish.yml
gh workflow run release-publish.yml -f release_version=1.0.0 -f release_candidate=rc1
gh workflow run nightly-build.yml
gh workflow run atr-release-test.yml -f dry_run=true

# View workflow runs
gh run list
gh run view <run-id>
gh run watch

# Download workflow artifacts
gh run download <run-id>

# Manage secrets
gh secret list
gh secret set SECRET_NAME --body "value"
gh secret delete SECRET_NAME

# Tag management for releases
git tag v1.0.0-rc1 -m "Release candidate 1"
git push origin v1.0.0-rc1
git tag -d v1.0.0-rc1  # Delete local
git push origin :refs/tags/v1.0.0-rc1  # Delete remote
```

---

**Last Updated**: 2026-01-06
**Workflows Version**: Compatible with all workflows as of this date