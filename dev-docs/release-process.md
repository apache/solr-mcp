To release Solr MCP, these are the steps:

1) Create a release branch like branch_1_0_0.  Or update the release branch with latest changes.

1) Switch to that branch.

1) Merge https://github.com/apache/solr-mcp/pull/136/changes into the branch.

1) Set up a release in Apache Trusted Release server at: blah.

1) create the artifacts via `./gradlew clean build`

1) Sign them via:
```
for fn in *.jar
do
  ../../gpgsign.sh sign ~/.ssh/.private.asc "$fn"
done
```

1) Make sha keys via:
```
for fn in *.jar
do
  sha512 $fn > $fn.sha512
done
```

1) Uplaod all the artifacts to the previously created release in ATR.

1) Test in Claude Code and Claude Desktop the following steps.

1) IN the vote thread we want to make it easy for folks to test.   Add the following steps:

```


```

1) After the vote, create a tag with the source code in github, as `releases/
solr-mcp/1.0.0`

1) After the vote bump the version tag in `main` for 1.1 so we get -snapshot builds there.
