## Summary

<!-- What changes and why, in a few lines. Link the issue: "Closes #123" or "Refs #123". -->

Closes #

## Checklist

<!-- Tick what applies; strike through or delete what does not, and say why. -->

- [ ] `./gradlew build` passes locally (Spotless, Error Prone/NullAway, RAT, unit and integration tests)
- [ ] The SonarCloud check passes, with no new issues on this PR
- [ ] New or changed behaviour has a test that fails without the change
- [ ] User-visible change: one line under `[Unreleased]` in `CHANGELOG.md` (see `dev-docs/CHANGELOG_GUIDE.md`)
- [ ] New or changed MCP tool, prompt or resource: the README tables, `THREAT_MODEL.md` and the tool counts in `docs/clients/` are updated, and every new response record is registered in `config/SolrNativeHints.java`
- [ ] New package, tool or environment variable: `AGENTS.md` is updated
- [ ] Docker image or native-image change: `./gradlew dockerIntegrationTest` (and `-Pnative` where relevant) passes locally

## How a reviewer can check this

<!-- The commands you ran, or the steps to see the change working. -->
