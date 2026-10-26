<!--
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Auth0 Setup Guide for Solr MCP Server

This guide walks you through securing the Solr MCP server's HTTP transport with
[Auth0](https://auth0.com/): register an API and a machine-to-machine
application, mint an access token, run the server, verify it, and connect an
MCP client. Companion to [`keycloak.md`](./keycloak.md); the server-side model
is in [`http.md`](./http.md).

## Table of Contents

1. [Create Auth0 Application](#1-create-auth0-application)
2. [Create Auth0 API](#2-create-auth0-api)
3. [Authorize the Application for the API](#3-authorize-the-application-for-the-api)
4. [Get an Access Token](#4-get-an-access-token)
5. [Run the Server](#5-run-the-server)
6. [Verify](#6-verify)
7. [Connect an MCP Client](#7-connect-an-mcp-client)
- [Convenience Script for Getting Access Tokens](#convenience-script-for-getting-access-tokens)
- [Application Configuration Details](#application-configuration-details)
- [Troubleshooting](#troubleshooting)
- [Security Best Practices](#security-best-practices)

## Before you start: the audience

The server validates the token's `aud` claim against its **resource URI**,
which it derives from the URL the client calls — scheme, host and port plus
`/mcp`. For a local trial that is `http://localhost:8080/mcp`; for a deployed
server it is the public URL, e.g. `https://mcp.example.com/mcp`. Read it from a
running server with:

```bash
curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq -r .resource
# http://localhost:8080/mcp
```

Auth0 puts the API **Identifier** into `aud`, so the Identifier you choose in
step 2 must be that URI, spelled exactly as clients will dial it. Auth0 never
calls the URL; it only has to match. A token for any other identifier (the
`https://solr-mcp-api` style Auth0 suggests) is rejected with
`401 ... "The aud claim is not valid"`.

---

## 1. Create Auth0 Application

### Step 1: Log in to Auth0 Dashboard

- Go to [Auth0 Dashboard](https://manage.auth0.com/)
- Log in with your credentials

### Step 2: Create a New Application

1. Navigate to **Applications** > **Applications** in the left sidebar
2. Click **Create Application**
3. Enter application details:
    - **Name**: `Solr MCP Server` (or your preferred name)
    - **Application Type**: Select **Machine to Machine Applications**
4. Click **Create**

A machine-to-machine application uses the Client Credentials grant: it
obtains tokens with its client ID and secret, with no browser login. That is
the right shape for trying the server from a terminal or from an MCP client
that takes a bearer token.

### Step 3: Note Your Application Credentials

After creation, you'll see the application settings page. Note down:

- **Domain**: `your-tenant.auth0.com` (found at the top of the page)
- **Client ID**: A unique identifier for your application
- **Client Secret**: Keep this secure, it's like a password

⚠️ **Important**: Keep your Client Secret secure and never commit it to version control.

---

## 2. Create Auth0 API

### Step 1: Navigate to APIs

1. In the Auth0 Dashboard, go to **Applications** > **APIs**
2. Click **Create API**

### Step 2: Configure API Settings

1. Enter API details:
    - **Name**: `Solr MCP API` (or your preferred name)
    - **Identifier**: `http://localhost:8080/mcp` — the server's resource URI
      (see [Before you start](#before-you-start-the-audience)). For a deployed
      server use its public URL, e.g. `https://mcp.example.com/mcp`.
    - **Signing Algorithm**: Leave as **RS256** (recommended)
2. Click **Create**

### Step 3: Configure API Permissions (Optional)

1. Go to the **Permissions** tab
2. Add scopes if needed (e.g., `read:schema`, `write:documents`)
3. For basic setup, you can skip this and add permissions later. The shipped
   server checks `isAuthenticated()` only; scopes are not evaluated.

---

## 3. Authorize the Application for the API

1. Go back to **Applications** > **Applications**
2. Click on your **Solr MCP Server** application
3. Open the **APIs** tab
4. Find your **Solr MCP API** and click the toggle to authorize it
5. Select the permissions/scopes you want to grant (or select all)
6. Click **Update**

No callback or logout URLs are needed: the MCP server is a resource server, it
never redirects a browser to Auth0, and a machine-to-machine application does
not use the authorization-code flow.

---

## 4. Get an Access Token

### Manual Token Request (using cURL)

```bash
curl --request POST \
  --url https://YOUR_DOMAIN/oauth/token \
  --header 'content-type: application/json' \
  --data '{
    "client_id": "YOUR_CLIENT_ID",
    "client_secret": "YOUR_CLIENT_SECRET",
    "audience": "http://localhost:8080/mcp",
    "grant_type": "client_credentials"
  }'
```

Replace:

- `YOUR_DOMAIN`: Your Auth0 domain (e.g., `your-tenant.auth0.com`)
- `YOUR_CLIENT_ID`: Your application's Client ID
- `YOUR_CLIENT_SECRET`: Your application's Client Secret
- `audience`: Your API identifier from step 2

### Response Format

```json
{
    "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6...",
    "token_type": "Bearer",
    "expires_in": 86400
}
```

### Check the audience before going further

```bash
TOKEN=$(curl -s --request POST --url https://YOUR_DOMAIN/oauth/token \
  --header 'content-type: application/json' \
  --data '{"client_id":"YOUR_CLIENT_ID","client_secret":"YOUR_CLIENT_SECRET",
           "audience":"http://localhost:8080/mcp","grant_type":"client_credentials"}' \
  | jq -r .access_token)

jwt_payload() {
  python3 -c "import sys,base64,json;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);\
print(json.dumps(json.loads(base64.urlsafe_b64decode(p))))" "$1"
}
jwt_payload "$TOKEN" | jq '{iss, aud}'
# { "iss": "https://your-tenant.auth0.com/", "aud": "http://localhost:8080/mcp" }
```

`iss` ends with a slash; `OAUTH2_ISSUER_URI` in the next step must match it
character for character.

---

## 5. Run the Server

The HTTP profile is secured by default; the only value you have to supply is
the issuer. Pass the environment inline rather than `export`ing it — an
exported `OAUTH2_ISSUER_URI` is inherited by the Gradle daemon and breaks the
next `./gradlew build`.

```bash
PROFILES=http \
OAUTH2_ISSUER_URI=https://your-tenant.auth0.com/ \
./gradlew bootRun
```

Or with the fat JAR or the Docker image:

```bash
PROFILES=http OAUTH2_ISSUER_URI=https://your-tenant.auth0.com/ \
  java -jar build/libs/solr-mcp-1.0.0-SNAPSHOT.jar

docker run -p 8080:8080 --rm \
  -e PROFILES=http \
  -e OAUTH2_ISSUER_URI=https://your-tenant.auth0.com/ \
  -e SOLR_URL=http://host.docker.internal:8983/solr/ \
  solr-mcp:latest
```

**The issuer URI must end with a trailing slash `/`**, because that is how
Auth0 writes the `iss` claim. The server resolves
`https://your-tenant.auth0.com/.well-known/openid-configuration` **at startup**
and exits if it cannot; a wrong domain fails immediately, not on the first
request.

The server is ready when `curl http://localhost:8080/actuator/health` answers
`{"status":"UP"}`.

---

## 6. Verify

An unauthenticated tool call is denied inside the tool (HTTP `200`, JSON-RPC
error); the same call with the token returns data. That contrast is the check
that the gate is live:

```bash
# Without a token → "Access Denied"
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"list-collections","arguments":{}}}'
# {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Access Denied"}],"isError":true}}

# With the token → the collections
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"list-collections","arguments":{}}}'
# {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"[\"books\",\"films\"]"}],"isError":false}}
```

To see *why* a token is rejected, ask a protected actuator endpoint, which
answers with a plain `401` and the reason:

```bash
curl -s -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/metrics | grep -i www-authenticate
# 200 and no header → token valid
# ... error_description="... The aud claim is not valid" → wrong API identifier / wrong URL spelling
# ... error_description="... Jwt expired at ..."          → request a new token
```

---

## 7. Connect an MCP Client

The server does not answer an anonymous `/mcp` request with `401` — `/mcp` is
permitted at the HTTP layer and the denial happens inside each tool — and MCP
clients start their OAuth flow only on a `401`. A client configured with just
the URL therefore shows the server as connected, lists every tool, and gets
`Access Denied` from every call. Pass the token explicitly:

```bash
# Claude Code
claude mcp add --transport http solr-mcp http://localhost:8080/mcp \
  --header "Authorization: Bearer $TOKEN"

# MCP Inspector, CLI
npx @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer $TOKEN" --method tools/call --tool-name list-collections

# MCP Inspector, web UI
npx @modelcontextprotocol/inspector --server-url http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer $TOKEN"
```

The other clients take the same header — the table in
[`http.md`](./http.md#connecting-an-mcp-client-to-a-secured-server) covers
Claude Desktop and JetBrains via `mcp-remote`, VS Code, Cursor and Zed, and
each [client guide](../clients/) shows the full snippet. Auth0
machine-to-machine tokens last 24 hours by default (`expires_in: 86400`);
when one expires, re-add the server with a fresh token.

---

## Convenience Script for Getting Access Tokens

A convenience script `scripts/get-auth0-token.sh` is provided to automate token retrieval for testing and development.

### Script Configuration

The script requires the following configuration (can be provided via environment variables, `.env` file, or command-line
arguments):

```bash
# Auth0 Token Script Configuration
AUTH0_DOMAIN=your-tenant.auth0.com
AUTH0_CLIENT_ID=your-client-id-here
AUTH0_CLIENT_SECRET=your-client-secret-here
AUTH0_AUDIENCE=http://localhost:8080/mcp
```

### Script Usage

**Using .env file:**

```bash
# Create .env file with the above configuration
./scripts/get-auth0-token.sh
```

**Using command-line arguments:**

```bash
./scripts/get-auth0-token.sh \
  --domain your-tenant.auth0.com \
  --client-id YOUR_CLIENT_ID \
  --client-secret YOUR_CLIENT_SECRET \
  --audience http://localhost:8080/mcp
```

**Get just the token (for scripting):**

```bash
TOKEN=$(./scripts/get-auth0-token.sh --quiet)
echo "Authorization: Bearer $TOKEN"
```

**Save to custom file:**

```bash
./scripts/get-auth0-token.sh --output my-token.txt
```

The script will:

1. Request an access token from Auth0 using Client Credentials flow
2. Display the token and expiration time
3. Save the token to `.auth-token` file (with secure 600 permissions)

---

## Application Configuration Details

No file edits are required; the environment variables above are enough. For
reference, the relevant entries in `src/main/resources/application-http.properties`:

```properties
# Security toggle — HTTP mode is secured by default.
http.security.enabled=${HTTP_SECURITY_ENABLED:true}

# OAuth2 issuer. Empty by default: the filter chain then answers 401/403 to
# everything except /actuator/health instead of crashing on a placeholder.
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OAUTH2_ISSUER_URI:}
```

**Key Points**:

- OAuth2 is **only active** when using the `http` profile
- The default profile is `stdio` which does **not** use OAuth2
- The server validates the JWT signature (against the issuer's JWKS), issuer,
  expiry **and audience** — the `aud` claim must contain the server's resource
  URI (see [`http.md`](./http.md#2-jwt-validation))
- `HTTP_SECURITY_ENABLED=false` turns authentication off for a local
  experiment; never on anything reachable by other people
- CORS allows the MCP Inspector's origin by default (`MCP_CORS_ALLOWED_ORIGINS`)
- CSRF is disabled because the API is stateless bearer-token

---

## Troubleshooting

### Common Issues

1. **`401` with `The aud claim is not valid`**
    - The API Identifier is not the server's resource URI. Compare
      `jwt_payload "$TOKEN" | jq .aud` with
      `curl -s http://localhost:8080/.well-known/oauth-protected-resource | jq -r .resource`
    - The client dials the server under a different name than the one in the
      token (`127.0.0.1` vs `localhost`, or a public hostname). Use the same
      spelling everywhere

2. **`401` with `Jwt expired at ...`**
    - Request a new token; the default lifetime is 24 hours

3. **Server exits at startup with `Unable to resolve the Configuration with the provided Issuer`**
    - `OAUTH2_ISSUER_URI` is wrong or lacks the trailing slash, or the machine
      cannot reach Auth0. Check with
      `curl https://your-tenant.auth0.com/.well-known/openid-configuration`

4. **`access_denied` / `Unauthorized` from the token endpoint**
    - Verify Client ID and Client Secret are correct
    - Ensure the application is authorized for the API (step 3)

5. **Every tool call returns `Access Denied` from an MCP client, no browser opens**
    - Expected when the client sends no token: the server does not challenge
      `/mcp` with `401`, so the client never starts OAuth. Configure the header
      as in [step 7](#7-connect-an-mcp-client)

---

## Security Best Practices

1. **Never commit secrets**: Use environment variables or secure vaults
2. **Rotate credentials**: Regularly rotate Client Secrets
3. **Use HTTPS in production**: Never use http:// for production callbacks
4. **Limit scopes**: Grant only necessary permissions
5. **Monitor usage**: Use Auth0 Dashboard to monitor authentication attempts
6. **Set token expiration**: Configure appropriate token lifetimes

---

## References

- [Auth0 Documentation](https://auth0.com/docs)
- [OAuth 2.0 Client Credentials Flow](https://auth0.com/docs/get-started/authentication-and-authorization-flow/client-credentials-flow)
- [Auth0 APIs](https://auth0.com/docs/get-started/apis)
- [Spring Security OAuth 2.0 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Solr MCP HTTP security model](./http.md)
