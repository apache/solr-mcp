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
  - [Configuring the Audience Claim](#configuring-the-audience-claim)
  - [Creating Test Users](#creating-test-users)
- [Spring Boot Configuration](#spring-boot-configuration)
- [Running the Server](#running-the-server)
- [Testing Authentication](#testing-authentication)
- [Configuring a Spring AI MCP Client](#configuring-a-spring-ai-mcp-client)
- [User Management Options](#user-management-options)
  - [Manual User Creation](#manual-user-creation)
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

### Two ordering constraints

Both of these will stop the server from starting if you get them wrong, so they
are worth internalising before you run anything:

1. **The realm must exist before `bootRun`.** The MCP server builds its
   `NimbusJwtDecoder` *eagerly*, during Spring context refresh, by fetching
   `$OAUTH2_ISSUER_URI/.well-known/openid-configuration`. If that URL does not
   resolve, the application context fails to refresh and the process exits.
   This is not a lazy, first-request failure.
2. **Keycloak needs ~20-40s to become ready.** `docker run -d` returns
   immediately; the realm endpoints do not exist yet. Poll before continuing.

The Quick Start below is ordered to satisfy both.

## Prerequisites

- Docker (for running Keycloak)
- Java 25 (for Solr MCP Server)
- Gradle (for building the server)
- `curl` and [`jq`](https://jqlang.github.io/jq/) (for the scripted setup below)

You do **not** need to start Solr yourself. `application-http.properties` sets
`spring.docker.compose.enabled=true`, so `./gradlew bootRun` starts the Solr,
ZooKeeper and LGTM containers from `compose.yaml` before the application
context comes up.

## Quick Start

This block is runnable end to end — copy the whole thing. It waits for
Keycloak, creates the realm, client, audience mapper and test user, verifies
the realm resolves, and only then starts the server.

```bash
# ── 1. Start Keycloak ────────────────────────────────────────────────────────
docker run -d --name keycloak \
  -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0 start-dev

KC=http://localhost:8180
# Must match the MCP server's canonical resource URI. Confirm it at runtime with
#   curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq -r .resource
MCP_AUDIENCE=http://localhost:8080/mcp

# Keycloak's container is up long before its HTTP endpoints are. Wait for them.
echo "Waiting for Keycloak..."
until curl -sf "$KC/realms/master/.well-known/openid-configuration" >/dev/null; do
  sleep 2
done
echo "Keycloak ready."

# ── 2. Configure the realm, client and user ──────────────────────────────────
ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d username=admin -d password=admin \
  -d grant_type=password | jq -r .access_token)

# 2a. Realm
curl -s -X POST "$KC/admin/realms" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"realm":"solr-mcp","enabled":true}'

# 2b. Public client for testing / MCP Inspector.
#
#     protocolMappers: Keycloak does not honour the RFC 8707 `resource=`
#     parameter yet, so an Audience protocol mapper is the supported way to put
#     the MCP server's resource URI into the token's `aud` claim. The MCP server
#     runs validateAudienceClaim(true) and rejects tokens without it.
#
#     directAccessGrantsEnabled: required by the password grant used in
#     "Testing Authentication". It defaults to true in the admin console but to
#     false over the Admin REST API, so it must be set explicitly here.
curl -s -X POST "$KC/admin/realms/solr-mcp/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{
        \"clientId\": \"solr-mcp-client\",
        \"publicClient\": true,
        \"directAccessGrantsEnabled\": true,
        \"redirectUris\": [\"http://localhost:6274/*\"],
        \"webOrigins\": [\"http://localhost:6274\"],
        \"protocolMappers\": [{
          \"name\": \"mcp-audience\",
          \"protocol\": \"openid-connect\",
          \"protocolMapper\": \"oidc-audience-mapper\",
          \"config\": {
            \"included.custom.audience\": \"$MCP_AUDIENCE\",
            \"access.token.claim\": \"true\",
            \"id.token.claim\": \"false\"
          }
        }]
      }"

# 2c. Test user.
#
#     firstName and lastName are REQUIRED. Keycloak 24+ enforces a declarative
#     user profile in which both are mandatory; a user created without them is
#     flagged VERIFY_PROFILE and every password grant fails with
#     "Account is not fully set up".
curl -s -X POST "$KC/admin/realms/solr-mcp/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{
        "username": "testuser",
        "email": "test@example.com",
        "firstName": "Test",
        "lastName": "User",
        "enabled": true,
        "emailVerified": true,
        "credentials": [
          {"type": "password", "value": "testpassword", "temporary": false}
        ]
      }'

# ── 3. Verify the realm resolves BEFORE starting the server ──────────────────
# The server fails to boot if this is not a 200. See "Two ordering constraints".
if curl -sf "$KC/realms/solr-mcp/.well-known/openid-configuration" >/dev/null; then
  echo "Realm ready."
else
  echo "Realm NOT ready — do not start the server yet."
fi

# ── 4. Run the Solr MCP Server ───────────────────────────────────────────────
export PROFILES=http
export HTTP_SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

To tear the whole thing down again:

```bash
docker rm -f keycloak
docker compose down
```

## Keycloak Setup

The Quick Start automates everything in this section. The steps below are the
equivalent admin-console walkthrough, for when you want to understand or adjust
what the script did.

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

Keycloak takes roughly 20-40 seconds to start serving. `docker run -d` returns
before that, so wait for the endpoint rather than assuming it is up:

```bash
until curl -sf http://localhost:8180/realms/master/.well-known/openid-configuration >/dev/null; do
  sleep 2
done
```

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

> **Create the realm before starting the MCP server.** The server resolves the
> issuer eagerly at startup and will not boot against a realm that does not
> exist. See [Troubleshooting](#troubleshooting).

### Creating Clients

You need at least one client for applications to authenticate against.

> **The MCP server itself does not need a Keycloak client.** It is a pure
> resource server: `HttpSecurityConfiguration` consumes only the issuer URI and
> validates tokens against the realm's JWKS endpoint. There is no client-id or
> client-secret anywhere in the server configuration. Create clients only for
> the *applications that call it*.

#### Public Client (for testing/MCP Inspector)

1. Navigate to **Clients** → **Create client**
2. Configure:
   - Client ID: `solr-mcp-client`
   - Client type: `OpenID Connect`
3. Click **Next**
4. Client authentication: **OFF** (public client)
5. Authentication flow: leave **Direct access grants** enabled — the password
   grant used in [Testing Authentication](#testing-authentication) needs it
6. Click **Next**
7. Configure access settings:
   - Valid redirect URIs: `http://localhost:6274/*`, `http://localhost:*`
   - Web origins: `http://localhost:6274`
8. Click **Save**

Then add the audience mapper described next — without it, tokens from this
client are rejected.

### Configuring the Audience Claim

The MCP server runs with `validateAudienceClaim(true)` and requires every token
to carry an `aud` claim matching its canonical resource URI. Confirm that URI
from the running server:

```bash
curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq -r .resource
# http://localhost:8080/mcp
```

Keycloak does not honour the RFC 8707 `resource=` parameter yet
([keycloak#41526](https://github.com/keycloak/keycloak/issues/41526)), so the
supported approach is an **Audience** protocol mapper:

1. Navigate to **Clients** → `solr-mcp-client` → **Client scopes**
2. Click the dedicated scope (`solr-mcp-client-dedicated`)
3. **Add mapper** → **By configuration** → **Audience**
4. Configure:

| Field | Value |
|-------|-------|
| Name | `mcp-audience` |
| Included Custom Audience | `http://localhost:8080/mcp` |
| Add to access token | **ON** |

5. Click **Save**

Verify the claim landed:

```bash
TOKEN=$(curl -s -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -d client_id=solr-mcp-client -d username=testuser \
  -d password=testpassword -d grant_type=password | jq -r .access_token)

jwt_payload() {
  python3 -c "import sys,base64,json;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);\
print(json.dumps(json.loads(base64.urlsafe_b64decode(p))))" "$1"
}
jwt_payload "$TOKEN" | jq .aud
# [ "http://localhost:8080/mcp", "account" ]
```

> Decode with `base64url` semantics and re-pad, as above. The common
> `cut -d. -f2 | base64 -d` one-liner fails on real Keycloak tokens — JWT
> payloads are unpadded base64**url**, so `base64 -d` errors out and, with
> `2>/dev/null`, silently feeds nothing to `jq`. The resulting
> `jq: parse error` looks like a malformed token when the token is fine.

If `aud` is only `"account"`, the mapper is not applied and every request will
fail with `401 ... "The aud claim is not valid"`.

See [`http.md`](./http.md#3-per-idp-setup-for-the-audience-claim) for the
equivalent setup on Auth0 and Okta.

### Creating Test Users

1. Navigate to **Users** → **Add user**
2. Configure:
   - Username: `testuser`
   - Email: `test@example.com`
   - Email verified: **ON**
   - First name: `Test`
   - Last name: `User`
3. Click **Create**
4. Go to the **Credentials** tab
5. Click **Set password**
6. Enter password and disable **Temporary**
7. Click **Save**

> **First and last name are required.** Keycloak's default user profile marks them
> mandatory, and a user missing either one cannot obtain a token at all — the token
> endpoint returns `{"error":"invalid_grant","error_description":"Account is not fully
> set up"}`, which does not obviously point at the missing name fields.
>
> **Step 6 matters for the same reason.** Leaving **Temporary** ON attaches an
> `UPDATE_PASSWORD` required action, which fails the token request with that same
> message. The [Troubleshooting](#troubleshooting) section shows how to tell the two
> apart.

## Spring Boot Configuration

The Solr MCP Server is pre-configured to work with any OAuth2/OIDC provider,
and **no file edits are required** — the environment variables in the Quick
Start are enough. For reference, these are the relevant entries in
`application-http.properties`:

```properties
# Security toggle — HTTP mode is secured by default.
http.security.enabled=${HTTP_SECURITY_ENABLED:true}

# OAuth2 issuer. Format for Keycloak: https://<keycloak-host>/realms/<realm-name>
# The default is deliberately EMPTY: with no issuer set, the filter chain still
# gates every non-permitAll endpoint (401/403) instead of crashing at startup on
# an unreachable placeholder URL.
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OAUTH2_ISSUER_URI:}
```

No code changes are required — `HttpSecurityConfiguration` handles JWT
validation by discovering Keycloak's JWKS endpoint from the issuer URI.

Note the consequence of that discovery being eager: once `OAUTH2_ISSUER_URI`
is set to a **non-empty** value, it must be resolvable at startup. An empty
value is safe; an unreachable one is fatal.

## Running the Server

**With Security Enabled:**

```bash
export PROFILES=http
export HTTP_SECURITY_ENABLED=true
export OAUTH2_ISSUER_URI=http://localhost:8180/realms/solr-mcp
./gradlew bootRun
```

**Without Security (Development Only):**

```bash
export PROFILES=http
export HTTP_SECURITY_ENABLED=false
./gradlew bootRun
```

> These `export`s persist in your shell and are inherited by the Gradle daemon.
> If a later `./gradlew build` behaves oddly, `unset` them and run
> `./gradlew --stop` first.

## Testing Authentication

### Obtain a Token

**Using Resource Owner Password Grant (for testing):**

```bash
curl -X POST "http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=solr-mcp-client" \
  -d "username=testuser" \
  -d "password=testpassword" \
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
  -d "password=testpassword" \
  -d "grant_type=password" | jq -r '.access_token')

# List the available tools
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

The `Accept` header is required — the MCP Streamable HTTP transport negotiates
between `application/json` and `text/event-stream`.

### Verify the Token Is Actually Being Validated

`/mcp` is `permitAll()` at the HTTP layer (authorization is enforced per tool),
so it is not the best probe for token validity. Use a protected actuator
endpoint instead:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/metrics
# 200  → token valid (signature, issuer, expiry and audience all check out)
# 401  → inspect the WWW-Authenticate header for the reason
```

To see *why* a token was rejected:

```bash
curl -s -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/metrics \
  | grep -i www-authenticate
```

## Configuring a Spring AI MCP Client

The Quick Start's `solr-mcp-client` is a *public* client driven by the password
grant — fine for `curl` and the MCP Inspector, wrong for an application. A
Spring AI app calling this server has no interactive user, so it should
authenticate as itself with the **client credentials** grant.

```
Spring AI client app          Keycloak                    Solr MCP Server
      │                          │                              │
      │── client_credentials ───►│                              │
      │◄──── JWT (aud=.../mcp) ──│                              │
      │                                                         │
      │── POST /mcp + Authorization: Bearer <jwt> ──────────────►│
      │                                        (JWKS validation) │
      │◄──────────────── tools/list │ tools/call ────────────────│
```

### 1. Register a confidential client in Keycloak

```bash
curl -s -X POST "$KC/admin/realms/solr-mcp/clients" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{
        \"clientId\": \"spring-ai-app\",
        \"publicClient\": false,
        \"serviceAccountsEnabled\": true,
        \"standardFlowEnabled\": false,
        \"protocolMappers\": [{
          \"name\": \"mcp-audience\",
          \"protocol\": \"openid-connect\",
          \"protocolMapper\": \"oidc-audience-mapper\",
          \"config\": {
            \"included.custom.audience\": \"$MCP_AUDIENCE\",
            \"access.token.claim\": \"true\",
            \"id.token.claim\": \"false\"
          }
        }]
      }"
```

The audience mapper is **not** optional here either — a service-account token
without it is rejected with `The aud claim is not valid`, exactly like a user
token. Retrieve the generated secret from **Clients** → `spring-ai-app` →
**Credentials**.

### 2. Configure the transport

Add `spring-ai-starter-mcp-client` to the client application, then:

```properties
spring.ai.mcp.client.streamable-http.connections.solr.url=http://localhost:8080
spring.ai.mcp.client.streamable-http.connections.solr.endpoint=/mcp

spring.security.oauth2.client.registration.solr-mcp.provider=keycloak
spring.security.oauth2.client.registration.solr-mcp.client-id=spring-ai-app
spring.security.oauth2.client.registration.solr-mcp.client-secret=${KC_CLIENT_SECRET}
spring.security.oauth2.client.registration.solr-mcp.authorization-grant-type=client_credentials
spring.security.oauth2.client.provider.keycloak.issuer-uri=http://localhost:8180/realms/solr-mcp
```

Two things that bite:

- `endpoint` already defaults to `/mcp`, so `url` must be the bare origin.
  Setting `url=http://localhost:8080/mcp` produces requests to `/mcp/mcp`.
- The prefix is `spring.ai.mcp.client.streamable-http`, **not** the older
  `spring.ai.mcp.client.sse`. SSE is the deprecated transport; this server
  speaks Streamable HTTP.

### 3. Attach the bearer token

There is no property for a bearer token. The supported hook is an
`McpSyncHttpClientRequestCustomizer` bean —
`StreamableHttpHttpClientTransportAutoConfiguration` takes one as an
`ObjectProvider`, so simply declaring the bean wires it in.

```java
@Bean
OAuth2AuthorizedClientManager authorizedClientManager(
        ClientRegistrationRepository registrations,
        OAuth2AuthorizedClientService clients) {
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
    manager.setAuthorizedClientProvider(
            OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    return manager;
}

@Bean
McpSyncHttpClientRequestCustomizer bearerTokenCustomizer(OAuth2AuthorizedClientManager manager) {
    return (builder, method, uri, body, context) -> {
        OAuth2AuthorizedClient client = manager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId("solr-mcp")
                        .principal("spring-ai-app")
                        .build());
        builder.header("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
    };
}
```

`AuthorizedClientServiceOAuth2AuthorizedClientManager` is the right manager for
a headless caller — the servlet-oriented `DefaultOAuth2AuthorizedClientManager`
expects an active `HttpServletRequest`. Calling `authorize(...)` per request
looks wasteful but is not: the manager caches the token and only re-fetches once
it is near expiry. Do not hand-roll a cache — Keycloak's default access-token
lifetime is 300s, and a naive cache without a refresh margin produces
intermittent 401s that are painful to diagnose.

If your Spring AI application is itself a resource server acting on behalf of an
end user, propagating that user's identity (via Keycloak token exchange) is what
the MCP authorization specification actually calls for, and preserves the
identity chain that client credentials collapses. That is a larger setup and is
not covered here.

### 4. Verify

A gated tool call is the end-to-end check — it exercises the token, the audience
claim and method security together:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":1,
       "params":{"name":"check-health","arguments":{"collection":"films"}}}'
```

A correctly configured client gets the tool result. Dropping the
`Authorization` header returns `{"content":[{"text":"Access Denied"}],"isError":true}`
— that contrast is what confirms the gate is live rather than silently open.

> If you see `Access Denied` *with* a token, check that `$TOKEN` is actually
> populated before blaming the server. An unset variable sends the literal
> header `Bearer null`, which is indistinguishable from sending nothing — and
> `jq -r .access_token` writes the string `null` into the variable when the
> token request itself failed.

## User Management Options

Keycloak provides multiple ways to manage users beyond manual creation.

### Manual User Creation

As described above, create users via **Users** → **Add user** in the admin
console. Remember to set first and last name.

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
    "firstName": "New",
    "lastName": "User",
    "enabled": true,
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "value": "temppassword",
      "temporary": true
    }]
  }'
```

> `"temporary": true` is the right default when provisioning a real user — it forces a
> password change on first login. It also attaches an `UPDATE_PASSWORD` required action,
> so this account cannot obtain a token via the password grant until that is resolved
> through the browser login flow. Use `"temporary": false` for accounts you intend to
> drive from scripts or tests, and populate `firstName`/`lastName` for the same reason.

### JSON Import

Bulk import users during realm setup:

```json
{
  "realm": "solr-mcp",
  "users": [
    {
      "username": "user1",
      "email": "user1@example.com",
      "firstName": "User",
      "lastName": "One",
      "enabled": true,
      "credentials": [{"type": "password", "value": "pass123"}],
      "realmRoles": ["user", "solr-query"]
    },
    {
      "username": "user2",
      "email": "user2@example.com",
      "firstName": "User",
      "lastName": "Two",
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

The shipped configuration authorizes with `@PreAuthorize("isAuthenticated()")`
on each `@McpTool` method. To authorize on Keycloak *roles* instead, you need a
converter that reads them out of the JWT.

### Create Roles in Keycloak

1. Navigate to **Realm roles** → **Create role**
2. Create roles like `admin`, `solr-query`, `solr-admin`
3. Assign roles to users via **Users** → select user → **Role mappings**

### Keycloak Role Locations

| Location | Claim Path | Use Case |
|----------|------------|----------|
| Realm roles | `realm_access.roles` | Global roles across all clients |
| Client roles | `resource_access.<client-id>.roles` | Roles specific to a client |

### Configure Spring Security

Keycloak nests realm roles under `realm_access.roles`, which
`JwtGrantedAuthoritiesConverter` cannot read directly — it expects a top-level
claim holding a list. Extract the nested list yourself:

```java
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@Bean
@ConditionalOnProperty(name = "http.security.enabled", havingValue = "true", matchIfMissing = true)
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || realmAccess.get("roles") == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    });
    return converter;
}
```

> **This is a sketch, not shipped configuration.** The converter above is
> correct for reading Keycloak's nested `realm_access.roles`, but wiring it in
> depends on how the JWT decoder is built. The shipped chain constructs its
> decoder through `McpServerOAuth2Configurer`, so verify that a standalone
> `JwtAuthenticationConverter` bean is actually picked up in your build before
> relying on role checks — and add a test that asserts a role-gated tool call
> succeeds with a role-bearing token and fails without one.

### Use Role Annotations

```java
@PreAuthorize("hasRole('admin')")
public String deleteCollection(String name) { ... }

@PreAuthorize("hasRole('solr-query')")
public String executeQuery(String collection, String query) { ... }

@PreAuthorize("hasAnyRole('solr-query', 'solr-admin')")
public String getSchema(String collection) { ... }
```

> **Do not copy an older filter-chain snippet over the shipped one.** The
> current chain in `HttpSecurityConfiguration` authenticates `/actuator/**`
> (only `/actuator/health` is anonymous) and sets `resourcePath("/mcp")` plus
> `validateAudienceClaim(true)`. Replacing it with a version that permits all
> actuator paths exposes `loggers`, `sbom`, `metrics` and `prometheus`
> anonymously, and dropping `validateAudienceClaim` reintroduces
> [CWE-345](https://cwe.mitre.org/data/definitions/345.html). See
> [`http.md`](./http.md#1-filter-chain-httpsecurityconfiguration) for the
> authoritative version.

## Troubleshooting

### `bootRun` exits with code 1 and prints nothing

This is the most common failure, and the hardest to read, because **HTTP mode
currently produces no application log output**: `logback.xml` is picked up by
logback's own self-initialization, so Spring Boot never applies
`logback-spring.xml`, where the `http` profile's console appender is defined.
Gradle reports only:

```
> Task :bootRun FAILED
Execution failed for task ':bootRun'.
> Process 'command '.../bin/java'' finished with non-zero exit value 1
```

Re-run with logging forced on to see the real exception:

```bash
LOGGING_CONFIG=classpath:logback-spring.xml ./gradlew bootRun
```

The usual cause is that the realm does not exist yet:

```
Error creating bean with name 'securityFilterChain' ...
  Unable to resolve the Configuration with the provided Issuer of
  "http://localhost:8180/realms/solr-mcp"
```

Fix by confirming the realm resolves before starting the server:

```bash
curl -sf http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration >/dev/null \
  && echo OK || echo "realm missing or Keycloak not ready"
```

### Common Issues

**"Invalid token" or 401 Unauthorized:**

- Read the `WWW-Authenticate` response header — it names the exact failure
  (`The aud claim is not valid`, expired, bad signature, ...)
- Verify `OAUTH2_ISSUER_URI` matches your Keycloak realm URL exactly
- Check that the token hasn't expired (default lifetime is 300s)
- Ensure Keycloak is accessible from the MCP server

**401 with `"The aud claim is not valid"`:**

- The Audience protocol mapper is missing or misconfigured. See
  [Configuring the Audience Claim](#configuring-the-audience-claim)
- The mapper's **Included Custom Audience** must equal the value returned by
  `curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq -r .resource`

**`{"error":"invalid_grant","error_description":"Account is not fully set up"}`:**

The account is not in a state Keycloak will issue a token for. The password grant has
no browser leg, so there is nowhere to render the "complete your profile" or "update
your password" page — Keycloak rejects the whole grant instead of prompting. The same
account signs in fine through a browser flow, which is why this looks like it only
breaks `curl`.

Two different causes produce this identical message:

1. **A required user-profile field is blank** — usually **first name** or **last name**,
   both of which are `required` in Keycloak's default user profile. Fill them in under
   **Users** → select user → **Details**.
2. **The account has a pending required action** — most often `UPDATE_PASSWORD`, added
   automatically whenever a password is set with **Temporary** ON.

Check both at once:

```bash
ADMIN=$(curl -s -X POST "http://localhost:8180/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" -d "username=admin" -d "password=admin" \
  -d "grant_type=password" | jq -r '.access_token')

curl -s -H "Authorization: Bearer $ADMIN" \
  "http://localhost:8180/admin/realms/solr-mcp/users?username=testuser&exact=true" \
  | jq -c '.[] | {username, firstName, lastName, emailVerified, requiredActions}'
```

A ready-to-use account has both names populated and an empty `requiredActions`:

```json
{"username":"testuser","firstName":"Test","lastName":"User","emailVerified":true,"requiredActions":[]}
```

Required actions and what they mean:

| Required action | Usual cause | Fix |
|-----------------|-------------|-----|
| `UPDATE_PASSWORD` | Password was set with **Temporary** ON | Re-set the password with **Temporary** OFF |
| `VERIFY_EMAIL` | Realm has email verification enabled | Set **Email verified** ON for the user |
| `UPDATE_PROFILE` | A required profile field is blank | Fill in the missing fields |
| `CONFIGURE_TOTP` | OTP required by the realm or a policy | Enroll OTP via the browser flow, or drop the requirement |

Clear pending actions from the console (**Users** → select user → **Details** →
**Required user actions** → remove → **Save**), or over the REST API:

```bash
USER_ID=$(curl -s -H "Authorization: Bearer $ADMIN" \
  "http://localhost:8180/admin/realms/solr-mcp/users?username=testuser&exact=true" | jq -r '.[0].id')

curl -X PUT "http://localhost:8180/admin/realms/solr-mcp/users/$USER_ID" \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"requiredActions":[]}'
```

Clearing an action does not change the password — the credential you already set stays
valid. Do this for test accounts only; real users should resolve the action through the
browser login flow.

**A tool call returns `"Access Denied"` instead of 401:**

Expected when no token is sent. `/mcp` is permitted at the HTTP layer and
authorization is enforced per tool by `@PreAuthorize`, so an anonymous `tools/call`
comes back as a JSON-RPC error with `isError: true` rather than an HTTP 401. An
anonymous `tools/list` succeeding is also expected.

**`unauthorized_client` or `Client not allowed for direct access grants`:**

- Enable **Direct access grants** on `solr-mcp-client`. Clients created through
  the Admin REST API have it off by default

**"Unable to resolve issuer":**

- Keycloak must be running and accessible
- Check the issuer URL: `http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration`

**CORS errors with MCP Inspector:**

- Ensure Web origins are configured in your Keycloak client
- Add `http://localhost:6274` to Web origins
- The server's own allowlist is `MCP_CORS_ALLOWED_ORIGINS` (defaults to
  `http://localhost:6274,http://127.0.0.1:6274`)

**Token doesn't contain roles:**

- Verify the user has roles assigned in Keycloak
- Check the token contents at https://jwt.io
- Ensure you're reading the correct claim path (`realm_access.roles`)

### Useful Commands

**Inspect a JWT token:**

```bash
# Decode token (without verification). Handles unpadded base64url correctly —
# see "Configuring the Audience Claim" for why `base64 -d` alone does not.
jwt_payload() {
  python3 -c "import sys,base64,json;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);\
print(json.dumps(json.loads(base64.urlsafe_b64decode(p))))" "$1"
}
jwt_payload "$TOKEN" | jq
```

**Check the audience claim specifically:**

```bash
jwt_payload "$TOKEN" | jq .aud
```

**Check Keycloak OpenID configuration:**

```bash
curl http://localhost:8180/realms/solr-mcp/.well-known/openid-configuration | jq
```

**Check what resource URI the MCP server expects:**

```bash
curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq
```

**Check whether an account can obtain a token:**

```bash
curl -s -H "Authorization: Bearer $ADMIN" \
  "http://localhost:8180/admin/realms/solr-mcp/users?username=testuser&exact=true" \
  | jq -c '.[] | {firstName, lastName, requiredActions}'
# names populated + requiredActions [] = good to go
```

**View Keycloak logs:**

```bash
docker logs -f keycloak
```

## References

- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [MCP Specification](https://spec.modelcontextprotocol.io/)
- [Solr MCP HTTP security model](./http.md)
