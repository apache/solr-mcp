# Nightly Jar Snapshot Guide for Apache Solr MCP

This guide documents the nightly SNAPSHOT jar publishing process for Apache Solr MCP —
the Maven/Gradle-dependency counterpart to the nightly Docker image described in
[DOCKER_PUBLISHING.md](DOCKER_PUBLISHING.md).

## Overview

The project publishes a nightly `org.apache.solr:solr-mcp:1.0.0-SNAPSHOT` jar (main
artifact + sources + javadoc) built from `main` to the **Apache Nexus snapshot
repository**:

```
https://repository.apache.org/content/repositories/snapshots
```

This lets people consume the latest `main` as a Maven or Gradle dependency without
waiting for a voted release.

### Why not Maven Central?

Maven Central never accepts SNAPSHOT/nightly artifacts — that is a hard
Sonatype/Central platform rule, not a project choice. The ASF-native destination for
SNAPSHOT artifacts is the Apache Nexus snapshot repository above, which `org.apache.*`
Maven groupIds — this project's groupId is `org.apache.solr` — are entitled to publish
to. This mirrors how other `org.apache.*` projects (including Apache Solr itself)
publish nightly/SNAPSHOT jars.

## Build System

The project already applies the `maven-publish` Gradle plugin and defines a `maven`
publication (`group = "org.apache.solr"`, `version = "1.0.0-SNAPSHOT"`) in
`build.gradle.kts`, which includes:

- `solr-mcp-1.0.0-SNAPSHOT.jar` (main artifact)
- `solr-mcp-1.0.0-SNAPSHOT-sources.jar`
- `solr-mcp-1.0.0-SNAPSHOT-javadoc.jar`
- `solr-mcp-1.0.0-SNAPSHOT.pom`

The `publishing { repositories { ... } } }` block targets the ASF Nexus snapshot
repository above, with credentials read lazily via Gradle's provider API
(`providers.environmentVariable("ASF_NEXUS_USERNAME"/"ASF_NEXUS_PASSWORD")`). This is
deliberately **lazy**: no other Gradle task (`build`, `test`, `publishToMavenLocal`,
...) is affected when these env vars are unset. Only running `./gradlew publish`
against the ASF Nexus repository fails, and it fails loudly with a clear message
(`doFirst` check in `build.gradle.kts`) instead of an opaque HTTP 401 deep in the
Maven-publish plugin.

## Publishing Workflows

### Local publish (manual, for testing)

```bash
export ASF_NEXUS_USERNAME=yourasfid
export ASF_NEXUS_PASSWORD=yourasfpassword-or-token
./gradlew publish
```

Without these two env vars set, `./gradlew publish` fails immediately with:

```
ASF Nexus deploy credentials are not configured. Set the ASF_NEXUS_USERNAME and
ASF_NEXUS_PASSWORD environment variables before running ./gradlew publish.
See dev-docs/NIGHTLY_JAR.md.
```

`./gradlew publishToMavenLocal` (installing to `~/.m2/repository`) needs no
credentials and is unaffected.

### Nightly Jar (ASF Nexus snapshot repository)

**Workflow**: `.github/workflows/nightly-jar.yml`

- **Trigger**: Scheduled (2 AM UTC daily, same cadence as `nightly-build.yml`'s
  Docker image) or manual (`workflow_dispatch`)
- **Published to**: `org.apache.solr:solr-mcp:1.0.0-SNAPSHOT` at
  `https://repository.apache.org/content/repositories/snapshots`
- **Scope**: Jar artifacts only, built from `main`. The job fails loudly (rather than
  silently skipping) if `ASF_NEXUS_USERNAME` / `ASF_NEXUS_PASSWORD` are unset.
- **Not an ASF release**: no vote, no signing, no `dist.apache.org` artifacts. This is
  a convenience SNAPSHOT dependency only.
- **Guarded to `apache/solr-mcp`**: the workflow no-ops on forks
  (`if: github.repository == 'apache/solr-mcp'`), since scheduled workflows also run
  on forks with Actions enabled, and forks shouldn't be publishing under the
  `org.apache.solr` groupId.

## ⚠️ Credentials are not provisioned yet

**This is the single most important thing in this document.**

`nightly-jar.yml` requires ASF Nexus deploy credentials as GitHub Actions repo
secrets:

- `ASF_NEXUS_USERNAME`
- `ASF_NEXUS_PASSWORD`

**These secrets do not exist in this repository as of this writing.** An ASF
committer with `org.apache.solr` Nexus deploy rights — **Eric Pugh or Jan Høydahl** —
must provision them as GitHub Actions repo secrets before this workflow can succeed.

Until then, `nightly-jar.yml`'s scheduled run **will fail loudly** on the credential
check step. **This is intentional fail-loud behavior, not a bug** — the alternative
(silently skipping the publish) would report a green workflow while shipping nothing,
which is worse.

### Open question for Eric / Jan

It is not yet confirmed whether `org.apache.solr` is already pre-provisioned in ASF's
Nexus for this specific sub-project (`solr-mcp`), the way it presumably is for the
main Apache Solr project, or whether that itself needs to be requested separately
(e.g. via an INFRA ticket or LDAP group membership for Nexus deploy rights scoped to
`solr-mcp`). This document makes no claim either way — it is an open question for
whichever of Eric Pugh or Jan Høydahl provisions the credentials to confirm.

## Consuming the nightly SNAPSHOT

Once published, add the ASF Nexus snapshot repository to your build and depend on the
SNAPSHOT version:

**Gradle:**

```kotlin
repositories {
    maven { url = uri("https://repository.apache.org/content/repositories/snapshots") }
    mavenCentral()
}

dependencies {
    implementation("org.apache.solr:solr-mcp:1.0.0-SNAPSHOT")
}
```

**Maven:**

```xml
<repositories>
    <repository>
        <id>apache-snapshots</id>
        <url>https://repository.apache.org/content/repositories/snapshots</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>

<dependency>
    <groupId>org.apache.solr</groupId>
    <artifactId>solr-mcp</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

As with any SNAPSHOT dependency, the artifact at this coordinate changes daily and is
not reproducible — do not depend on it from production code.

## Related

- [DOCKER_PUBLISHING.md](DOCKER_PUBLISHING.md) — the nightly *Docker image* counterpart
  (`nightly-build.yml`, `apache/solr-mcp-nightly`), same fail-loud pattern, same
  `if: github.repository == 'apache/solr-mcp'` fork guard.
- [WORKFLOWS.md](WORKFLOWS.md) — overview of all GitHub Actions workflows in this repo.
