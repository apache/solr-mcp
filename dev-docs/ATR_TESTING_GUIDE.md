# ATR: Current Process vs. Future Automation

This project uses Apache Trusted Releases (ATR) for release staging and
publishing, but there are **two distinct things** in this repo named "ATR"
that are easy to conflate:

1. **The real release process today** — manual, driven from the ATR web UI
   at `release-test.apache.org`. This is what a release manager (RM)
   actually runs.
2. **A future GitHub Actions automation** — `atr-release.yml` /
   `atr-release-test.yml` — which would drive ATR via its API/OIDC instead
   of the web UI. It is **not usable yet**: it is blocked on automated
   release signing and ATR onboarding, and nothing in this repo has
   exercised it against a real release.

Earlier versions of this guide presented the workflow files as a
near-term testing checklist ("Dry Run Testing (Recommended First Step)",
"Real ATR Testing (After Onboarding)") in a way that read as the current
path to a release. That was misleading — no release has gone through
`atr-release.yml`, and it cannot until the prerequisites below are met.
This revision separates the two so neither is overstated.

## Part A — The real process today (manual)

The authoritative, current, step-by-step release process is documented in
[**PR #157, "Release Process"**](https://github.com/apache/solr-mcp/pull/157)
(branch `add_release_steps` on Eric Pugh's fork). That PR adds
`dev-docs/release-process.md`, which **does not exist on `main` yet** — the
PR is open and under review, not merged. Read the PR diff directly
(`gh pr diff 157 --repo apache/solr-mcp`) for the current text rather than
relying on a summary here, since it has been revised multiple times.

**Status of PR #157, as of this writing:**

- It covers, in order: preparing the release branch and version bump,
  building an immutable release candidate tag, generating the source
  tarball (`git archive`) alongside the four Gradle-produced JARs, signing
  and staging everything in ATR (`gpgsign.sh`, an external tool — not
  scripted in this repo — plus a scoped GPG-sign/checksum loop), voting on
  `dev@solr.apache.org` (72-hour minimum, with `[VOTE]`/`[RESULT]` email
  templates), and publishing + announcing (final artifacts land at
  `https://downloads.apache.org/solr/mcp/<version>/`; ATR's own Finish/
  Publish action performs the dev-to-release SVN move, so there is no
  separate manual `svn mv` step).
- It still carries **open review feedback**: Jan requested changes on
  2026-07-29, and that review predates the source-tarball and vote/publish
  sections being added, so it's worth a fresh look rather than assuming the
  requested changes are unaddressed.
- It still has **at least one open self-TODO from Eric**
  (`**ERIC FIND THIS OUT**`) about whether ATR's Finish/Publish action also
  sends the release announcement email, or whether that still has to be
  sent manually — unresolved, don't assume an answer either way.
- Separately from the ASF source release, `.github/workflows/release-publish.yml`
  in this repo handles the *post-vote* Docker image publish and MCP
  Registry update — that workflow is unrelated to ATR and already works;
  it isn't part of what PR #157 documents.

**Follow-up once PR #157 merges:** replace the link above with a direct
link to `dev-docs/release-process.md`, and delete this pointer paragraph.
Do not duplicate that file's content here — it is authored and owned by
that PR, not by this guide.

## Part B — Future: GitHub Actions ATR automation (blocked)

`.github/workflows/atr-release.yml` and `.github/workflows/atr-release-test.yml`
are a prototype of a fully automated release pipeline that would drive ATR
via API/OIDC instead of a human using the web UI. Both files carry a
`FUTURE USE ONLY — Blocked` banner. They are kept in the repo as a working
draft of the automation goal, not as something to run against a real
release.

Per ASF Tooling guidance:

> **"Once you have achieved automated release signing, then you could test
> ATR automation."**

### Required order of implementation

```
Step 1: Implement Automated Release Signing
   ↓
Step 2: Onboard to ATR
   ↓
Step 3: Test ATR Automation
   ↓
Step 4: Use in Production
```

Until Step 1 is done, Steps 2–4 cannot start — the sections below describe
what each step involves, not something ready to run this week.

### Prerequisites for automated release signing (per ASF INFRA)

| Requirement                   | Status          | Action needed                 |
|--------------------------------|-----------------|--------------------------------|
| Reproducible builds           | ✅ Ready         | None — Jib provides this      |
| Staging validation            | ⚠️ Partial      | Add an explicit staging step   |
| Trusted hardware verification | ❌ Missing       | Add a manual PMC approval gate |
| ASF signing key                | ❌ Not requested | File an INFRA JIRA ticket      |
| ATR onboarding                 | ❌ Blocked       | Wait for signing to be ready    |
| ATR automation testing         | ❌ Blocked       | Wait for onboarding             |

### What the two workflow files are for, once unblocked

- **`atr-release-test.yml`** — a dry-run harness for exercising the
  automation's *logic* (artifact creation, checksums, ATR connectivity,
  vote-email templating) without side effects. Safe to run today in the
  sense that its default mode performs no uploads, but it is testing
  workflow YAML, not a real release — do not treat a green run as release
  validation.
- **`atr-release.yml`** — the eventual production automation. Cannot be
  used for a real release until the prerequisites above are met; its
  in-file banner says so explicitly.

Once the prerequisites are met, testing this automation would look like:

1. **Dry-run testing** — run `atr-release-test.yml` via
   **Actions → ATR Release Process (TEST) → Run workflow** with
   `dry_run: true` (default). Confirms the build, tarball/checksum
   generation, simulated signing, and vote-email template render without
   touching ATR.
2. **ATR connectivity check** — the `validate-atr` job (runs automatically
   in every test workflow execution) confirms `release-test.apache.org` is
   reachable, its health endpoint responds, SSH port 2222 is open, and
   GitHub OIDC is available.
3. **Pre-onboarding local testing** — build a tarball and checksums by hand
   (`git archive` / `sha512sum`) to sanity-check artifact shape before any
   GitHub Actions run.
4. **`act`-based workflow validation** — run the workflow locally via
   [`act`](https://github.com/nektos/act) to catch YAML/job-dependency
   errors before pushing.
5. **Real ATR testing (after onboarding only)** — with `ASF_USERNAME`
   configured and the project onboarded, run with `dry_run: false` against
   `release-test.apache.org`, verify the upload and checksums on the ATR
   UI, and separately test vote resolution.

None of steps 1–5 above substitute for a real release; they validate the
automation's plumbing so that, once signing and onboarding are in place, a
first real ATR-automated release doesn't start from a completely
unvalidated workflow.

### Debugging the test/future workflows

- **`asf-uid` not found** — `ASF_USERNAME` secret is missing or empty; add
  it under repo **Settings → Secrets and variables → Actions**.
- **SSH connection refused (port 2222)** — either ATR is down or the
  project isn't onboarded yet; check
  [release-test.apache.org](https://release-test.apache.org) and confirm
  onboarding with `dev@tooling.apache.org`.
- **OIDC token validation failed** — confirm the workflow has
  `id-token: write` and that org settings allow OIDC.
- **`apache/tooling-actions` action not found** — confirm the repo is
  public and the ref (ATR docs say use `@main`, not a tag) resolves; pin to
  a reviewed commit SHA if needed.

### Implementation steps toward automated signing

1. **Add a trusted-hardware verification gate** to `release-publish.yml` —
   a manual-approval job requiring PMC sign-off before publish, comparing
   locally rebuilt checksums against CI's.
2. **Request an ASF signing key from INFRA** — file an INFRA JIRA ticket
   describing the project's reproducible-build and staging-validation
   status.
3. **Integrate the signing key** once INFRA provisions it, replacing manual
   `gpg --detach-sign` steps with CI-driven signing.
4. **Request ATR onboarding** from `dev@tooling.apache.org` once signing is
   working end to end, then configure the `ASF_USERNAME` secret.
5. **Test the automation** (Part B's testing approaches above) before ever
   pointing it at a real release.

This is a multi-week-to-multi-month effort (INFRA key provisioning alone is
typically 2–4 weeks); there is no committed timeline for landing it before
1.0.0, so Part A (manual, via PR #157) remains the release path for the
foreseeable future.

### Resources

- ATR platform (test/alpha): https://release-test.apache.org
- ATR tutorial: https://release-test.apache.org/tutorial
- ATR API docs: https://release-test.apache.org/api/docs
- `apache/tooling-actions`: https://github.com/apache/tooling-actions
- ATR source: https://github.com/apache/tooling-trusted-releases
- Support: dev@tooling.apache.org

If you hit an issue not covered here, check the workflow logs, search
[tooling-trusted-releases issues](https://github.com/apache/tooling-trusted-releases/issues),
ask on `dev@tooling.apache.org`, and update this guide with what you learn.
