# ATR Release Workflow Testing Guide

This guide explains the proper path to implementing Apache Trusted Releases (ATR) automation and how to test the
workflow.

## ⚠️ Important Prerequisites

Based on Apache developer feedback:

> **"Once you have achieved automated release signing, then you could test ATR automation."**

### Required Order of Implementation

```
Step 1: Implement Automated Release Signing
   ↓
Step 2: Onboard to ATR
   ↓
Step 3: Test ATR Automation
   ↓
Step 4: Use in Production
```

## Prerequisites Before ATR

### Requirements for Automated Release Signing (from ASF INFRA)

Your project must meet these three conditions:

1. ✅ **Reproducible builds**: All artifacts can be built reproducibly
    - **Status for Solr MCP**: ✅ READY (Jib provides this)
    - Evidence: `build.gradle.kts:252`, `DOCKER_PUBLISHING.md:19`

2. ⚠️ **Staging validation**: CI deploys artifacts to a staging environment
    - **Status for Solr MCP**: ⚠️ PARTIAL
    - Current: GitHub releases, Docker registries
    - Needed: Explicit staging step before final publication

3. ❌ **Trusted hardware verification**: Artifacts reproduced on trusted hardware before publication
    - **Status for Solr MCP**: ❌ NOT IMPLEMENTED
    - Needed: Manual verification step by PMC member

### Current Status for Solr MCP

| Requirement                   | Status          | Action Needed                |
|-------------------------------|-----------------|------------------------------|
| Reproducible builds           | ✅ READY         | None - Jib provides this     |
| Staging validation            | ⚠️ PARTIAL      | Add explicit staging step    |
| Trusted hardware verification | ❌ MISSING       | Add manual PMC approval gate |
| ASF signing key               | ❌ NOT REQUESTED | File INFRA JIRA ticket       |
| ATR onboarding                | ❌ BLOCKED       | Wait for signing to be ready |
| ATR testing                   | ❌ BLOCKED       | Wait for onboarding          |

## Testing Approaches

### 1. Dry Run Testing (Recommended First Step)

**Purpose**: Validate workflow logic without touching ATR platform.

**How to run:**

1. Go to **Actions** tab in GitHub
2. Select **ATR Release Process (TEST)** workflow
3. Click **Run workflow**
4. Fill in:
    - `release_version`: `0.0.1-test`
    - `release_candidate`: `rc1`
    - `dry_run`: ✅ **true** (default)
5. Click **Run workflow**

**What it tests:**

- ✅ Builds the project successfully
- ✅ Creates source tarball with checksums
- ✅ Creates JAR distribution with checksums
- ✅ Simulates GPG signing
- ✅ Shows what would be uploaded to ATR
- ✅ Generates vote email template
- ✅ Validates workflow logic without side effects

**Expected result**: Green checkmark, no errors, artifacts available for download.

---

### 2. ATR Connectivity Testing

**Purpose**: Verify you can reach the ATR platform.

**Included in**: The `validate-atr` job runs automatically in every test workflow execution.

**What it tests:**

