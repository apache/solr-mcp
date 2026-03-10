# Keycloak Authentication Guide for Solr MCP Server

This guide covers setting up [Keycloak](https://www.keycloak.org/) as an OAuth2/OpenID Connect identity provider for the Solr MCP Server running in HTTP mode.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Keycloak Setup](#keycloak-setup)
  - [Running Keycloak](#running-keycloak)
  - [Creating a Realm](#creating-a-realm)
  - [Creating Clients](#creating-clients)
  - [Creating Test Users](#creating-test-users)
- [Spring Boot Configuration](#spring-boot-configuration)
- [Running the Server](#running-the-server)
- [Testing Authentication](#testing-authentication)
- [User Management Options](#user-management-options)
  - [Manual User Creation](#manual-user-creation)
  - [User Federation (LDAP/AD)](#user-federation-ldapad)
  - [Identity Brokering (GitHub, Google, etc.)](#identity-brokering-github-google-etc)
  - [Self-Registration](#self-registration)
  - [REST API](#rest-api)
  - [JSON Import](#json-import)
  - [Custom User Storage SPI](#custom-user-storage-spi)
- [GitHub Identity Provider Setup](#github-identity-provider-setup)
- [Role-Based Access Control (Optional)](#role-based-access-control-optional)
- [Troubleshooting](#troubleshooting)

## Overview

The Solr MCP Server supports OAuth2 authentication via JWT tokens. Keycloak acts as the authorization server, issuing tokens that the MCP server validates. This enables:

- Centralized user management
- Single Sign-On (SSO) across multiple applications
- Integration with external identity providers (GitHub, Google, LDAP, etc.)
- Fine-grained role-based access control

### Authentication Flow

```
User                    MCP Client            Keycloak              Solr MCP Server
  │                        │                     │                        │
  │─── Request Access ────►│                     │                        │
  │                        │─── Auth Request ───►│                        │
  │◄────────────────────── Login Page ◄──────────│                        │
  │─── Credentials ───────────────────────────────►                       │
  │                        │◄─── JWT Token ──────│                        │
  │                        │─── API Request + Token ────────────────────►│
  │                        │                     │      (validates JWT)   │
  │                        │◄─────────────────────────── Response ────────│
  │◄─── Result ────────────│                     │                        │
```

## Prerequisites

- Docker (for running Keycloak)
- Java 25 (for Solr MCP Server)
- Gradle (for building the server)

## Quick Start

```bash
# 1. Start Keycloak
docker run -d --name keycloak \
  -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev

# 2. Configure Keycloak (see detailed steps below)
# - Create realm: solr-mcp
# - Create client: solr-mcp-client

# 3. Run Solr MCP Server with security enabled
export PROFILES=http
export SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

## Keycloak Setup

### Running Keycloak

**Development Mode (Docker):**

```bash
docker run -d --name keycloak \
  -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev
```

Access the admin console at `http://localhost:8180` and log in with `admin/admin`.

**Production Mode:**

For production deployments, use a proper database backend and TLS:

```bash
docker run -d --name keycloak \
  -p 8443:8443 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=<secure-password> \
  -e KC_DB=postgres \
  -e KC_DB_URL=jdbc:postgresql://db-host:5432/keycloak \
  -e KC_DB_USERNAME=keycloak \
  -e KC_DB_PASSWORD=<db-password> \
  -e KC_HOSTNAME=keycloak.example.com \
  quay.io/keycloak/keycloak:26.0 start
```

### Creating a Realm

1. Log into Keycloak Admin Console: `http://localhost:8180/admin`
2. Click the dropdown in the top-left (shows "master")
3. Click **Create realm**
4. Realm name: `solr-mcp`
5. Click **Create**

### Creating Clients

You need at least one client for applications to authenticate against.

#### Resource Server Client (for the MCP Server)

1. Navigate to **Clients** → **Create client**
2. Configure:
   - Client ID: `solr-mcp-server`
   - Client type: `OpenID Connect`
3. Click **Next**
4. Client authentication: **ON** (confidential client)
5. Authentication flow: Enable **Service accounts roles**
6. Click **Next** → **Save**

#### Public Client (for testing/MCP Inspector)

1. Navigate to **Clients** → **Create client**
2. Configure:
   - Client ID: `solr-mcp-client`
   - Client type: `OpenID Connect`
3. Click **Next**
4. Client authentication: **OFF** (public client)
5. Click **Next**
6. Configure access settings:
   - Valid redirect URIs: `http://localhost:6274/*`, `http://localhost:*`
   - Web origins: `*` or `http://localhost:6274`
7. Click **Save**

### Creating Test Users

1. Navigate to **Users** → **Add user**
2. Configure:
   - Username: `testuser`
   - Email: `test@example.com`
   - Email verified: **ON**
3. Click **Create**
4. Go to the **Credentials** tab
5. Click **Set password**
6. Enter password and disable **Temporary**
7. Click **Save**

## Spring Boot Configuration

The Solr MCP Server is pre-configured to work with any OAuth2/OIDC provider. Update `application-http.properties`:

```properties
# Security toggle - set to true to enable OAuth2 authentication
spring.security.enabled=${SECURITY_ENABLED:true}

# Keycloak OAuth2 Configuration
# Format: https://<keycloak-host>/realms/<realm-name>
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OAUTH2_ISSUER_URI:http://localhost:8180/realms/solr-mcp}
```

No code changes are required—the existing `McpServerConfiguration` handles JWT validation automatically by discovering Keycloak's JWKS endpoint from the issuer URI.

## Running the Server

**With Security Enabled:**

```bash
export PROFILES=http
export SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

**Without Security (Development Only):**

```bash
export PROFILES=http
export SECURITY_ENABLED=false
./gradlew bootRun
```

## Testing Authentication

### Obtain a Token

**Using Resource Owner Password Grant (for testing):**

```bash
curl -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=solr-mcp-client" \
  -d "username=testuser" \
  -d "password=yourpassword" \
  -d "grant_type=password"
```

Response:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 300,
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer"
}
```

### Call the MCP Server

```bash
# Store token in variable
TOKEN=$(curl -s -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -d "client_id=solr-mcp-client" \
  -d "username=testuser" \
  -d "password=yourpassword" \
  -d "grant_type=password" | jq -r '.access_token')

# Call MCP endpoint
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## User Management Options

Keycloak provides multiple ways to manage users beyond manual creation.

### Manual User Creation

As described above, create users via **Users** → **Add user** in the admin console.


### Identity Brokering (GitHub, Google, etc.)

Allow users to authenticate via external identity providers. See [GitHub Identity Provider Setup](#github-identity-provider-setup) for a detailed example.

Supported providers include:
- GitHub
- Google
- Microsoft/Azure AD
- Facebook
- Twitter/X
- Any SAML 2.0 or OIDC provider

### Self-Registration

Allow users to create their own accounts:

1. Navigate to **Realm settings** → **Login** tab
2. Enable **User registration**
3. Optionally enable:
   - Email verification
   - Terms and conditions
   - reCAPTCHA

### REST API

Programmatically create users:

```bash
# Get admin token
TOKEN=$(curl -s -X POST "http://localhost:8180/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" | jq -r '.access_token')

# Create user
curl -X POST "http://localhost:8180/admin/realms/solr-mcp/users" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "newuser",
    "email": "newuser@example.com",
    "enabled": true,
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "value": "temppassword",
      "temporary": true
    }]
  }'
```

### JSON Import

Bulk import users during realm setup:

```json
{
  "realm": "solr-mcp",
  "users": [
    {
      "username": "user1",
      "email": "user1@example.com",
      "enabled": true,
      "credentials": [{"type": "password", "value": "pass123"}],
      "realmRoles": ["user", "solr-query"]
    },
    {
      "username": "user2",
      "email": "user2@example.com",
      "enabled": true,
      "credentials": [{"type": "password", "value": "pass456"}],
      "realmRoles": ["admin"]
    }
  ]
}
```

Import via CLI:

```bash
/opt/keycloak/bin/kc.sh import --file realm-export.json
```

### Custom User Storage SPI

For databases or custom backends, implement Keycloak's User Storage SPI to authenticate against your existing user database without migration.

## GitHub Identity Provider Setup

### Step 1: Create GitHub OAuth App

1. Go to GitHub → **Settings** → **Developer settings** → **OAuth Apps** → **New OAuth App**

   Direct link: https://github.com/settings/applications/new

2. Fill in the form:

| Field | Value |
|-------|-------|
| Application name | `Solr MCP Server` |
| Homepage URL | `http://localhost:8180` |
| Authorization callback URL | `http://localhost:8180/realms/solr-mcp/broker/github/endpoint` |

> **Note:** The callback URL format is: `https://<keycloak-host>/realms/<realm-name>/broker/github/endpoint`

3. Click **Register application**
4. Note the **Client ID** and generate a **Client Secret**

### Step 2: Configure Keycloak

1. Log into Keycloak Admin Console
2. Select your realm (`solr-mcp`)
3. Navigate to **Identity Providers** → **Add provider** → **GitHub**
4. Configure:

| Field | Value |
|-------|-------|
| Client ID | `<from GitHub>` |
| Client Secret | `<from GitHub>` |
| Default Scopes | `user:email` (optional) |

5. Click **Save**

### Step 3: Test GitHub Login

1. Open: `http://localhost:8180/realms/solr-mcp/account`
2. Click the **GitHub** button
3. Authorize on GitHub
4. You're logged into Keycloak with your GitHub account

### Optional: Map GitHub Data

Add mappers to import GitHub profile data:

1. Navigate to **Identity Providers** → **GitHub** → **Mappers**
2. Click **Add mapper**
3. Example configurations:

| Name | Mapper Type | Claim | User Attribute |
|------|-------------|-------|----------------|
| GitHub Username | Attribute Importer | `login` | `github_username` |
| GitHub Avatar | Attribute Importer | `avatar_url` | `avatar` |
| GitHub Email | Attribute Importer | `email` | `email` |

### Optional: Auto-Assign Roles

Automatically assign roles to GitHub users:

1. Navigate to **Identity Providers** → **GitHub** → **Mappers**
2. Click **Add mapper**
3. Configure:
   - Mapper Type: `Hardcoded Role`
   - Role: Select a role (e.g., `solr-query`)

## Role-Based Access Control (Optional)

To use Keycloak roles with Spring Security's `@PreAuthorize` annotations, add a JWT converter.

### Create Roles in Keycloak

1. Navigate to **Realm roles** → **Create role**
2. Create roles like `admin`, `solr-query`, `solr-admin`
3. Assign roles to users via **Users** → select user → **Role mappings**

### Configure Spring Security

Add to `McpServerConfiguration.java`:

```java
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Bean
@ConditionalOnProperty(name = "spring.security.enabled", havingValue = "true", matchIfMissing = true)
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    // Keycloak stores realm roles in realm_access.roles
    grantedAuthoritiesConverter.setAuthoritiesClaimName("realm_access.roles");
    grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

    JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
    jwtConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
    return jwtConverter;
}
```

Wire it into the security filter chain:

```java
@Bean
@ConditionalOnProperty(name = "spring.security.enabled", havingValue = "true", matchIfMissing = true)
SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
    return http
        .authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator").permitAll();
            auth.requestMatchers("/actuator/*").permitAll();
            auth.requestMatchers("/mcp").permitAll();
            auth.anyRequest().authenticated();
        })
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
        )
        .with(McpServerOAuth2Configurer.mcpServerOAuth2(), (mcpAuthorization) -> {
            mcpAuthorization.authorizationServer(issuerUrl);
        })
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(CsrfConfigurer::disable)
        .build();
}
```

### Use Role Annotations

```java
@PreAuthorize("hasRole('admin')")
public String deleteCollection(String name) { ... }

@PreAuthorize("hasRole('solr-query')")
public String executeQuery(String collection, String query) { ... }

@PreAuthorize("hasAnyRole('solr-query', 'solr-admin')")
public String getSchema(String collection) { ... }
```

### Keycloak Role Locations

| Location | Claim Path | Use Case |
|----------|------------|----------|
| Realm roles | `realm_access.roles` | Global roles across all clients |
| Client roles | `resource_access.<client-id>.roles` | Roles specific to a client |

## Troubleshooting

### Common Issues

**"Invalid token" or 401 Unauthorized:**

- Verify `OAUTH2_ISSUER_URI` matches your Keycloak realm URL exactly
- Check that the token hasn't expired
- Ensure Keycloak is accessible from the MCP server

**"Unable to resolve issuer":**

- Keycloak must be running and accessible
- Check the issuer URL: `http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration`

**CORS errors with MCP Inspector:**

- Ensure Web origins are configured in your Keycloak client
- Add `http://localhost:6274` to Web origins

**Token doesn't contain roles:**

- Verify the user has roles assigned in Keycloak
- Check the token contents at https://jwt.io
- Ensure you're reading the correct claim path (`realm_access.roles`)

### Useful Commands

**Inspect a JWT token:**

```bash
# Decode token (without verification)
echo $TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq
```

**Check Keycloak OpenID configuration:**

```bash
curl http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration | jq
```

**View Keycloak logs:**

```bash
docker logs -f keycloak
```

## References

- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [MCP Specification](https://spec.modelcontextprotocol.io/)
