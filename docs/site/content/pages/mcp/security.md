Title: Security
URL: mcp/security.html
save_as: mcp/security.html
template: mcp/security

## Overview ##

When running in HTTP mode, the Solr MCP Server supports **OAuth2 authentication** with JWT token validation. Security is **disabled by default** and must be explicitly enabled.

* **Protocol**: OAuth2 Resource Server with JWT validation
* **Supported providers**: Auth0, Keycloak, Okta, or any OAuth2/OIDC provider
* **STDIO mode**: Security is not applicable (OS-level process isolation)
* **HTTP mode**: Optional, enabled with `SECURITY_ENABLED=true`

### Enable Security ###

```bash
export PROFILES=http
export SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=https://your-provider.example.com/
./gradlew bootRun
```

Or with Docker (local image &mdash; build first with `./gradlew jibDockerBuild`):

```bash
docker run -p 8080:8080 --rm \
    -e PROFILES=http \
    -e SECURITY_ENABLED=true \
    -e OAUTH2_ISSUER_URI=https://your-provider.example.com/ \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

***

## Auth0 ##

### 1. Create Auth0 Application ###

1. Go to [Auth0 Dashboard](https://manage.auth0.com/) > **Applications** > **Create Application**
2. Name: `Solr MCP Server`
3. Type: **Machine to Machine Applications**
4. Note your **Domain**, **Client ID**, and **Client Secret**

### 2. Create Auth0 API ###

1. Navigate to **Applications** > **APIs** > **Create API**
2. Name: `Solr MCP API`
3. Identifier (audience): `https://solr-mcp-api`
4. Signing Algorithm: **RS256**

### 3. Configure Callback URLs ###

In your application settings, add to **Allowed Callback URLs**:

    http://localhost:6274/oauth/callback,http://localhost:3334/oauth/callback,http://localhost:8080/login/oauth2/code/auth0

Each callback URL serves a different client:

* `http://localhost:6274/oauth/callback` &mdash; MCP Inspector
* `http://localhost:3334/oauth/callback` &mdash; `mcp-remote` (Claude Desktop, VS Code, Cursor, JetBrains in HTTP mode)
* `http://localhost:8080/login/oauth2/code/auth0` &mdash; Direct server OAuth2 code flow

### 4. Run the Server ###

```bash
export PROFILES=http
export SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=https://your-tenant.auth0.com/
./gradlew bootRun
```

### 5. Get an Access Token ###

```bash
curl --request POST \
    --url https://your-tenant.auth0.com/oauth/token \
    --header 'content-type: application/json' \
    --data '{
      "client_id": "YOUR_CLIENT_ID",
      "client_secret": "YOUR_CLIENT_SECRET",
      "audience": "https://solr-mcp-api",
      "grant_type": "client_credentials"
    }'
```

Or use the convenience script:

```bash
./scripts/get-auth0-token.sh \
    --domain your-tenant.auth0.com \
    --client-id YOUR_CLIENT_ID \
    --client-secret YOUR_CLIENT_SECRET \
    --audience https://solr-mcp-api
```

### 6. Use the Token ###

```bash
curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
    http://localhost:8080/mcp
```

For the full step-by-step guide, see [Auth0 Setup Guide](https://github.com/apache/solr-mcp/blob/main/docs/development/AUTH0_SETUP.md).

***

## Keycloak ##

### 1. Start Keycloak ###

```bash
docker run -d --name keycloak \
    -p 8180:8080 \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    quay.io/keycloak/keycloak:26.0 start-dev
```

Access the admin console at `http://localhost:8180` (login: `admin` / `admin`).

### 2. Create Realm and Client ###

1. Create realm: `solr-mcp`
2. Create client:
    * Client ID: `solr-mcp-client`
    * Client type: OpenID Connect
    * Client authentication: OFF (public client)
    * Valid redirect URIs: `http://localhost:6274/*`, `http://localhost:3334/*`, `http://localhost:8080/*`
    * Web origins: `*`

### 3. Create Test User ###

1. Navigate to **Users** > **Add user**
2. Username: `testuser`, Email verified: ON
3. Set password in **Credentials** tab

### 4. Run the Server ###

```bash
export PROFILES=http
export SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

### 5. Get a Token ###

```bash
curl -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "client_id=solr-mcp-client" \
    -d "username=testuser" \
    -d "password=yourpassword" \
    -d "grant_type=password"
```

For the full guide including role-based access control and production deployment, see [Keycloak Setup Guide](https://github.com/apache/solr-mcp/blob/main/docs/development/keycloak.md).

***

## How OAuth2 Works with MCP Clients ##

When a client connects to a secured Solr MCP Server:

1. Client connects to `/mcp`
2. Server responds with `401` + OAuth2 metadata
3. Client discovers the authorization server from `/.well-known/oauth-authorization-server`
4. Client opens a browser for login/consent
5. Client receives an authorization code, exchanges it for an access token (JWT)
6. Client attaches the Bearer token to all subsequent MCP requests
7. Server validates the JWT with the OAuth2 provider

Most MCP clients handle this flow transparently&mdash;the configuration is the same for secured and unsecured HTTP servers. See the individual [client setup pages](/mcp/clients/claude-desktop.html) for details.
