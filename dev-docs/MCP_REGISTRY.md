# Publishing to the MCP Registry

This guide covers how a voted Solr MCP release reaches the
[official MCP Registry](https://modelcontextprotocol.io/registry/about) as
`io.github.apache/solr-mcp`, and where that step sits in the ATR release described in
[release-process.md](release-process.md). The PMC and Infra prerequisites are tracked in
[#251](https://github.com/apache/solr-mcp/issues/251).

## How the registry works

The registry stores metadata, not artifacts. `server.json` at the repository root lists
`packages`, and each package points at an artifact hosted somewhere else. Solr MCP lists
its JVM container image twice, once on Docker Hub and once on GHCR, both with the stdio
transport. There is no Maven package type, so the JAR is not listed.

When `mcp-publisher publish` runs, the registry checks each package:

- The image must be on an allowed host (`docker.io`, `ghcr.io`, `quay.io`,
  `mcr.microsoft.com`, `*.pkg.dev` or `*.azurecr.io`) and pullable without credentials.
- The image must carry the label `io.modelcontextprotocol.server.name` with the value
  `io.github.apache/solr-mcp`. The Jib configuration in `build.gradle.kts` sets it. The
  native images don't carry it, which is why they are not listed.
- An OCI package has no `version` field. Its version is the tag in `identifier`, such as
  `docker.io/apache/solr-mcp:1.0.0`. The identifier may also carry a digest
  (`docker.io/apache/solr-mcp:1.0.0@sha256:…`), which the release workflow adds.

`mcp-publisher validate` checks the schema only, not the package checks above, so a file
that passes `validate` can still be rejected by `publish`.

A published version cannot be edited. Its `status` can be changed (for example to
`deprecated` with `mcp-publisher status`), but a mistake in `1.0.0` is fixed by publishing
a new version, not by republishing `1.0.0`.

The registry grants the `io.github.apache/*` namespace to GitHub Actions runs in
`apache/*` repositories through OIDC (`mcp-publisher login github-oidc`), so no secret is
stored. An interactive `mcp-publisher login github` only grants the namespace to an Owner
of the `apache` GitHub organization, so publishing is done from the workflow.

Other catalogs read from the official registry, including JFrog AI Catalog's public
listings and aggregators such as PulseMCP. The Docker MCP Catalog does not; it needs a
separate submission to [docker/mcp-registry](https://github.com/docker/mcp-registry)
after the release.

## What the workflow does

Before any image is pushed, the `publish-docker` job checks that its build reproduces the
voted JAR. The images are built from the voted tag rather than from the voted files, so the
job downloads `solr-mcp-<version>.jar` and its `.sha512` from
`dist.apache.org/repos/dist/release/solr/mcp/<version>/`. It then checks the hash and
compares the unpacked JAR with its own build. Two entries differ on every build and are
left out: the build time in `META-INF/build-info.properties` and the timestamp and serial
number in `META-INF/sbom/application.cdx.json`. Every class, resource and bundled
dependency must match, or the job stops before pushing anything. This step needs the
release to be published on ATR first, which is why that comes first in the order below.
A mismatch usually means the voted JAR was built with a different JDK than the one in
`.github/actions/setup-java`; the `diff` output shows which entries differ.

The `publish-mcp-registry` job runs after the image jobs, from the voted candidate tag
`releases/solr-mcp/<version>-<rc>`:

1. Writes `<version>` into `server.json`'s `version` and into the tag of every package
   `identifier`, dropping any digest. It fails if a package has a `version` field or an
   identifier didn't take the new tag.
2. Runs the registry's own package checks on each image, without logging in: the host
   is one the registry accepts, the image can be pulled anonymously, and every platform
   carries the ownership label. A failure names the next step to take (on the first
   release, GHCR's private package). It then pins each identifier to the digest it
   checked, so the entry names that exact image even if the tag is later moved.
3. Installs `mcp-publisher` at a pinned version, verified against the release checksums.
4. Runs `mcp-publisher validate`, `login github-oidc` and `publish`.
5. Reads `GET /v0.1/servers/io.github.apache%2Fsolr-mcp/versions/<version>` back and
   fails if that version doesn't appear.

## Where it fits in the ATR release

ATR stages, votes on and publishes the source archive and JARs. It does not push
container images or registry entries. Its finish phase leaves third-party distribution to
the release manager and only records it
([ATR finish phase](https://releases.apache.org/docs/finish-phase)).

```
ATR vote passes ──► release is in ATR's finish phase
      │
      ├─► 1. ATR Publish: artifacts to downloads.apache.org/solr/mcp/<v>/
      │
      ├─► 2. run release-publish.yml (release_version=<v>, release_candidate=<rc>)
      │        images → Docker Hub + GHCR → MCP Registry entry
      │
      ├─► 3. record the Docker Hub distribution on ATR
      │
      └─► 4. ATR Announce
```

### Before the first release

These are one-time steps for the PMC and Infra (details in
[#251](https://github.com/apache/solr-mcp/issues/251)):

- The Docker Hub repository `apache/solr-mcp`, and the `DOCKERHUB_USER` /
  `DOCKERHUB_TOKEN` repository secrets that `release-publish.yml` reads.
- Making the `ghcr.io/apache/solr-mcp` package public. GHCR creates packages as private,
  so on the first release the image check in step 2 fails until Infra changes the
  visibility. Re-run the `publish-mcp-registry` job afterwards. Later releases push to the
  same package.

### During the release

1. **Publish on ATR** as in [release-process.md](release-process.md), step 5.
2. **Run `release-publish.yml`** from the Actions tab with `release_version` (for
   example `1.0.0`) and `release_candidate` (for example `rc1`). It checks out
   `releases/solr-mcp/<version>-<rc>`, the tag the PMC voted on. Run it after step 1,
   because it compares its build with the voted JAR on dist, and before announcing, so
   the announcement can point at the images and the registry entry.
3. **Record the Docker Hub distribution on ATR.** On the release's finish page select
   ***Verify a third-party distribution*** and enter:

   | Field | Value |
   | --- | --- |
   | Platform | Docker Hub |
   | Owner or namespace | `apache` |
   | Package | `solr-mcp` |
   | Version | `<version>` |

   ATR looks the tag up on Docker Hub before it accepts the record. The same record can
   be made through the API with a JWT exchanged from an ATR PAT
   ([ATR API](https://releases.apache.org/docs/release-manager-setup#recording-distributions)):

   ```bash
   curl -sSf -X POST "https://releases.apache.org/api/distribution/record" \
     -H "Authorization: Bearer ${JWT}" -H "Content-Type: application/json" \
     -d '{"project": "solr-mcp", "version": "1.0.0", "platform": "DOCKER_HUB",
          "distribution_owner_namespace": "apache", "distribution_package": "solr-mcp",
          "distribution_version": "1.0.0", "staging": false, "details": false}'
   ```

4. **Announce on ATR.**

### Why the workflow doesn't record the distribution itself

The Tooling team's `apache/tooling-actions/record-atr-distribution` action records a
distribution from a workflow using GitHub OIDC. ATR accepts that call only when
both of these hold:

- The committee has an Automated Release Signing key, shown as *CI builds: Enabled* on the
  committee page. Getting that key needs the ASF Security team to approve reproducible
  builds ([ATR Trusted Publishing](https://releases.apache.org/docs/trusted-publishing)).
- The project's release policy on ATR names `apache/solr-mcp` as its GitHub repository
  and `.github/workflows/release-publish.yml` as a finish-phase workflow path
  ([Configuring repository and workflow paths](https://releases.apache.org/docs/trusted-publishing#configuring-repository-and-workflow-paths)).

Until both are in place, the record is a manual step.

### What ATR can't record yet

ATR can record distributions to Maven Central, PyPI, npm, Docker Hub and Artifact Hub.
It has no GHCR (or generic OCI) platform and no MCP Registry platform, so the GHCR image
and the registry entry are not recorded on ATR. Its Docker Hub check confirms that the tag
exists; it does not compare the image digest with anything voted on. Those are upstream
ATR changes, under its third-party distribution track
([tooling-trusted-releases#1336](https://github.com/apache/tooling-trusted-releases/issues/1336)).

ATR can also block the announcement until a distribution is recorded: a `dockerhub` tag
in the project's tagging spec makes the Docker Hub record mandatory. Tags also select
files in a release, and ATR is redesigning them
([tooling-trusted-releases#1702](https://github.com/apache/tooling-trusted-releases/issues/1702)),
so Solr MCP does not set one for now.
