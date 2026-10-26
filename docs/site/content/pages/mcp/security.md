Title: Security
URL: mcp/security.html
save_as: mcp/security.html
template: mcp/security

## Overview ##

When running in HTTP mode, the Solr MCP Server supports **OAuth2 authentication** with JWT token validation. Security is **enabled by default** in HTTP mode &mdash; point it at an OAuth2 issuer to use it, or disable it for local development.

* **Protocol**: OAuth2 Resource Server with JWT validation
* **Supported providers**: Auth0, Keycloak, Okta, or any OAuth2/OIDC provider
* **STDIO mode**: Security is not applicable (OS-level process isolation)
* **HTTP mode**: **Secured by default** &mdash; set `OAUTH2_ISSUER_URI` to wire up a provider; disable for local dev only with `HTTP_SECURITY_ENABLED=false`

### Configure Security ###

HTTP mode is secured by default &mdash; you only need to point it at an OAuth2 issuer:

```bash
export PROFILES=http
export OAUTH2_ISSUER_URI=https://your-provider.example.com/
./gradlew bootRun
```

Or with Docker (local image &mdash; build first with `./gradlew jibDockerBuild`):

```bash
docker run -p 8080:8080 --rm \
    -e PROFILES=http \
    -e OAUTH2_ISSUER_URI=https://your-provider.example.com/ \
    -e SOLR_URL=http://host.docker.internal:8983/solr/ \
    solr-mcp:latest
```

To turn authentication **off** for local development, set `HTTP_SECURITY_ENABLED=false`.

***

## Before you start: the audience ##

The server validates the token's `aud` claim against its **resource URI**, which it derives from the URL the client calls &mdash; scheme, host and port plus `/mcp`. For a local trial that is `http://localhost:8080/mcp`; for a deployed server it is the public URL. Read it from a running server:

```bash
curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq -r .resource
# http://localhost:8080/mcp
```

Every provider setup below has one step whose only job is to put that value into `aud`. Skip it and every token is rejected with `401 ... "The aud claim is not valid"`.

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
3. Identifier (audience): `http://localhost:8080/mcp` &mdash; the server's resource URI, exactly as clients will dial it
4. Signing Algorithm: **RS256**

### 3. Authorize the Application ###

In your application's **APIs** tab, toggle **Solr MCP API** on. No callback URLs are needed: the MCP server is a resource server and a machine-to-machine application never opens a browser.

### 4. Run the Server ###

```bash
PROFILES=http OAUTH2_ISSUER_URI=https://your-tenant.auth0.com/ ./gradlew bootRun
```

The issuer must end with `/`, as Auth0 writes the `iss` claim. It is resolved at startup, so a wrong domain fails immediately.

### 5. Get an Access Token ###

```bash
curl --request POST \
    --url https://your-tenant.auth0.com/oauth/token \
    --header 'content-type: application/json' \
    --data '{
      "client_id": "YOUR_CLIENT_ID",
      "client_secret": "YOUR_CLIENT_SECRET",
      "audience": "http://localhost:8080/mcp",
      "grant_type": "client_credentials"
    }'
```

Or use the convenience script:

```bash
./scripts/get-auth0-token.sh \
    --domain your-tenant.auth0.com \
    --client-id YOUR_CLIENT_ID \
    --client-secret YOUR_CLIENT_SECRET \
    --audience http://localhost:8080/mcp
```

For the full step-by-step guide, see [Auth0 Setup Guide](https://github.com/apache/solr-mcp/blob/main/docs/security/auth0.md).

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

Access the admin console at `http://localhost:8180` (login: `admin` / `admin`). Wait for `http://localhost:8180/realms/master/.well-known/openid-configuration` to answer before continuing; the container is up well before its endpoints are.

### 2. Create Realm and Client ###

1. Create realm: `solr-mcp`
2. Create client:
    * Client ID: `solr-mcp-client`
    * Client type: OpenID Connect
    * Client authentication: OFF (public client)
    * Authentication flow: leave **Direct access grants** enabled (the token request below uses it)
    * Valid redirect URIs: `http://localhost:6274/*`
    * Web origins: `http://localhost:6274`

### 3. Add the Audience Mapper ###

Keycloak does not honour the RFC 8707 `resource=` parameter, so the audience must be mapped in:

1. **Clients** > `solr-mcp-client` > **Client scopes** > `solr-mcp-client-dedicated`
2. **Add mapper** > **By configuration** > **Audience**
3. Name `mcp-audience`, **Included Custom Audience** `http://localhost:8080/mcp`, **Add to access token** ON
4. **Save**

### 4. Create Test User ###

1. Navigate to **Users** > **Add user**
2. Username: `testuser`, Email verified: ON, **First name and Last name filled in** (Keycloak refuses to issue a token to a user missing either)
3. Set password in **Credentials** tab with **Temporary** OFF

### 5. Run the Server ###

```bash
PROFILES=http OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp ./gradlew bootRun
```

The realm must exist before this: the issuer is resolved at startup.

### 6. Get a Token ###

```bash
curl -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "client_id=solr-mcp-client" \
    -d "username=testuser" \
    -d "password=yourpassword" \
    -d "grant_type=password"
```

Tokens last 300 seconds by default. The full guide shows how to raise that for a trial, and covers scripted realm setup, role-based access control and production deployment: [Keycloak Setup Guide](https://github.com/apache/solr-mcp/blob/main/docs/security/keycloak.md).

***

## Verify ##

An unauthenticated tool call is denied inside the tool; the same call with the token returns data:

```bash
curl -s -X POST http://localhost:8080/mcp \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"list-collections","arguments":{}}}'
# with the header:    {"content":[{"type":"text","text":"[\"books\",\"films\"]"}],"isError":false}
# without the header: {"content":[{"type":"text","text":"Access Denied"}],"isError":true}
```

***

## Connecting an MCP Client ##

The server does not answer an anonymous `/mcp` request with `401` &mdash; `/mcp` is permitted at the HTTP layer and the denial happens inside each tool &mdash; and MCP clients start their OAuth flow only on a `401`. A client configured with just the URL therefore shows the server as connected, lists every tool, and gets `Access Denied` from every call; no browser opens. Pass the token explicitly:

```bash
# Claude Code
claude mcp add --transport http solr-mcp http://localhost:8080/mcp \
    --header "Authorization: Bearer $TOKEN"

# MCP Inspector
npx @modelcontextprotocol/inspector --server-url http://localhost:8080/mcp --transport http \
    --header "Authorization: Bearer $TOKEN"
```

VS Code and Cursor take a `headers` object on the server entry; Claude Desktop and JetBrains go through `mcp-remote --header`. Each [client page](/mcp/clients/claude-code.html) shows the snippet. The token's `aud` must contain the exact URL the client dials, and when it expires the client reports a failed connection until you configure a fresh one.
