# Development Guide

This guide covers development workflows, testing, and building the Solr MCP Server.

## Build System

This project uses Gradle with version catalogs for dependency management. All dependencies and their versions are centrally managed in `gradle/libs.versions.toml`.

### Common Gradle Tasks

```bash
# Build the project and run tests
./gradlew build

# Build without tests (faster)
./gradlew assemble

# Clean and rebuild
./gradlew clean build

# Run tests only
./gradlew test

# Run Docker integration tests
./gradlew dockerIntegrationTest

# Check code formatting
./gradlew spotlessCheck

# Apply code formatting
./gradlew spotlessApply
```

### Build Outputs

The build produces two JAR files in `build/libs/`:

- `solr-mcp-0.0.1-SNAPSHOT.jar` - Executable JAR with all dependencies (fat JAR)
- `solr-mcp-0.0.1-SNAPSHOT-plain.jar` - Plain JAR without dependencies

## Running Locally

### Start Solr

```bash
docker-compose up -d
```

This starts a Solr instance in SolrCloud mode with ZooKeeper and creates two sample collections:
- `books` - Collection with sample book data
- `films` - Collection with sample film data

### Run the Server

#### STDIO Mode (Default)

```bash
./gradlew bootRun
```

Or using the JAR:
```bash
java -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
```

#### HTTP Mode

```bash
./gradlew bootRun --args='--spring.profiles.active=http'
```

The server will start on http://localhost:8080

### Environment Variables

- `SOLR_URL`: Solr instance URL (default: `http://localhost:8983/solr/`)
- `PROFILES`: Transport mode (`stdio` or `http`)
- `SPRING_DOCKER_COMPOSE_ENABLED`: Enable/disable Docker Compose integration (default: `true`)

Example:
```bash
SOLR_URL=http://my-solr:8983/solr/ ./gradlew bootRun
```

## Testing

### Unit Tests

Unit tests use mocked dependencies for fast, isolated testing:

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests SearchServiceTest

# Run with coverage
./gradlew test jacocoTestReport
```

### Integration Tests

Integration tests use Testcontainers to spin up real Solr instances:

```bash
# Run integration tests
./gradlew test --tests "*IntegrationTest"
```

### Docker Integration Tests

These tests verify the Docker images built by Jib:

```bash
# Build Docker image first
./gradlew jibDockerBuild

# Run Docker integration tests
./gradlew dockerIntegrationTest
```

This runs tests tagged with `@Tag("docker-integration")` which verify:
- STDIO mode functionality
- HTTP mode functionality
- Container stability
- Solr connectivity

### Test with MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) provides a web UI for testing:

```bash
# Start the server in HTTP mode
./gradlew bootRun --args='--spring.profiles.active=http'

# In another terminal, start MCP Inspector
npx @modelcontextprotocol/inspector
```

Then open the browser URL provided (typically http://localhost:6274) and connect to http://localhost:8080/mcp

## Code Quality

### Spotless Formatting

This project uses Spotless for consistent code formatting:

```bash
# Check if code is formatted correctly
./gradlew spotlessCheck

# Auto-format all code
./gradlew spotlessApply
```

**Important**: Always run `spotlessApply` before committing. The CI will reject PRs with formatting issues.

### Error Prone

Error Prone is configured to catch common Java mistakes at compile time. It will fail the build if issues are found.

## Build Info

The project generates build metadata at build time via the Spring Boot Gradle plugin. This creates `META-INF/build-info.properties` containing:

- `build.artifact`: Artifact name (e.g., "solr-mcp")
- `build.group`: Group ID (e.g., "org.apache.solr")
- `build.name`: Project name
- `build.version`: Version (e.g., "0.0.1-SNAPSHOT")
- `build.time`: Build timestamp

This metadata is used by:
- Spring Boot Actuator (`/actuator/info` endpoint)
- Test utilities (e.g., `BuildInfoReader`)
- Docker image labels
- Runtime version introspection

## Docker Development

See [DEPLOYMENT.md](DEPLOYMENT.md) for detailed Docker build instructions.

### Quick Docker Build

```bash
# Build to local Docker daemon
./gradlew jibDockerBuild

# Run the image
docker run -i --rm solr-mcp:0.0.1-SNAPSHOT
```

### Docker Executable Configuration

Jib needs to find the Docker executable. The build auto-detects based on your OS:

- **macOS**: `/usr/local/bin/docker`
- **Linux**: `/usr/bin/docker`
- **Windows**: `C:\Program Files\Docker\Docker\resources\bin\docker.exe`

Override if needed:
```bash
export DOCKER_EXECUTABLE=/custom/path/to/docker
./gradlew jibDockerBuild
```

## IDE Setup

### IntelliJ IDEA

1. Open the project directory
2. IDEA will automatically detect it as a Gradle project
3. Enable annotation processing for Lombok (if used)
4. Install Spotless plugin for automatic formatting

### VS Code

1. Install Java Extension Pack
2. Install Gradle Extension
3. Open the project directory
4. VS Code will configure automatically

## Debugging

### Debug STDIO Mode

Since STDIO uses stdin/stdout for protocol communication, traditional debugging can interfere. Use these approaches:

1. **Log to file**:
   ```java
   System.setOut(new PrintStream(new FileOutputStream("debug.log")));
   ```

2. **Use IDE remote debugging**:
   ```bash
   ./gradlew bootRun --debug-jvm
   ```
   Then attach your IDE debugger to port 5005

### Debug HTTP Mode

Standard debugging works normally:

1. Start in debug mode in your IDE
2. Set breakpoints
3. Make HTTP requests to http://localhost:8080

## Common Development Tasks

### Adding a New MCP Tool

1. Create a new method in an existing service or new service class
2. Annotate with `@McpTool`:
   ```java
   @McpTool(
       name = "tool_name",
       description = "What this tool does"
   )
   public String myTool(
       @McpToolParameter(description = "Parameter description")
       String param
   ) {
       // Implementation
   }
   ```
3. Add tests
4. Update documentation

### Adding a New Document Format

1. Create a new class implementing `IndexingDocumentCreator`
2. Register in `SolrDocumentCreator` factory
3. Add tests
4. Update documentation

### Modifying Configuration

1. Update `application.properties` for defaults
2. Update profile-specific properties as needed
3. Update `SolrConfigurationProperties` if adding new properties
4. Document in README

## Performance Testing

### Load Testing HTTP Mode

Use tools like Apache JMeter or wrk:

```bash
# Install wrk
brew install wrk

# Run load test
wrk -t4 -c100 -d30s http://localhost:8080/mcp
```

### Profiling

Use Java Flight Recorder:

```bash
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
     -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
```

Analyze with Java Mission Control.

## Continuous Integration

The project uses GitHub Actions for CI/CD. See:
- `.github/workflows/build.yml` - Build and test
- `.github/workflows/build-and-publish.yml` - Docker image publishing
- `.github/workflows/publish-mcp.yml` - MCP Registry publishing

Local CI simulation:

```bash
# Run what CI runs
./gradlew clean build spotlessCheck dockerIntegrationTest
```
