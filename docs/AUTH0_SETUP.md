# Auth0 Setup Guide for Solr MCP Server

This guide walks you through setting up Auth0 authentication for the Solr MCP server, including configuration for MCP
Inspector.

## Table of Contents

1. [Create Auth0 Application](#1-create-auth0-application)
2. [Create Auth0 API](#2-create-auth0-api)
3. [Configure OAuth Settings](#3-configure-oauth-settings)
4. [Get Access Token Using Client Credentials](#4-get-access-token-using-client-credentials)
5. [MCP Inspector Configuration](#5-mcp-inspector-configuration)

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
    - **Identifier**: `https://solr-mcp-api` (this is your Audience)
        - This can be any URI format, but use a URL-like identifier
        - This value will be used as the `audience` in token requests
    - **Signing Algorithm**: Leave as **RS256** (recommended)
2. Click **Create**

### Step 3: Configure API Permissions (Optional)

1. Go to the **Permissions** tab
2. Add scopes if needed (e.g., `read:schema`, `write:documents`)
3. For basic setup, you can skip this and add permissions later

---

## 3. Configure OAuth Settings

### Step 1: Configure Application Settings

1. Go back to **Applications** > **Applications**
2. Click on your **Solr MCP Server** application
3. Go to the **Settings** tab

### Step 2: Set Allowed Callback URLs

Add the following callback URLs (comma-separated):

```
http://localhost:6274/oauth/callback,http://localhost:8080/login/oauth2/code/auth0
```

- `http://localhost:6274/oauth/callback` - For **MCP Inspector**
- `http://localhost:8080/login/oauth2/code/auth0` - For your Solr MCP Server

### Step 3: Set Allowed Logout URLs (Optional)

Add if you need logout functionality:

```
http://localhost:8080/logout,http://localhost:6274
```

### Step 4: Grant API Access

1. Scroll down to **APIs** section on the application settings page
2. Or go to the **APIs** tab in your application
3. Find your **Solr MCP API** and click the toggle to authorize it
4. Select the permissions/scopes you want to grant (or select all)

### Step 5: Save Changes

- Scroll to the bottom and click **Save Changes**

---

## 4. Get Access Token Using Client Credentials

### Understanding the Token Request

Auth0 uses the OAuth 2.0 Client Credentials flow for machine-to-machine authentication.

### Manual Token Request (using cURL)

```bash
curl --request POST \
  --url https://YOUR_DOMAIN/oauth/token \
  --header 'content-type: application/json' \
  --data '{
    "client_id": "YOUR_CLIENT_ID",
    "client_secret": "YOUR_CLIENT_SECRET",
    "audience": "https://solr-mcp-api",
    "grant_type": "client_credentials"
  }'
```

Replace:

- `YOUR_DOMAIN`: Your Auth0 domain (e.g., `your-tenant.auth0.com`)
- `YOUR_CLIENT_ID`: Your application's Client ID
- `YOUR_CLIENT_SECRET`: Your application's Client Secret
- `audience`: Your API identifier (e.g., `https://solr-mcp-api`)

### Response Format

```json
{
    "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6...",
    "token_type": "Bearer",
    "expires_in": 86400
}
```

### Using the Access Token

Include the token in your HTTP requests:

```bash
curl --request GET \
  --url http://localhost:8080/api/some-endpoint \
  --header 'Authorization: Bearer YOUR_ACCESS_TOKEN'
```

Or for MCP Inspector, configure it in the OAuth settings as described below.

---

## 5. MCP Inspector Configuration

### Step 1: Ensure Callback URL is Added

In Auth0 Dashboard:

1. Go to **Applications** > **Applications**
2. Click on your **Solr MCP Server** application
3. In **Settings** tab, find **Allowed Callback URLs**
4. Ensure this URL is included:
   ```
   http://localhost:6274/oauth/callback
   ```
5. Click **Save Changes**

### Step 2: Configure MCP Inspector

When running MCP Inspector with OAuth:

1. Start your Solr MCP server with OAuth enabled
2. Open MCP Inspector (usually at `http://localhost:6274`)
3. When connecting to your server, use OAuth configuration:
    - **Authorization URL**: `https://YOUR_DOMAIN/authorize`
    - **Token URL**: `https://YOUR_DOMAIN/oauth/token`
    - **Client ID**: Your application's Client ID
    - **Client Secret**: Your application's Client Secret (if required)
    - **Scope**: Scopes required by your API (e.g., `openid profile`)
    - **Redirect URI**: `http://localhost:6274/oauth/callback`

### Step 3: Test the Connection

1. Click "Connect" in MCP Inspector
2. You'll be redirected to Auth0 login page
3. After successful authentication, you'll be redirected back to MCP Inspector
4. The inspector should now have an access token to communicate with your server

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
AUTH0_AUDIENCE=https://solr-mcp-api
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
  --audience https://solr-mcp-api
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

## Configuring the Solr MCP Application

### Required Environment Variable

Set the OAuth2 issuer URI for your Solr MCP application:

```bash
# OAuth2 Resource Server Configuration
OAUTH2_ISSUER_URI=https://your-tenant.auth0.com/
```

**Important**: The issuer URI must end with a trailing slash `/`

### Running the Application with OAuth2

The application must run in `http` profile to enable OAuth2 security:

```bash
# Option 1: Using environment variable
export PROFILES=http
./gradlew bootRun

# Option 2: Using Spring Boot argument
./gradlew bootRun --args='--spring.profiles.active=http'

# Option 3: Using Gradle property
./gradlew bootRun -Dspring.profiles.active=http
```

### Application Configuration Details

The OAuth2 configuration is in `src/main/resources/application-http.properties`:

```properties
# OAuth2 Security Configuration
spring.security.oauth2.resourceserver.jwt.issuer-uri=${OAUTH2_ISSUER_URI:https://your-auth0-domain.auth0.com/}
```

**Key Points**:

- OAuth2 is **only active** when using the `http` profile
- The default profile is `stdio` which does **not** use OAuth2
- The application validates JWT tokens from the configured issuer
- No audience validation is performed - only issuer validation
- CORS is enabled for MCP Inspector support
- CSRF is disabled for API usage

### Testing the Application

Once the application is running with OAuth2:

```bash
# Get a token using the script
TOKEN=$(./scripts/get-auth0-token.sh --quiet)

# Use the token to call the API
curl -H "Authorization: Bearer $TOKEN" \
     http://localhost:8080/mcp/v1/your-endpoint
```

---

## Troubleshooting

### Common Issues

1. **"Invalid Callback URL"**
    - Ensure the callback URL is exactly as configured in Auth0
    - Check for trailing slashes or http vs https mismatches

2. **"Unauthorized Client"**
    - Verify Client ID and Client Secret are correct
    - Ensure the application is authorized to access the API

3. **"Invalid Audience"**
    - Check that the audience matches your API identifier exactly
    - The audience is case-sensitive

4. **Token Validation Fails**
    - Ensure issuer-uri matches your Auth0 domain exactly
    - Ensure the issuer-uri ends with a trailing slash `/`

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