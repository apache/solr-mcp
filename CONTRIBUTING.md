# Contributing to Solr MCP Server

Thank you for your interest in contributing to the Solr MCP Server! This document provides guidelines for contributing to the project.

## Developer documentation

To avoid duplication, the environment setup, build/run/test workflows, and detailed developer guides live in the dev-docs folder:

- Development Guide (build, run, test, IDE, CI): dev-docs/DEVELOPMENT.md
- Architecture: dev-docs/ARCHITECTURE.md
- Deployment (Docker, HTTP vs STDIO): dev-docs/DEPLOYMENT.md
- Troubleshooting: dev-docs/TROUBLESHOOTING.md

If you're ready to contribute code, see Submitting Changes below.

## Code Style and Quality

We use Spotless for code formatting and style enforcement. CI enforces `spotlessCheck` on pull requests.

- Commands and details: dev-docs/DEVELOPMENT.md#common-gradle-tasks
- Build system overview: dev-docs/DEVELOPMENT.md#build-system

### Coding Standards

- Follow standard Java conventions
- Write meaningful commit messages
- Add JavaDoc for public APIs
- Include unit tests for new features
- Keep methods focused and concise

## Testing

To keep this document concise, please see the Development Guide for all testing workflows and tips:

- Testing overview: dev-docs/DEVELOPMENT.md#testing
- Unit tests: dev-docs/DEVELOPMENT.md#unit-tests
- Integration tests: dev-docs/DEVELOPMENT.md#integration-tests
- Docker image tests: dev-docs/DEVELOPMENT.md#docker-integration-tests
- Coverage reports: dev-docs/DEVELOPMENT.md#testing

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

- Adding new MCP tools: dev-docs/DEVELOPMENT.md#adding-a-new-mcp-tool
- Adding a new document format: dev-docs/DEVELOPMENT.md#adding-a-new-document-format
- Project structure and architecture: dev-docs/ARCHITECTURE.md
- Dependencies and version catalogs: dev-docs/DEVELOPMENT.md#build-system
- Documentation practices: dev-docs/DEVELOPMENT.md#modifying-configuration

## Questions or Need Help?

- Open an issue for bugs or feature requests
- Start a discussion for questions or ideas
- Check existing issues and discussions first

## Code of Conduct

Be respectful, inclusive, and professional. We're all here to build something great together.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