- ✅ ATR platform is reachable (https://release-test.apache.org)
- ✅ Health endpoint responds
- ✅ SSH port 2222 is accessible
- ✅ GitHub OIDC is available

**Check results**: Look at the workflow summary for connectivity status.

---

### 3. Pre-Onboarding Local Testing

**Purpose**: Test artifact creation locally before any GitHub Actions.

```bash
# 1. Create a test tag
git tag v0.0.1-test-rc1 -m "Test release"

# 2. Build the project
./gradlew clean build test

# 3. Create distribution artifacts
mkdir -p build/distributions

# Create source tarball
tar czf build/distributions/solr-mcp-0.0.1-test-rc1-src.tar.gz \
  --exclude='.git' \
  --exclude='build' \
  --exclude='.gradle' \
  --exclude='*.iml' \
  --exclude='.idea' \
  .

# Generate checksums
cd build/distributions
sha512sum solr-mcp-0.0.1-test-rc1-src.tar.gz > solr-mcp-0.0.1-test-rc1-src.tar.gz.sha512
sha256sum solr-mcp-0.0.1-test-rc1-src.tar.gz > solr-mcp-0.0.1-test-rc1-src.tar.gz.sha256

# 4. Verify artifacts
ls -lh build/distributions/
sha512sum -c solr-mcp-0.0.1-test-rc1-src.tar.gz.sha512
```

**Expected result**: All artifacts created successfully, checksums verify.

---

### 4. GitHub Actions Workflow Validation

**Purpose**: Test GitHub Actions syntax and job dependencies.

```bash
# Install act (https://github.com/nektos/act) to run workflows locally
brew install act  # macOS
# or: curl https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash

# Run workflow locally (requires Docker)
act workflow_dispatch \
  -W .github/workflows/atr-release-test.yml \
  -j compose-release \
  --input release_version=0.0.1-test \
  --input release_candidate=rc1 \
  --input dry_run=true
```

**Note**: `act` may not perfectly replicate GitHub's environment, but catches basic errors.

---

### 5. Real ATR Testing (After Onboarding)

**Prerequisites:**

- ✅ Project onboarded to ATR (email dev@tooling.apache.org)
- ✅ `ASF_USERNAME` secret configured in GitHub
- ✅ Your ASF account has access to ATR platform

**How to run:**

1. **Test artifact upload:**
   ```
   Run workflow: ATR Release Process (TEST)
   - release_version: 0.0.1-test
   - release_candidate: rc1
   - dry_run: false  ⚠️ Set to FALSE
   - skip_vote: false
   ```

2. **Verify upload on ATR:**
    - Visit: https://release-test.apache.org/projects/solr-mcp/
    - Look for your test artifacts
    - Verify checksums match

3. **Test vote resolution (skip upload):**
   ```
   Run workflow: ATR Release Process (TEST)
   - release_version: 0.0.1-test
   - release_candidate: rc1
   - dry_run: false
   - skip_compose: true  ⚠️ Skip artifact creation
   - skip_vote: true     ⚠️ Go straight to finish
   ```

4. **Check ATR platform:**
    - Verify vote was marked as resolved
    - Check that no announcement email was sent (we set announce=false)

---

## Debugging Common Issues

### Issue: `asf-uid` not found

**Cause**: `ASF_USERNAME` secret not configured or empty.

**Fix:**

1. Go to repository **Settings** → **Secrets and variables** → **Actions**
2. Add new secret: `ASF_USERNAME` = your ASF ID
3. Re-run workflow

---

### Issue: SSH connection refused (port 2222)

**Cause**: Either ATR platform is down, or project not onboarded.

**Fix:**

1. Check ATR status: https://release-test.apache.org
2. Verify project onboarding with dev@tooling.apache.org
3. Check `validate-atr` job output for connectivity details

---

### Issue: OIDC token validation failed

**Cause**: Repository settings don't allow OIDC token creation.

**Fix:**

1. Ensure workflow has `id-token: write` permission (already configured)
2. Check if organization settings restrict OIDC
3. Verify GitHub Actions is enabled for the repository

---

### Issue: Action not found (apache/tooling-actions)

**Cause**: Actions repository not accessible or branch reference incorrect.

**Fix:**

1. Verify https://github.com/apache/tooling-actions exists and is public
2. Check if you're using `@main` (ATR docs say not to tag versions)
3. Try pinning to specific commit SHA if needed

---

## Testing Checklist

Before attempting a real release with ATR:

- [ ] Dry run test passes locally
- [ ] GitHub Actions dry run completes successfully
- [ ] All artifacts are created correctly
- [ ] Checksums verify
- [ ] ATR connectivity validation passes
- [ ] Vote email template looks correct
- [ ] (After onboarding) Test upload succeeds
- [ ] (After onboarding) Can view artifacts on ATR platform
- [ ] (After onboarding) Vote resolution works
- [ ] Real `release-publish.yml` workflow still works for Docker/MCP

---

## Monitoring Test Results

### GitHub Actions UI

1. Go to **Actions** tab
2. Click on workflow run
3. Check each job's status and logs
4. Download artifacts from **Summary** page

### Job Summaries

Each job writes a summary visible at the top of the job output:

- **Compose**: Lists generated artifacts and sizes
- **Vote**: Shows email template
- **Finish**: Displays announcement preview
- **Validate**: Shows connectivity test results

### Artifacts

Download test artifacts to verify locally:

```bash
# After workflow completes
gh run list --workflow=atr-release-test.yml
gh run view <RUN_ID>
gh run download <RUN_ID>

# Verify downloaded artifacts
cd test-release-artifacts-0.0.1-test-rc1/
sha512sum -c *.sha512
```

---

## Comparison: Test vs Real Workflow

| Feature                | Test Workflow          | Real Workflow               |
|------------------------|------------------------|-----------------------------|
| **File**               | `atr-release-test.yml` | `atr-release.yml`           |
| **Default Mode**       | Dry run                | Live                        |
| **Creates Tags**       | Yes (automatically)    | No (must exist)             |
| **Uploads to ATR**     | Optional               | Always (if secrets present) |
| **Sends Emails**       | Never                  | Yes (after vote)            |
| **Docker Publish**     | No                     | Yes (after vote)            |
| **MCP Registry**       | No                     | Yes (after vote)            |
| **Artifact Retention** | 7 days                 | 30 days                     |
| **Use Case**           | Testing & validation   | Production releases         |

---

## Implementation Steps for Automated Signing

### Step 1: Add Trusted Hardware Verification

Update `.github/workflows/release-publish.yml` to add a manual verification gate:

```yaml
jobs:
    # NEW: Add this job before publish-docker
    manual-verification:
        name: Manual Build Verification Required
        runs-on: ubuntu-latest
        needs: validate-release

        steps:
            -   name: Request PMC verification
                uses: trstringer/manual-approval@v1
                with:
                    approvers: apache-pmc-members  # Replace with actual PMC GitHub team
                    minimum-approvals: 1
                    issue-title: "Verify reproducible build for ${{ inputs.release_version }}"
                    issue-body: |
                        ## PMC Verification Required

                        Before proceeding with release ${{ inputs.release_version }}, a PMC member must:

                        ### Verification Steps:
                        1. Check out tag: `v${{ inputs.release_version }}-${{ inputs.release_candidate }}`
                        2. Run build: `./gradlew clean build`
                        3. Compare checksums with CI artifacts:
                           - Download CI artifacts from this workflow run
                           - Compare local vs CI checksums: `sha512sum -c *.sha512`
                        4. If checksums match, approve this issue

                        ### Checksums to verify:
                        - Source tarball SHA512
                        - JAR SHA512

                        Approve this issue to continue the release.

    publish-docker:
        needs: manual-verification  # Add this dependency
        # ... rest of job
```

### Step 2: Request ASF Signing Key

File a JIRA ticket with ASF INFRA:

```
Project: INFRA
Summary: Request automated signing key for Apache Solr MCP
Description:

Apache Solr MCP project requests a signing key for automated release signing.

Project: Apache Solr MCP
Repository: https://github.com/apache/solr-mcp
PMC: Apache Solr PMC

We meet the requirements:
1. Reproducible builds: Yes (using Jib)
2. Staging validation: Yes (GitHub Actions with artifact uploads)
3. Trusted hardware verification: Yes (manual PMC approval gate)

Please provision:
- 4096-bit RSA signing key
- Encrypted revocation certificate in private repo
- Add public key to project KEYS file

Contact: [Your ASF email]
```

### Step 3: Integrate Signing Key (After INFRA Provisions)

INFRA will provide instructions, but typically:

```yaml
# In release-publish.yml, after build step:
-   name: Sign artifacts with ASF infrastructure
    env:
        ASF_SIGNING_KEY: ${{ secrets.ASF_SIGNING_KEY }}  # Provided by INFRA
    run: |
        # Import signing key (INFRA will provide exact commands)
        echo "$ASF_SIGNING_KEY" | gpg --import

        # Sign all artifacts
        cd build/distributions
        for file in *.tar.gz *.jar; do
          gpg --armor --detach-sign "$file"
        done

        # Verify signatures
        for file in *.asc; do
          gpg --verify "$file"
        done
```

## ATR Onboarding Process

Once automated signing is working:

### Subscribe to ATR Mailing Lists

```bash
# Development discussions
echo "subscribe" | mail dev-subscribe@tooling.apache.org

# User support
echo "subscribe" | mail users-subscribe@tooling.apache.org
```

### Request ATR Onboarding

Send email to `dev@tooling.apache.org`:

```
Subject: Request ATR onboarding for Apache Solr MCP

Hello ATR team,

Apache Solr MCP would like to join the ATR Alpha program.

Project Details:
- Name: Apache Solr MCP
- Repository: https://github.com/apache/solr-mcp
- PMC: Apache Solr
- Release Manager: [Your name/ASF ID]

Automated Signing Status:
- Reproducible builds: ✅ Implemented (Jib)
- Staging validation: ✅ Implemented (GitHub Actions)
- Trusted hardware verification: ✅ Implemented (manual PMC gate)
- ASF signing key: ✅ Provisioned by INFRA (ticket: INFRA-XXXXX)

We are ready to test ATR automation.

Thanks,
[Your name]
```

### Configure GitHub Secrets

After onboarding approval, add:

```
Repository Settings → Secrets → Actions:

ASF_USERNAME=your-asf-id
```

## Implementation Timeline

### Phase 1: Automated Signing Setup (2-4 weeks)

- Week 1: Add trusted hardware verification gate
- Week 2: File INFRA JIRA ticket for signing key
- Week 3-4: Wait for INFRA to provision key
- Week 4: Integrate signing key, test

### Phase 2: ATR Onboarding (1-2 weeks)

- Request ATR onboarding
- Wait for approval
- Configure secrets

### Phase 3: ATR Testing (1 week)

- Dry run tests
- Upload tests
- Complete flow test

### Phase 4: Production (Ongoing)

- Use for real releases
- Iterate based on experience

## Why This Order?

ATR is designed for fully automated releases. Without automated signing:

- You'd still need manual GPG signing steps
- Can't test the full automation
- Defeats the purpose of ATR

By implementing signing first:

- ✅ Complete automation is possible
- ✅ ATR can validate signatures automatically
- ✅ Reduces manual Release Manager burden
- ✅ Meets ASF compliance requirements

## Testing Without ATR (Current Approach)

Until automated signing + ATR are ready, continue using:

```
.github/workflows/release-publish.yml
```

This workflow:

- ✅ Works today
- ✅ Publishes Docker images
- ✅ Updates MCP Registry
- ⚠️ Requires manual signing by Release Manager
- ⚠️ Requires manual vote management

## Next Steps

### Immediate (This Week)

1. **Test workflow logic** with dry runs (`atr-release-test.yml`)
2. **Validate locally**: Ensure your build produces correct artifacts
3. **Review prerequisites**: Understand what's needed for automated signing

### Short Term (1-2 Months)

1. **Implement trusted hardware verification** gate
2. **File INFRA JIRA ticket** for signing key
3. **Continue using manual process** for real releases

### Long Term (3-6 Months)

1. **Integrate automated signing** after INFRA provisions key
2. **Request ATR onboarding** after signing is working
3. **Test with real ATR** after onboarding approval
4. **Use in production** for future releases

---

## Resources

- **ATR Platform**: https://release-test.apache.org
- **ATR Tutorial**: https://release-test.apache.org/tutorial
- **ATR API Docs**: https://release-test.apache.org/api/docs
- **GitHub Actions**: https://github.com/apache/tooling-actions
- **ATR Source**: https://github.com/apache/tooling-trusted-releases
- **Support**: dev@tooling.apache.org

---

## Questions?

If you encounter issues not covered here:

1. Check workflow logs in GitHub Actions
2. Review ATR platform documentation
3. Search GitHub issues: https://github.com/apache/tooling-trusted-releases/issues
4. Ask on mailing list: dev@tooling.apache.org
5. Update this guide with solutions you discover!