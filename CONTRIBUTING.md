# Contributing to Solr MCP Server

Thank you for your interest in contributing to the Solr MCP Server! This document provides guidelines for contributing to the project.

## Getting Started

### Prerequisites

- Java 25 or higher
- Docker and Docker Compose (for running Solr)
- Gradle 9.1.0+ (wrapper included)

### Development Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/apache/solr-mcp.git
   cd solr-mcp
   ```

2. **Start Solr**
   ```bash
   docker-compose up -d
   ```

3. **Build the project**
   ```bash
   ./gradlew build
   ```

4. **Run tests**
   ```bash
   ./gradlew test
   ```

5. **Run the server locally**
   ```bash
   # STDIO mode
   ./gradlew bootRun

   # HTTP mode
   ./gradlew bootRun --args='--spring.profiles.active=http'
   ```

## Code Style and Quality

This project uses [Spotless](https://github.com/diffplug/spotless) for code formatting and style enforcement.

### Format your code

Before committing, ensure your code is properly formatted:

```bash
# Check formatting
./gradlew spotlessCheck

# Apply formatting automatically
./gradlew spotlessApply
```

**Important**: All code must pass `spotlessCheck` before being merged. The CI pipeline will reject PRs with formatting issues.

### Coding Standards

- Follow standard Java conventions
- Write meaningful commit messages
- Add JavaDoc for public APIs
- Include unit tests for new features
- Keep methods focused and concise

## Testing

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests SearchServiceTest

# Run Docker integration tests
./gradlew dockerIntegrationTest
```

### Test Coverage

- Aim for >80% test coverage for new code
- Include both unit tests and integration tests
- Test edge cases and error conditions

### Writing Tests

- Use JUnit 5 for unit tests
- Use Testcontainers for integration tests
- Mock external dependencies when appropriate
- Follow the Arrange-Act-Assert pattern

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

## Development Guidelines

### Adding New MCP Tools

1. Create a service class in the appropriate package
2. Annotate methods with `@McpTool`
3. Add proper parameter validation
4. Include comprehensive tests
5. Update documentation

Example:
```java
@McpTool(
    name = "my_tool",
    description = "Description of what this tool does"
)
public String myTool(
    @McpToolParameter(description = "Parameter description")
    String param
) {
    // Implementation
}
```

### Project Structure

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed project structure and design decisions.

### Dependencies

- All dependencies are managed via Gradle version catalogs in `gradle/libs.versions.toml`
- Discuss major dependency additions in an issue before implementing
- Keep dependencies up to date

## Documentation

- Update README.md for user-facing changes
- Update docs/ for developer-facing changes
- Add JavaDoc for public APIs
- Include code examples where helpful

## Questions or Need Help?

- Open an issue for bugs or feature requests
- Start a discussion for questions or ideas
- Check existing issues and discussions first

## Code of Conduct

Be respectful, inclusive, and professional. We're all here to build something great together.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
