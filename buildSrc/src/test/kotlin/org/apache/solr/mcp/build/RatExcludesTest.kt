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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RatExcludesTest {

    @Test
    fun `each entry yields both the path and its directory-contents glob`() {
        assertEquals(listOf("**/build", "**/build/**"), RatExcludes.fromGitignore(listOf("build/")))
    }

    @Test
    fun `a no-separator entry matches at any depth`() {
        // ".gradle" can appear at any level, so it must be prefixed with **/.
        assertTrue(RatExcludes.fromGitignore(listOf(".gradle")).contains("**/.gradle"))
        assertTrue(RatExcludes.fromGitignore(listOf("*.iml")).contains("**/*.iml"))
    }

    @Test
    fun `a leading-slash entry is anchored to the repo root`() {
        // "/build" means the root build dir only — no **/ prefix, leading slash stripped.
        assertEquals(listOf("build", "build/**"), RatExcludes.fromGitignore(listOf("/build")))
    }

    @Test
    fun `an interior-slash entry is root-anchored, not any-depth`() {
        // git anchors "src/generated" to the root; it must NOT become **/src/generated.
        val globs = RatExcludes.fromGitignore(listOf("src/generated"))
        assertEquals(listOf("src/generated", "src/generated/**"), globs)
    }

    @Test
    fun `an entry already starting with double-star is left untouched`() {
        assertEquals(listOf("**/*.log", "**/*.log/**"), RatExcludes.fromGitignore(listOf("**/*.log")))
    }

    @Test
    fun `blank lines, comments, and negations are skipped`() {
        val globs = RatExcludes.fromGitignore(listOf("", "   ", "# a comment", "!keep.txt"))
        assertTrue(globs.isEmpty(), "expected no globs but got $globs")
    }

    @Test
    fun `duplicate entries are collapsed`() {
        assertEquals(listOf("**/target", "**/target/**"), RatExcludes.fromGitignore(listOf("target", "target/")))
    }
}
