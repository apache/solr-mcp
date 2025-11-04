# Deployment Guide

This guide covers building Docker images, publishing to registries, and deploying the Solr MCP Server.

## Docker Images with Jib

This project uses [Jib](https://github.com/GoogleContainerTools/jib) to build optimized Docker images. Jib creates layered images for faster rebuilds and smaller sizes.

### Build to Docker Daemon

Build directly to your local Docker daemon (requires Docker installed):

```bash
./gradlew jibDockerBuild
```

This creates: `solr-mcp:0.0.1-SNAPSHOT`

Verify:
```bash
docker images | grep solr-mcp
```

### Push to Docker Hub

Authenticate and push (no local Docker daemon required):

```bash
# Login to Docker Hub
docker login

# Build and push
./gradlew jib -Djib.to.image=YOUR_DOCKERHUB_USERNAME/solr-mcp:0.0.1-SNAPSHOT
```

### Push to GitHub Container Registry

Authenticate and push (no local Docker daemon required):

```bash
# Create Personal Access Token with write:packages scope:
# https://github.com/settings/tokens

# Login to GHCR
export GITHUB_TOKEN=YOUR_GITHUB_TOKEN
echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin

# Build and push
./gradlew jib -Djib.to.image=ghcr.io/YOUR_GITHUB_USERNAME/solr-mcp:0.0.1-SNAPSHOT
```

### Multi-Platform Support

Docker images are built with multi-platform support for:
- `linux/amd64` (Intel/AMD 64-bit)
- `linux/arm64` (Apple Silicon M1/M2/M3/M4/M5)

Jib automatically selects the appropriate platform or builds the first specified platform.

## Running Docker Containers

### STDIO Mode (Default)

```bash
docker run -i --rm solr-mcp:0.0.1-SNAPSHOT
```

With custom Solr URL:
```bash
docker run -i --rm \
  -e SOLR_URL=http://your-solr-host:8983/solr/ \
  solr-mcp:0.0.1-SNAPSHOT
```

### HTTP Mode

```bash
docker run -p 8080:8080 --rm \
  -e PROFILES=http \
  -e SOLR_URL=http://your-solr-host:8983/solr/ \
  solr-mcp:0.0.1-SNAPSHOT
```

### Linux Host Networking

On Linux, to connect to Solr on the host machine:

```bash
docker run -i --rm \
  --add-host=host.docker.internal:host-gateway \
  -e SOLR_URL=http://host.docker.internal:8983/solr/ \
  solr-mcp:0.0.1-SNAPSHOT
```

## GitHub Actions CI/CD

### Workflows

- `.github/workflows/build-and-publish.yml` — Build, test, and publish Docker images
- `.github/workflows/publish-mcp.yml` — Publish to the Model Context Protocol Registry on version tags

### Docker image publishing

Automated Docker image publishing is not configured in this repository.
To publish images, use Jib from your local machine or set up your own workflow:

- Docker Hub:
  ```bash
  docker login
  ./gradlew jib -Djib.to.image=DOCKERHUB_USERNAME/solr-mcp:0.0.1-SNAPSHOT
  ```
- GitHub Container Registry (GHCR):
  ```bash
  export GITHUB_TOKEN=YOUR_GITHUB_TOKEN
  echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin
  ./gradlew jib -Djib.to.image=ghcr.io/YOUR_GITHUB_USERNAME/solr-mcp:0.0.1-SNAPSHOT
  ```

### MCP Registry Publishing

`.github/workflows/publish-mcp.yml` publishes to the Model Context Protocol Registry.

**Triggers:**
- Version tags (e.g., `v0.1.0`)
- Manual workflow dispatch

**Authentication:**
Uses GitHub OIDC (no secrets required)

**Publishing Process:**

1. Tag a release:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

2. GitHub Actions automatically:
   - Updates `server.json` version
   - Authenticates with MCP Registry via OIDC
   - Publishes server metadata
   - Verifies publication

3. Verify in registry:
   ```bash
   curl "https://registry.modelcontextprotocol.io/v0/servers?search=io.github.apache/solr-mcp"
   ```

## MCP Registry Configuration

### server.json

The `server.json` file defines MCP registry metadata:

```json
{
  "$schema": "https://static.modelcontextprotocol.io/schemas/2025-10-17/server.schema.json",
  "name": "io.github.apache/solr-mcp",
  "description": "MCP server for Apache Solr",
  "version": "0.0.1",
  "packages": [
    {
      "registryType": "docker",
      "identifier": "ghcr.io/apache/solr-mcp",
      "version": "0.0.1-SNAPSHOT",
      "transport": {
        "type": "stdio"
      }
    }
  ]
}
```

### Docker Image Label

The Docker image includes an MCP label for registry discovery:

```kotlin
labels.set(
    mapOf(
        "io.modelcontextprotocol.server.name" to "io.github.apache/solr-mcp"
    )
)
```

## Manual MCP Registry Publishing

If you need to publish manually:

1. **Install MCP Publisher CLI**
   ```bash
   # macOS
   brew install modelcontextprotocol/tap/mcp-publisher

   # Or download from GitHub releases
   curl -L https://github.com/modelcontextprotocol/registry/releases/latest/download/mcp-publisher-[OS]-[ARCH].tar.gz | tar xz
   ```

2. **Authenticate**
   ```bash
   # GitHub OIDC (recommended)
   mcp-publisher login github-oidc

   # Or with token
   export MCP_GITHUB_TOKEN=your_token
   mcp-publisher login github
   ```

3. **Publish**
   ```bash
   mcp-publisher publish
   ```

## Docker Executable Configuration

Jib auto-detects Docker based on your operating system:

- **macOS**: `/usr/local/bin/docker`
- **Linux**: `/usr/bin/docker`
- **Windows**: `C:\Program Files\Docker\Docker\resources\bin\docker.exe`

Override if needed:
```bash
export DOCKER_EXECUTABLE=/custom/path/to/docker
./gradlew jibDockerBuild
```

Or in `gradle.properties`:
```properties
systemProp.DOCKER_EXECUTABLE=/custom/path/to/docker
```

## Production Deployment

### Using Docker Compose

Example `compose.yaml`:

```yaml
version: '3.8'

services:
  solr:
    image: solr:9.9-slim
    ports:
      - "8983:8983"
    volumes:
      - solr_data:/var/solr

  solr-mcp:
    image: ghcr.io/apache/solr-mcp:latest
    environment:
      - SOLR_URL=http://solr:8983/solr/
      - PROFILES=http
    ports:
      - "8080:8080"
    depends_on:
      - solr

volumes:
  solr_data:
```

### Using Kubernetes

Example deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: solr-mcp
spec:
  replicas: 2
  selector:
    matchLabels:
      app: solr-mcp
  template:
    metadata:
      labels:
        app: solr-mcp
    spec:
      containers:
      - name: solr-mcp
        image: ghcr.io/apache/solr-mcp:latest
        env:
        - name: SOLR_URL
          value: "http://solr-service:8983/solr/"
        - name: PROFILES
          value: "http"
        ports:
        - containerPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: solr-mcp
spec:
  selector:
    app: solr-mcp
  ports:
  - port: 8080
    targetPort: 8080
```

## Security Considerations

### STDIO Transport
- Recommended for local deployments
- No network exposure
- OS-level process isolation
- Secure by default

### HTTP Transport
⚠️ **Warning**: HTTP mode is insecure without additional measures.

**Production requirements:**
1. **Use HTTPS** with TLS/SSL certificates
2. **Implement OAuth2** authentication (see [Spring AI MCP OAuth2 guide](https://spring.io/blog/2025/04/02/mcp-server-oauth2/))
3. **Validate origin headers** to prevent DNS rebinding
4. **Network isolation** via firewall/VPN
5. **API Gateway** with rate limiting

**Recommendation:**
- Local development: HTTP on localhost only
- Claude Desktop: Always use STDIO
- Production remote: HTTP + OAuth2 + HTTPS + proper network security

## Monitoring

### Health Checks

The server exposes Spring Boot Actuator endpoints:

```bash
# Health check
curl http://localhost:8080/actuator/health

# Build info
curl http://localhost:8080/actuator/info
```

### Docker Health Check

Add to Dockerfile or compose.yaml:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
```

## Backup and Recovery

### Solr Data

Backup Solr collections regularly:
```bash
# Using Solr API
curl "http://localhost:8983/solr/admin/collections?action=BACKUP&name=backup1&collection=books&location=/backup"
```

### Configuration

Version control all configuration files:
- `application.properties`
- `server.json`
- Docker Compose files
- Kubernetes manifests
