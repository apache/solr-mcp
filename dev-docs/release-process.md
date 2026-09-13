To release Solr MCP, these are the steps:

1) Create a release branch like branch_1_0_0.  Or update the existing release branch with latest changes.

1) Switch to that branch.

1) Deal with `-SNAPSHOT` build artifacts.  For 1.0 we will merge https://github.com/apache/solr-mcp/pull/136/changes into the branch.

1) Set up a release in Apache Trusted Release server at https://release-test.apache.org/#project-solr-mcp.

1) create the artifacts via `./gradlew clean build`

1) `./gradlew build` already ran the Apache RAT license-header check (`org.apache.solr.mcp.rat`) and regenerated the binary `LICENSE`/`NOTICE` as part of `check` — confirm the build was green, then spot-check the generated files bundled in the bootJar:
```
unzip -p build/libs/solr-mcp-X.Y.Z.jar META-INF/LICENSE
unzip -p build/libs/solr-mcp-X.Y.Z.jar META-INF/NOTICE
```

1) Sign them via:
```
for fn in *.jar
do
  ../../gpgsign.sh sign ~/.ssh/.private.asc "$fn"
done
```

1) Make sha keys via (macOS; use `sha512sum` instead of `sha512` on Linux):
```
for fn in *.jar
do
  sha512 $fn > $fn.sha512
done
```

1) Upload all the artifacts to the previously created release in ATR.

1) Test in Claude Code or Claude Desktop using the steps in ./dev-docs/SMOKE_TEST.md.

1) After the vote, create a tag with the source code in github, as `releases/solr-mcp/1.0.0`

1) After the vote bump the version tag in `main` for 1.1 so we get -snapshot builds there.
