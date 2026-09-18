# Changelog Guide

`CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com) and is managed by the
`org.jetbrains.changelog` Gradle plugin (`build.gradle.kts`). It has one job: give every release
a real, human-readable set of release notes without anyone reverse-engineering them from commit
history at the last minute.

## Day to day: add an entry with your PR

If your change is user-visible — a new tool, a changed response shape, a fixed bug, a closed
security gap — add one line to the `[Unreleased]` section of `CHANGELOG.md` in the same PR,
under the group that fits:

```markdown
## [Unreleased]

### Added

- **index-json-documents** now accepts a typed array of documents in one call.

### Changed

### Fixed

- Configuring `solr.url` to an address SolrJ cannot reach now fails fast at startup.

### Security
```

Skip the entry for internal-only changes: refactors, test additions, CI/build tweaks, docs,
dependency bumps with no observable effect. If a group ends up with no entries at release time,
it's simply omitted from the extracted notes (see `getChangelog` below) — leave empty headers
in place rather than deleting them.

Write the entry as the *effect*, not the commit — no commit hashes, PR numbers, or scope
prefixes; that's what `git log` and `gh pr view` are for.

## At release time

1. **Review `[Unreleased]`** for completeness against everything that actually shipped since the
   last release (`git log <last-tag>..HEAD --no-merges` is the check, not the source of the
   entries — entries should already be there from step "day to day" above).
2. **Cut the version.** The plugin's `changelog { version = project.version }` default will
   pick up whatever `version` is set to in `build.gradle.kts` — but during development that's a
   `-SNAPSHOT` value, which is *not* what you want in the changelog heading. Override it
   explicitly for the release:
   ```bash
   ./gradlew patchChangelog -Pversion=1.0.0
   ```
   This renames `[Unreleased]` to `## [1.0.0] - <today>` and opens a fresh, empty
   `[Unreleased]` section above it.
3. **Extract the release notes** for reuse as the GitHub Release body / release announcement:
   ```bash
   ./gradlew getChangelog --console=plain -q --no-header --project-version=1.0.0
   ```
4. **Commit `CHANGELOG.md`** as part of the release branch (see `dev-docs/release-process.md`
   for where this fits relative to building and signing artifacts — do it before signing, since
   it's source, not a build output).

Useful read-only commands while iterating:

```bash
# Preview what would ship, without mutating the file
./gradlew getChangelog --console=plain -q --unreleased --no-summary --no-empty-sections

# Scaffold a fresh CHANGELOG.md from nothing (already done for this repo; here for reference)
./gradlew initializeChangelog
```

## Handling the first release (1.0.0)

This is the first release this project has ever cut, so `[Unreleased]` was seeded empty rather
than back-filled — entries only exist from the point this guide was adopted onward. Whoever
prepares the 1.0.0 release notes needs a one-time, deliberate pass over the *entire* project
history before running `patchChangelog`, not just the recent PRs:

1. Get the full commit range: `git log --no-merges --format='%x1e%H%x1f%s%x1f%b' HEAD` from the
   project's first commit (there are no prior tags to diff against).
2. Categorize into `Added` / `Changed` / `Fixed` / `Security`, collapsing multiple commits that
   implement one user-facing capability into a single entry (e.g. the several PRs behind
   markdown indexing become one `Added` line, not five). Drop internal-only commits.
3. Write the result into `[Unreleased]` by hand, then follow **At release time** above.

Doing this well is a real editing pass, not a mechanical `git log` dump — budget time for it
alongside the other 1.0.0 release-process work, and do it close to the actual release so the
notes reflect what's really shipping (see `dev-docs/release-process.md` and the tracking issues
for the open blockers on that release). Every release after 1.0.0 skips this step entirely,
since `[Unreleased]` will already be current.
