# Troubleshooting Guide

Common issues and solutions when working with the Solr MCP Server.

## Solr Connection Issues

### Cannot connect to Solr

**Symptoms:**
- Connection refused errors
- UnknownHostException
- Timeout errors

**Solutions:**

1. **Verify Solr is running**
   ```bash
   docker-compose ps
   # or
   curl http://localhost:8983/solr/admin/info/system
   ```

2. **Check SOLR_URL environment variable**
   ```bash
   # Should point to your Solr instance
   export SOLR_URL=http://localhost:8983/solr/
   ```

3. **Verify network connectivity**
   ```bash
   # Test from the MCP server container
   docker run --rm curlimages/curl:latest \
     curl -v http://host.docker.internal:8983/solr/admin/info/system
   ```

4. **Linux users: Add host networking**
   ```bash
   docker run -i --rm \
     --add-host=host.docker.internal:host-gateway \
     solr-mcp:0.0.1-SNAPSHOT
   ```

### Collection not found

**Symptoms:**
- "Collection not found" errors
- 404 responses from Solr

**Solutions:**

1. **List available collections**
   ```bash
   curl "http://localhost:8983/solr/admin/collections?action=LIST"
   ```

2. **Create missing collection**
   ```bash
   docker-compose up -d  # Creates sample collections
   # or manually:
   curl "http://localhost:8983/solr/admin/collections?action=CREATE&name=books&numShards=1"
   ```

## Docker Build Issues

### Cannot find docker executable

**Symptoms:**
```
Cannot run program "docker": error=2, No such file or directory
```

**Solutions:**

1. **Verify Docker is installed**
   ```bash
   which docker
   docker --version
   ```

2. **Set DOCKER_EXECUTABLE environment variable**
   ```bash
   export DOCKER_EXECUTABLE=/usr/local/bin/docker
   ./gradlew jibDockerBuild
   ```

3. **macOS**: Check Docker Desktop is running
4. **Linux**: Ensure docker is in PATH

### Jib authentication errors

**Symptoms:**
- "Unauthorized" when pushing to registry
- "Access denied" errors

**Solutions:**

1. **Docker Hub**
   ```bash
   docker login
   # Enter username and password
   ```

2. **GitHub Container Registry**
   ```bash
   export GITHUB_TOKEN=your_token
   echo $GITHUB_TOKEN | docker login ghcr.io -u YOUR_USERNAME --password-stdin
   ```

3. **Check credentials in ~/.docker/config.json**

### Multi-platform build issues

**Symptoms:**
- Platform mismatch errors
- "no matching manifest" errors

**Solution:**
Jib automatically builds for the local platform or the first specified platform. For multi-platform publishing, use separate builds or Docker Buildx.

## Claude Desktop Integration

### Server not appearing in Claude Desktop

**Symptoms:**
- MCP server doesn't show up in Claude Desktop
- No tools available

**Solutions:**

1. **Verify configuration file location**
   - macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - Windows: `%APPDATA%\Claude\claude_desktop_config.json`

2. **Check JSON syntax**
   ```bash
   # Validate JSON
   cat ~/Library/Application\ Support/Claude/claude_desktop_config.json | jq .
   ```

3. **Verify JAR path is absolute**
   ```json
   {
     "mcpServers": {
       "solr-search-mcp": {
         "command": "java",
         "args": ["-jar", "/absolute/path/to/solr-mcp-0.0.1-SNAPSHOT.jar"]
       }
     }
   }
   ```

4. **Check logs**
   - macOS: `~/Library/Logs/Claude/`
   - Windows: `%APPDATA%\Claude\logs\`

5. **Restart Claude Desktop** after configuration changes

### Tools not working / timeout errors

**Symptoms:**
- Tool calls timeout
- No response from server
- Connection errors

**Solutions:**

1. **Test server manually**
   ```bash
   # STDIO mode - should start without errors
   java -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar

   # HTTP mode
   curl http://localhost:8080/actuator/health
   ```

2. **Check environment variables**
   ```json
   {
     "mcpServers": {
       "solr-search-mcp": {
         "env": {
           "SOLR_URL": "http://localhost:8983/solr/"
         }
       }
     }
   }
   ```

3. **Verify Solr is accessible** from the MCP server

### STDIO output corruption

**Symptoms:**
- Garbled output
- Protocol errors
- JSON parse errors

**Solutions:**

1. **Ensure clean stdout** - No debug logging to stdout
2. **Check for System.out.println()** in code
3. **Redirect logging to file** instead of stdout
4. **Use Docker image** built with Jib (clean stdout guaranteed)

## Build and Test Issues

### Tests failing

**Symptoms:**
- Test failures during `./gradlew build`
- Integration test errors

**Solutions:**

1. **Ensure Solr is running** for integration tests
   ```bash
   docker-compose up -d
   ```

2. **Check Docker is available** for Docker integration tests
   ```bash
   docker info
   ```

3. **Run specific test** to isolate issue
   ```bash
   ./gradlew test --tests SearchServiceTest
   ```

4. **Check test logs** in `build/reports/tests/test/index.html`

5. **Clean and rebuild**
   ```bash
   ./gradlew clean build
   ```

### Spotless formatting errors

**Symptoms:**
```
Task :spotlessJavaCheck FAILED
The following files had format violations:
```

**Solution:**
```bash
./gradlew spotlessApply
```

### Gradle build cache issues

**Symptoms:**
- Stale build outputs
- Unexpected behavior after changes

**Solution:**
```bash
# Clean Gradle cache
./gradlew clean --no-build-cache

