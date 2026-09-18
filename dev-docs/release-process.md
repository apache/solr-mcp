# Releasing Apache Solr MCP

This guide is for a Solr committer acting as release manager (RM). The source
archive is the official Apache release. The Spring Boot JAR is a convenience
binary. For 1.0.0, stage the source archive and all four Gradle-produced JARs,
each with an OpenPGP signature and SHA-512 checksum.
Replace the example version and candidate number throughout.

## 1. Prepare

1. Confirm that your signing key is available to `gpg`, is associated with
   Solr in Apache Trusted Releases (ATR), and appears in the published
   [Solr KEYS](https://downloads.apache.org/solr/KEYS). Confirm access to the
   production [ATR](https://releases.apache.org/) Solr MCP project. If it is
   unavailable, agree on staging with the PMC and ASF Tooling before voting.
   `release-test.apache.org` is for testing, not the production release.   **ERIC: IS THIS TRUE?  APPARENTLY MY KEYS ARE NOT IN SOLR/KEYS SO HOW COULD I HAVE STARTED THE RELEASE PROCESS**
2. Create or update the release branch as described below.

### Create or update the release branch

Start from a clean working tree. Fetch the latest remote refs, then use **one**
of these paths for `branch_1_0_0`:

```bash
git status --short                       # expected: no output
git fetch origin

# First release candidate from a new branch:
git switch -c branch_1_0_0 origin/main

# If origin/branch_1_0_0 already exists, use these instead:
# git switch --track origin/branch_1_0_0
# git merge origin/main
```

If the branch already exists locally, run `git switch branch_1_0_0` instead
of `git switch --track`, then `git pull --ff-only origin branch_1_0_0` and
`git merge origin/main`. Resolve any merge conflicts and finish the merge
before continuing. Review the changes brought in from `main`; include only
changes intended for this release.

Set the exact release version in `build.gradle.kts`.

**ERIC: how are we handling release notes?**
Prepare release notes in
`dev-docs/release-notes-1.0.0.md` describing the changes users should know
about. Then, to fix version labels in the soruce, on Linux, run these commands from the repository root, then review and
publish the branch:

```bash
sed -i 's/version = "1\.0\.0-SNAPSHOT"/version = "1.0.0"/' build.gradle.kts
rg -l -0 '1\.0\.0-SNAPSHOT' README.md dev-docs -g '*.md' -g '!release-process.md' \
  | xargs -0 -r sed -i 's/1\.0\.0-SNAPSHOT/1.0.0/g'
git diff --check
git diff -- build.gradle.kts README.md dev-docs
cat dev-docs/release-notes-1.0.0.md
git add build.gradle.kts README.md dev-docs
git commit -m "chore(release): prepare 1.0.0"
git status --short                # expected: no output
git push -u origin branch_1_0_0
```

Confirm `build.gradle.kts` contains `version = "1.0.0"` and the release notes
are committed before creating the candidate tag below.

## 2. Build an immutable candidate

From a clean release-branch checkout, create and push an annotated candidate
tag before making the source archive. Never move it after voting begins.

```bash
git status --short                         # expected: no output
git tag -a releases/solr-mcp/1.0.0-rc1 -m "Apache Solr MCP 1.0.0 RC1"
git push origin releases/solr-mcp/1.0.0-rc1
git checkout releases/solr-mcp/1.0.0-rc1
./gradlew clean build spotlessCheck
```

The build must be green. It runs RAT and generates the binary LICENSE and
NOTICE. Spot-check the executable boot JAR:

```bash
unzip -p build/libs/solr-mcp-1.0.0.jar META-INF/LICENSE | head
unzip -p build/libs/solr-mcp-1.0.0.jar META-INF/NOTICE | head
```

Stage the source archive and all four JARs: the executable boot JAR, the
`-plain` JAR, the `-sources` JAR, and the `-javadoc` JAR. The source archive is
the buildable source release; the `-sources` JAR does not replace it.

```bash
mkdir -p build/release
git archive --format=tar.gz --prefix=solr-mcp-1.0.0/ \
  releases/solr-mcp/1.0.0-rc1 -o build/release/solr-mcp-1.0.0-src.tgz
cp build/libs/solr-mcp-1.0.0*.jar build/release/
tar tzf build/release/solr-mcp-1.0.0-src.tgz | head
ls -lh build/release/
```

The archive should have a `solr-mcp-1.0.0/` top-level directory containing
the the uberjar, the -plain jar, source, LICENSE, and NOTICE. Verify it builds on its own:

```bash
mkdir -p build/release-check
tar xzf build/release/solr-mcp-1.0.0-src.tgz -C build/release-check
(cd build/release-check/solr-mcp-1.0.0 && ./gradlew build)
```

Run a manual MCP client check against the built JAR with a live Solr instance,
and record the client and Solr version in the vote notes.

## 3. Sign and stage in ATR

Sign every staged artifact with your ASF OpenPGP key. Substitute your own key
ID:

```bash
cd build/release
for artifact in solr-mcp-1.0.0-src.tgz solr-mcp-1.0.0*.jar; do
  gpg --armor --detach-sign --local-user YOUR_KEY_ID --output "$artifact.asc" "$artifact"
  shasum -a 512 "$artifact" > "$artifact.sha512"
  gpg --verify "$artifact.asc" "$artifact"
  shasum -a 512 -c "$artifact.sha512"
done
```

Expect `Good signature` and `OK` for each artifact. In production ATR,
create candidate `1.0.0-rc1`, upload all 15 files from `build/release/`,
and resolve any candidate check failures. ATR stages fixed candidate bytes and
runs mechanical checks; it does not replace the RM's licensing, provenance,
build, and functional review or the PMC vote. Copy the ATR candidate URL for
the vote. Do not give voters a second artifact copy.

See [ATR staging and voting](https://releases.apache.org/docs/staging-and-voting)
for upload methods and candidate checks.

## 4. Vote on dev@solr.apache.org

Use ATR's vote template or the following outline. State a closing time at
least 72 hours away, with timezone:

**ERIC: WE MAY NOT NEED THE TEXT HERE, DEFAULT ATR TEXT IS FINE**

**ERIC: ACTUALLY WE COULD DO SMOKETEST BY FIRING UP A SOLR AND HTNE USE https://adityamparikh.github.io/solr-mcp/**

```
https://adityamparikh.github.io/solr-mcp?download=httpds://dist.apache.rog/releases/solr-mcp/1.0.0-rc1/solr-mcp-1.0.0-rc1.jar
```

```text
Subject: [VOTE] Release Apache Solr MCP 1.0.0 RC1

I propose releasing Apache Solr MCP 1.0.0 from RC1.
Candidate: <ATR candidate URL>
Source tag: releases/solr-mcp/1.0.0-rc1 (<commit SHA>)
Signing keys: https://downloads.apache.org/solr/KEYS
Artifacts: solr-mcp-1.0.0-src.tgz and the boot, -plain, -sources,
           and -javadoc JARs; each has .asc and .sha512 files.
Changes: <release notes URL>
Verification performed: <build, test, and manual checks>

Please vote +1 or -1 by <date, time, and timezone>.
```

Voters should download the signed source, verify its signature and checksum,
build it, and test it. A passing vote requires at least three binding +1 votes
from Solr PMC members and more binding +1 than binding -1 votes. A -1 is not
a veto. After the normal 72-hour period, reply with a `[RESULT]` message
listing binding and nonbinding totals and link to the vote thread. If the vote
fails or the bytes need changing, cancel the candidate in ATR and make a new
RC. Never replace voted files.

## 5. Publish and announce

After a passing vote, use ATR's Finish / Publish action on the approved
candidate. Check its proposed destination before publishing; for this Solr
PMC project, use the versioned `solr/mcp/1.0.0/` path so files appear at
`https://downloads.apache.org/solr/mcp/1.0.0/`. Adjust ATR's download path
suffix if needed. ATR commits approved files to the ASF distribution SVN, so
there is no separate `svn mv` when using its publication action. Do not
rebuild or replace the voted artifacts.

Verify that all five artifacts, their signatures, and checksums are reachable
from that URL. Wait at least one hour for distribution caches before updating
the download page or announcing. **ERIC: DO WE NEED MORE DETAILS ON ALL THIS???** Update the Solr MCP website download page
with an ASF source download link and HTTPS links to the matching `.asc`,
`.sha512`, and KEYS files; verify those links. As in the
[Solr Operator release process](https://github.com/apache/solr-operator/blob/main/hack/release/wizard/releaseWizard.yaml),
create the final release tag after the vote at the **same commit** as the voted
RC tag, then use that tag for the GitHub Release:

```bash
git tag -a releases/solr-mcp/1.0.0 'releases/solr-mcp/1.0.0-rc1^{}' \
  -m "Apache Solr MCP 1.0.0"
git push origin releases/solr-mcp/1.0.0
```

Publish a GitHub Release from the final tag with notes linking to ASF
downloads.

Announce on the appropriate Solr and ASF announcement lists, with a short
project description and links to the updated download page and release notes.
**ERIC FIND THIS OUT** ATR may offer an announcement action; check whether it sent the email before
sending one manually. Finally, bump `main` to the next `-SNAPSHOT` version.

## References

- [ASF release policy](https://www.apache.org/legal/release-policy.html)
- [ASF release distribution policy](https://infra.apache.org/release-distribution)
- [ATR user guide](https://releases.apache.org/docs/user-guide) and [promotion guide](https://releases.apache.org/docs/promoting-to-release)
