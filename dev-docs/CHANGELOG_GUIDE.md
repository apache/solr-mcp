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

## Release highlights (the summary)

Plain paragraphs placed between a version heading and its first `###` group are that version's
**summary**. Use it for the two or three sentences a reader needs before the item list: what the
release is about and its headline changes. `patchChangelog` carries the summary into the released
section, and `getChangelog` prints it at the top of the extracted notes.

```markdown
## [Unreleased]

Solr MCP 1.1 adds semantic search and ...

### Added
```

Keep the summary to prose. Bullets above the first `###` are not part of the summary (the plugin
treats them as items with no group, and `--no-summary` leaves them in), and a `### Highlights`
heading would be parsed as one more group. Detail belongs in the groups below; the summary
should not repeat them. Releases with nothing to headline can omit it.

## At release time

1. **Review `[Unreleased]`** for completeness against everything that actually shipped since the
   last release (`git log <last-tag>..HEAD --no-merges` is the check, not the source of the
   entries — entries should already be there from step "day to day" above), and write or update
   the summary.
2. **Cut the version** in the release-preparation commit
   ([release process](release-process.md), step 1), after `version` in `build.gradle.kts` has
   been changed from the `-SNAPSHOT` value to the release version:
   ```bash
   ./gradlew patchChangelog --console=plain -q
   ```
   This renames `[Unreleased]` to `## [<version>] - <today>`, keeps the summary, drops groups that
   have no entries, adds the compare/commits links for the `releases/solr-mcp/<version>` tag (the
   plugin's `versionPrefix`), and opens a fresh, empty `[Unreleased]` section above it.
   The section is named after `project.version`; passing `-Pversion=...` instead of editing
   `build.gradle.kts` has no effect, because the build script assigns `version` itself.
3. **Commit `CHANGELOG.md`** with the version change, before tagging the release candidate: it
   is source, so it must be in the voted source archive.
4. **Extract the release notes** for the vote email, the GitHub Release body and the
   announcement:
   ```bash
   ./gradlew getChangelog --console=plain -q --no-header --project-version=1.0.0
   ```
   The output starts with the summary and lists only the groups that have entries.

Useful read-only commands while iterating:

```bash
# Preview what would ship, summary included, without mutating the file
./gradlew getChangelog --console=plain -q --unreleased --no-empty-sections

# Scaffold a fresh CHANGELOG.md from nothing (already done for this repo; here for reference)
./gradlew initializeChangelog
```

## The first release (1.0.0)

1.0.0 has no earlier release to diff against, so its `[Unreleased]` section was written by hand
from the project's whole history rather than accumulated PR by PR: a summary plus one `Added`
entry per user-facing capability (several PRs behind one capability, such as Markdown indexing,
are one entry). `Changed`, `Fixed` and `Security` are empty because they describe differences
from a previous release; `patchChangelog` drops them from the 1.0.0 section.

Before cutting 1.0.0, check the section against what is actually shipping — the tool list in the
source, the published images and the documentation — and add any capability merged after it was
written. From 1.0.1 on, `[Unreleased]` is kept current by the PRs themselves.
