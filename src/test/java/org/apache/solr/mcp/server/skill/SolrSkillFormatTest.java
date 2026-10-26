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
package org.apache.solr.mcp.server.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the bundled Solr skill ({@code src/main/resources/skills/solr}) to the
 * Agent Skills format and to the limits SEP-2640 puts on a skill served over
 * MCP, so a malformed skill fails the build instead of a client.
 *
 * <p>
 * Rules checked, from the Agent Skills specification: the frontmatter holds
 * only the specified keys; {@code name} is 1-64 characters of lowercase
 * letters, digits and single hyphens and equals the directory name;
 * {@code description} is 1-1024 characters; {@code compatibility} is at most
 * 500; {@code metadata} maps strings to strings; {@code SKILL.md} stays under
 * 500 lines and roughly 5000 tokens; references sit directly under
 * {@code references/}, every one is named in {@code SKILL.md}, and every
 * relative Markdown link in the skill resolves to a file. The skill carries no
 * {@code scripts/} directory, because hosts must not run code from an MCP
 * server without explicit approval.
 */
class SolrSkillFormatTest {

	private static final Path SKILL_DIR = Path.of("src/main/resources/skills/solr");
	private static final Path SKILL_MD = SKILL_DIR.resolve("SKILL.md");
	private static final Path REFERENCES = SKILL_DIR.resolve("references");

	private static final Set<String> SPEC_KEYS = Set.of("name", "description", "license", "compatibility", "metadata",
			"allowed-tools");
	private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
	private static final Pattern RELATIVE_LINK = Pattern.compile("\\]\\((?!https?:|#|mailto:)([^)#\\s]+)");

	private static final int MAX_FILES = 512;
	private static final long MAX_BYTES = 16L * 1024 * 1024;
	private static final int APPROX_CHARS_PER_TOKEN = 4;
	private static final int MAX_BODY_TOKENS = 5000;

	@Test
	void frontmatterFollowsTheAgentSkillsSpecification() throws IOException {
		Map<String, Object> frontmatter = frontmatter(read(SKILL_MD));

		assertThat(frontmatter.keySet()).isSubsetOf(SPEC_KEYS);
		assertThat(frontmatter.get("name")).isInstanceOf(String.class);
		assertThat((String) frontmatter.get("name")).hasSizeBetween(1, 64).matches(NAME)
				.isEqualTo(SKILL_DIR.getFileName().toString());
		assertThat(frontmatter.get("description")).isInstanceOf(String.class);
		assertThat((String) frontmatter.get("description")).isNotBlank().hasSizeLessThanOrEqualTo(1024);

		if (frontmatter.containsKey("compatibility")) {
			assertThat((String) frontmatter.get("compatibility")).hasSizeBetween(1, 500);
		}
		if (frontmatter.containsKey("metadata")) {
			assertThat(frontmatter.get("metadata")).isInstanceOf(Map.class);
			Map<?, ?> metadata = (Map<?, ?>) frontmatter.get("metadata");
			assertThat(metadata.keySet()).allSatisfy(k -> assertThat(k).isInstanceOf(String.class));
			assertThat(metadata.values()).allSatisfy(v -> assertThat(v).isInstanceOf(String.class));
		}
	}

	@Test
	void skillMdStaysWithinTheLineAndTokenBudget() throws IOException {
		String skill = read(SKILL_MD);

		assertThat(skill.lines().count()).isLessThan(500);
		assertThat(skill.length() / APPROX_CHARS_PER_TOKEN).isLessThan(MAX_BODY_TOKENS);
	}

	@Test
	void everyReferenceIsNamedInSkillMd() throws IOException {
		String skill = read(SKILL_MD);

		try (Stream<Path> files = Files.list(REFERENCES)) {
			List<String> references = files.map(p -> "references/" + p.getFileName()).toList();
			assertThat(references).isNotEmpty();
			assertThat(references).allSatisfy(r -> assertThat(skill).contains(r));
		}
	}

	@Test
	void everyRelativeMarkdownLinkResolvesFromTheSkillRoot() throws IOException {
		try (Stream<Path> files = Files.walk(SKILL_DIR)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
				var matcher = RELATIVE_LINK.matcher(read(file));
				while (matcher.find()) {
					assertThat(SKILL_DIR.resolve(matcher.group(1))).as("link %s in %s", matcher.group(1), file)
							.isRegularFile();
				}
			}
		}
	}

	@Test
	void referencesSitDirectlyUnderReferencesAndNothingIsExecutable() throws IOException {
		assertThat(SKILL_DIR.resolve("scripts")).doesNotExist();
		try (Stream<Path> files = Files.walk(REFERENCES)) {
			assertThat(files.filter(p -> !p.equals(REFERENCES))).allSatisfy(p -> {
				assertThat(p).isRegularFile();
				assertThat(p.getParent()).isEqualTo(REFERENCES);
			});
		}
	}

	@Test
	void skillFitsTheSep2640Limits() throws IOException {
		try (Stream<Path> files = Files.walk(SKILL_DIR)) {
			List<Path> all = files.filter(Files::isRegularFile).toList();
			long bytes = 0;
			for (Path p : all) {
				bytes += Files.size(p);
			}
			assertThat(all).hasSizeLessThanOrEqualTo(MAX_FILES);
			assertThat(bytes).isLessThanOrEqualTo(MAX_BYTES);
		}
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path).replace("\r\n", "\n");
	}

	private static Map<String, Object> frontmatter(String markdown) {
		assertThat(markdown).startsWith("---\n");
		int end = markdown.indexOf("\n---\n", 3);
		assertThat(end).as("closing frontmatter delimiter").isPositive();
		Object parsed = new Yaml().load(markdown.substring(4, end));
		assertThat(parsed).isInstanceOf(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> map = (Map<String, Object>) parsed;
		return map;
	}
}
