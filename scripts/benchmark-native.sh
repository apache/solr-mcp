#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# benchmark-native.sh — compare the JVM and native Docker images on:
#   - image size on disk
#   - startup time (time from `docker run` to context ready)
#   - resident memory after startup
#
# Prerequisites:
#   - Docker daemon running
#   - GraalVM JDK 25 on PATH (for the JVM build's processAot)
#   - Works on any OS/arch (native build runs inside Paketo builder container)
#
# Usage:
#   ./scripts/benchmark-native.sh
#
# Output:
#   - Markdown table on stdout
#   - docs/specs/benchmark-results.md (overwritten)

set -euo pipefail

cd "$(dirname "$0")/.."

RUNS="${RUNS:-5}"
SOLR_URL="${SOLR_URL:-http://host.docker.internal:8983/solr/}"
VERSION="$(grep '^version = ' build.gradle.kts | sed 's/version = "\(.*\)"/\1/')"
JVM_IMAGE="solr-mcp:${VERSION}"
NATIVE_IMAGE="solr-mcp:${VERSION}-native"
RESULT_FILE="docs/specs/benchmark-results.md"
# Require this many consecutive RSS samples within 1 MB to declare startup complete
STABLE_THRESHOLD="${STABLE_THRESHOLD:-3}"

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need docker
need awk
need bc

log() { printf '▶ %s\n' "$*" >&2; }

build_images() {
	log "Building JVM image…"
	./gradlew jibDockerBuild
	log "Building native image (this can take several minutes)…"
	./gradlew bootBuildImage
}

image_size_mb() {
	# Bytes -> MB with 1 decimal place
	docker image inspect "$1" --format '{{.Size}}' | awk '{printf "%.1f", $1/1024/1024}'
}

# Start container and time until it is "ready". STDIO MCP servers do not emit a
# ready signal on stdout, so we sample process memory until RSS stabilizes
# (STABLE_THRESHOLD consecutive samples within 1 MB of each other), which is a
# reasonable proxy for end-of-startup on both JVM and native.
run_one() {
	local image="$1"
	local cid
	local t_start t_ready rss_mb prev_rss=0 stable_count=0
	t_start="$(date +%s%N)"
	# Do not use --rm so we can reliably inspect and clean up the container
	cid="$(docker run -d \
		-e SOLR_URL="${SOLR_URL}" \
		-e SPRING_DOCKER_COMPOSE_ENABLED=false \
		"${image}")"

	# Poll at 25ms intervals for up to 60s
	for i in $(seq 1 2400); do
		if ! docker inspect "${cid}" >/dev/null 2>&1; then
			docker logs "${cid}" >&2 || true
			docker rm -f "${cid}" >/dev/null 2>&1 || true
			echo "container exited prematurely" >&2
			return 1
		fi
		rss_mb="$(docker stats --no-stream --format '{{.MemUsage}}' "${cid}" \
			| awk '{print $1}' | sed 's/MiB//; s/MB//; s/GiB/*1024/; s/GB/*1024/' \
			| bc 2>/dev/null || echo 0)"
		if [[ "$(echo "${rss_mb} > 0 && (${rss_mb} - ${prev_rss} < 1) && (${prev_rss} - ${rss_mb} < 1)" | bc)" == "1" ]] \
			&& [[ "${prev_rss}" != "0" ]]; then
			stable_count=$((stable_count + 1))
			if [[ "${stable_count}" -ge "${STABLE_THRESHOLD}" ]]; then
				t_ready="$(date +%s%N)"
				break
			fi
		else
			stable_count=0
		fi
		prev_rss="${rss_mb}"
		sleep 0.025
	done

	if [[ -z "${t_ready:-}" ]]; then
		docker logs "${cid}" >&2 || true
		docker rm -f "${cid}" >/dev/null 2>&1 || true
		echo "never stabilized" >&2
		return 1
	fi

	local startup_ms
	startup_ms=$(( (t_ready - t_start) / 1000000 ))
	docker rm -f "${cid}" >/dev/null 2>&1 || true
	printf '%s %s\n' "${startup_ms}" "${rss_mb}"
}

median() {
	sort -n | awk '
		{ a[NR] = $1 }
		END {
			if (NR % 2) print a[(NR+1)/2];
			else printf "%.1f", (a[NR/2] + a[NR/2+1]) / 2
		}'
}

measure() {
	local image="$1"
	local startups=() rss=()
	for i in $(seq 1 "${RUNS}"); do
		read -r s r < <(run_one "${image}")
		startups+=("${s}")
		rss+=("${r}")
		log "  run ${i}: startup=${s}ms rss=${r}MB"
	done
	local med_startup med_rss
	med_startup="$(printf '%s\n' "${startups[@]}" | median)"
	med_rss="$(printf '%s\n' "${rss[@]}" | median)"
	printf '%s %s\n' "${med_startup}" "${med_rss}"
}

build_images

log "Measuring JVM image (${RUNS} runs)…"
read -r jvm_startup jvm_rss < <(measure "${JVM_IMAGE}")
jvm_size="$(image_size_mb "${JVM_IMAGE}")"

log "Measuring native image (${RUNS} runs)…"
read -r native_startup native_rss < <(measure "${NATIVE_IMAGE}")
native_size="$(image_size_mb "${NATIVE_IMAGE}")"

pct() { echo "scale=1; ($1 / $2) * 100" | bc; }

size_pct="$(pct "${native_size}" "${jvm_size}")"
startup_pct="$(pct "${native_startup}" "${jvm_startup}")"
rss_pct="$(pct "${native_rss}" "${jvm_rss}")"

{
	echo "# Native vs JVM Benchmark Results"
	echo
	echo "Generated $(date -u +%FT%TZ) on $(uname -sm)"
	echo "Version: \`${VERSION}\`, runs: ${RUNS} per image, startup = time to RSS stabilization"
	echo
	echo "| Metric | JVM | Native | Native / JVM |"
	echo "| --- | --- | --- | --- |"
	echo "| Image size (MB) | ${jvm_size} | ${native_size} | ${size_pct}% |"
	echo "| Startup (ms, median) | ${jvm_startup} | ${native_startup} | ${startup_pct}% |"
	echo "| RSS after start (MB, median) | ${jvm_rss} | ${native_rss} | ${rss_pct}% |"
	echo
	echo "Acceptance thresholds (see spec §8.3): startup ≤25%, RSS ≤50%, size ≤60%."
} | tee "${RESULT_FILE}"