# Nuclear option: delete .gradle directory
rm -rf .gradle
./gradlew clean build
```

## MCP Inspector Issues

### Cannot connect to HTTP server

**Symptoms:**
- MCP Inspector shows connection errors
- CORS errors in browser console

**Solutions:**

1. **Verify server is running in HTTP mode**
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=http'
   ```

2. **Check port is correct**
   ```bash
   # Should return health status
   curl http://localhost:8080/actuator/health
   ```

3. **Configure MCP endpoint** in Inspector
   - URL: `http://localhost:8080/mcp`
   - Transport: Streamable HTTP

## Performance Issues

### Slow responses

**Symptoms:**
- Slow tool execution
- Timeouts

**Solutions:**

1. **Check Solr performance**
   ```bash
   curl "http://localhost:8983/solr/admin/metrics"
   ```

2. **Optimize Solr queries**
   - Add filter queries
   - Limit rows returned
   - Use appropriate fields

3. **Check resource usage**
   ```bash
   # Docker stats
   docker stats

   # Java heap
   jstat -gc <pid>
   ```

4. **Increase timeouts** if needed

### Memory issues

**Symptoms:**
- OutOfMemoryError
- Container crashes

**Solutions:**

1. **Increase JVM heap**
   ```bash
   export JAVA_OPTS="-Xmx2g -Xms512m"
   java $JAVA_OPTS -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
   ```

2. **Docker container limits**
   ```bash
   docker run -m 2g --rm solr-mcp:0.0.1-SNAPSHOT
   ```

3. **Check for memory leaks**
   - Use Java Flight Recorder
   - Analyze heap dumps

## Common Error Messages

### "build-info.properties not found"

**Cause:** Project not built with Gradle

**Solution:**
```bash
./gradlew build
```

### "Failed to read output of 'docker info'"

**Cause:** Docker not accessible to Gradle

**Solution:** See "Cannot find docker executable" above

### "No matching manifest for linux/amd64"

**Cause:** Docker image not available for your platform

**Solution:**
```bash
# Build for your platform
./gradlew jibDockerBuild
```

### "Port 8080 already in use"

**Cause:** Another service using port 8080

**Solution:**
```bash
# Find and kill process using port 8080
lsof -ti:8080 | xargs kill -9

# Or change port
java -Dserver.port=8081 -jar build/libs/solr-mcp-0.0.1-SNAPSHOT.jar
```

## Getting Help

If you're still having issues:

1. **Check existing issues**: https://github.com/apache/solr-mcp/issues
2. **Enable debug logging**:
   ```bash
   export LOGGING_LEVEL_ORG_APACHE_SOLR_MCP=DEBUG
   ./gradlew bootRun
   ```
3. **Collect information**:
   - Error messages and stack traces
   - Environment (OS, Java version, Docker version)
   - Steps to reproduce
   - Logs from server and Solr
4. **Open an issue** with collected information

## Logs and Diagnostics

### Server Logs

```bash
# STDIO mode - logs to stderr
./gradlew bootRun 2>&1 | tee server.log

# HTTP mode - Spring Boot logging
./gradlew bootRun --args='--spring.profiles.active=http'
```

### Solr Logs

```bash
# Docker Compose
docker-compose logs solr

# Follow logs
docker-compose logs -f solr
```

### Docker Logs

```bash
# Container logs
docker logs <container_id>

# Follow logs
docker logs -f <container_id>
```

### Actuator Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Build info
curl http://localhost:8080/actuator/info

# All endpoints
curl http://localhost:8080/actuator
```
