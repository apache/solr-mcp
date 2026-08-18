/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.build

/**
 * Translates `.gitignore` lines into Apache RAT (Ant-style) exclude globs.
 *
 * RAT scans every file under the project directory; we reuse `.gitignore` as the single
 * source of truth for build output, IDE folders, and other untracked cruft so those
 * patterns are not duplicated in the build script. The RAT plugin's own `excludeFile`
 * does **not** interpret `.gitignore` path semantics, so we translate each entry here.
 *
 * This is pure, Gradle-free logic precisely so it can be unit-tested
 * ([RatExcludesTest]); the gitignore-to-Ant-glob mapping has enough edge cases
 * (anchoring, directory markers, negation) to be worth pinning down with tests.
 *
 * ### Mapping rules (a practical subset of gitignore semantics)
 *
 * Throughout, "globstar" means the two-asterisk Ant wildcard that matches any number of
 * path segments (written here without the trailing slash to keep this comment valid).
 *
 * - Blank lines and `#` comments are skipped.
 * - **Negation (`!foo`) entries are skipped.** Git uses them to *re-include* a path, but
 *   RAT excludes have no re-inclusion mechanism. Skipping (rather than excluding) is the
 *   safe choice: at worst RAT still scans a file git would ignore. The opposite — a
 *   bare `build/` excluding a re-included `!build/keep.txt` — over-excludes that one file
 *   from header checking, which we accept as a rare, low-risk gap.
 * - A **trailing slash** (directory marker) is stripped; the entry is still emitted in
 *   both bare and directory-contents (globstar-suffixed) forms.
 * - **Anchoring** follows git: an entry with a leading slash, or with an interior slash
 *   (a separator that is not just the trailing one), is anchored to the repo root and is
 *   emitted as-is (leading slash removed). An entry with no separator — or only a
 *   trailing slash — matches at any depth and is prefixed with a leading globstar segment.
 *   An entry that already begins with a globstar segment is left untouched. (The original
 *   inline implementation prefixed *every* non-leading-slash entry with a globstar, which
 *   wrongly turned a root-anchored `foo/bar` into an any-depth match; this distinguishes
 *   the two.)
 *
 * Each surviving entry yields two globs — the path itself and its directory-contents form
 * — so an ignored directory and everything under it are both pruned.
 */
object RatExcludes {

    fun fromGitignore(lines: List<String>): List<String> =
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") }
            .filterNot { it.startsWith("!") }
            .map { it.trimEnd('/') }
            .filter { it.isNotEmpty() }
            .map(::toGlob)
            .flatMap { sequenceOf(it, "$it/**") }
            .distinct()
            .toList()

    /** Maps one normalized (trimmed, no trailing slash) gitignore entry to one Ant glob. */
    private fun toGlob(entry: String): String {
        val anchored = entry.startsWith("/")
        val body = entry.removePrefix("/")
        return when {
            body.startsWith("**/") -> body // already any-depth
            anchored || body.contains("/") -> body // root-anchored (leading or interior slash)
            else -> "**/$body" // no separator → match at any depth
        }
    }
}
