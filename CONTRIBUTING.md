# Contributing to Solr MCP Server

Thank you for your interest in contributing to the Solr MCP Server! This document provides guidelines for contributing to the project.

## Developer documentation

To avoid duplication, the environment setup, build/run/test workflows, and detailed developer guides live in the [docs/development](docs/development) folder:

- [Development Guide](docs/development/DEVELOPMENT.md) -- build, run, test, IDE, CI
- [Architecture](docs/development/ARCHITECTURE.md) -- project structure and design decisions
- [Deployment](docs/development/DEPLOYMENT.md) -- Docker, HTTP vs STDIO
- [Troubleshooting](docs/development/TROUBLESHOOTING.md) -- common issues and solutions

If you're ready to contribute code, see [Submitting Changes](#submitting-changes) below.

## Code Style and Quality

We use Spotless for code formatting and style enforcement. CI enforces `spotlessCheck` on pull requests.

- [Commands and details](docs/development/DEVELOPMENT.md#common-gradle-tasks)
- [Build system overview](docs/development/DEVELOPMENT.md#build-system)

### Coding Standards

- Follow standard Java conventions
- Write meaningful commit messages
- Add JavaDoc for public APIs
- Include unit tests for new features
- Keep methods focused and concise

## Testing

To keep this document concise, please see the Development Guide for all testing workflows and tips:

- [Testing overview](docs/development/DEVELOPMENT.md#testing)
- [Unit tests](docs/development/DEVELOPMENT.md#unit-tests)
- [Integration tests](docs/development/DEVELOPMENT.md#integration-tests)
- [Docker image tests](docs/development/DEVELOPMENT.md#docker-integration-tests)
- [Coverage reports](docs/development/DEVELOPMENT.md#testing)

## Publishing to Maven Local

To install the project artifacts to your local Maven repository for testing or local development:

```bash
./gradlew publishToMavenLocal
```

This publishes the following artifacts to `~/.m2/repository/org/apache/solr/solr-mcp/{version}/`:

- `solr-mcp-{version}.jar` - Main application JAR
- `solr-mcp-{version}-sources.jar` - Source code for IDE navigation
- `solr-mcp-{version}-javadoc.jar` - API documentation
- `solr-mcp-{version}.pom` - Maven POM with dependencies

This is useful when:
- Testing the library locally before publishing to a remote repository
- Sharing artifacts between local projects during development
- Verifying the published POM and artifact structure

## Submitting Changes

### Pull Request Process

1. **Create a feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes**
   - Write clean, well-documented code
   - Add tests for new functionality
   - Update documentation as needed

3. **Format your code**
   ```bash
   ./gradlew spotlessApply
   ```

4. **Run tests**
   ```bash
   ./gradlew build
   ```

5. **Commit your changes**
   ```bash
   git add .
   git commit -m "feat: add your feature description"
   ```

6. **Push to your fork**
   ```bash
   git push origin feature/your-feature-name
   ```

7. **Create a Pull Request**
   - Provide a clear description of the changes
   - Reference any related issues
   - Ensure CI checks pass

### Commit Message Guidelines

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

**Examples:**
```
feat(search): add fuzzy search support
fix(indexing): handle null values in CSV parser
docs: update installation instructions
test: add integration tests for collection service
```

## Development workflow references

For implementation details and examples, see the Development Guide:

- [Adding new MCP tools](docs/development/DEVELOPMENT.md#adding-a-new-mcp-tool)
- [Adding a new document format](docs/development/DEVELOPMENT.md#adding-a-new-document-format)
- [Project structure and architecture](docs/development/ARCHITECTURE.md)
- [Dependencies and version catalogs](docs/development/DEVELOPMENT.md#build-system)
- [Documentation practices](docs/development/DEVELOPMENT.md#modifying-configuration)

## Security Setup (HTTP Mode)

For OAuth2 configuration with supported providers:

- [Auth0 Setup Guide](docs/development/AUTH0_SETUP.md)
- [Keycloak Setup Guide](docs/development/keycloak.md)

## Questions or Need Help?

- **Slack:** [`#solr-mcp`](https://the-asf.slack.com/archives/C09TVG3BM1P) in the `the-asf` workspace
- **Issues:** [GitHub Issues](https://github.com/apache/solr-mcp/issues) for bugs or feature requests
- **Discussions:** [GitHub Discussions](https://github.com/apache/solr-mcp/discussions) for questions or ideas
- **Mailing lists:** Shared with Apache Solr -- see [mailing lists](https://solr.apache.org/community.html#mailing-lists-chat)

## Code of Conduct

As an Apache project, we follow the [Apache Code of Conduct](https://www.apache.org/foundation/policies/conduct).

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
