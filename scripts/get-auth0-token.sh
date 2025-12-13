#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

################################################################################
# Auth0 Token Retrieval Script
#
# This script retrieves an OAuth 2.0 access token from Auth0 using the
# Client Credentials flow.
#
# Usage:
#   ./scripts/get-auth0-token.sh [OPTIONS]
#
# Options:
#   -d, --domain DOMAIN       Auth0 domain (e.g., your-tenant.auth0.com)
#   -c, --client-id ID        Auth0 Client ID
#   -s, --client-secret SEC   Auth0 Client Secret
#   -a, --audience AUD        Auth0 API Audience/Identifier
#   -o, --output FILE         Save token to file (default: .auth-token)
#   -q, --quiet               Only output the token (no formatting)
#   -h, --help                Show this help message
#
# Environment Variables:
#   If not provided as arguments, the script will try to read from:
#   - AUTH0_DOMAIN
#   - AUTH0_CLIENT_ID
#   - AUTH0_CLIENT_SECRET
#   - AUTH0_AUDIENCE
#   - Or from .env file in the project root
#
# Examples:
#   # Using environment variables
#   export AUTH0_DOMAIN=your-tenant.auth0.com
#   export AUTH0_CLIENT_ID=your-client-id
#   export AUTH0_CLIENT_SECRET=your-client-secret
#   export AUTH0_AUDIENCE=https://solr-mcp-api
#   ./scripts/get-auth0-token.sh
#
#   # Using command line arguments
#   ./scripts/get-auth0-token.sh \
#     --domain your-tenant.auth0.com \
#     --client-id your-client-id \
#     --client-secret your-client-secret \
#     --audience https://solr-mcp-api
#
#   # Save token to custom file
#   ./scripts/get-auth0-token.sh -o my-token.txt
#
#   # Get only the token (for scripting)
#   TOKEN=$(./scripts/get-auth0-token.sh --quiet)
#
################################################################################

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
OUTPUT_FILE=".auth-token"
QUIET=false
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Function to print colored output
print_info() {
    if [ "$QUIET" = false ]; then
        echo -e "${BLUE}[INFO]${NC} $1"
    fi
}

print_success() {
    if [ "$QUIET" = false ]; then
        echo -e "${GREEN}[SUCCESS]${NC} $1"
    fi
}

print_warning() {
    if [ "$QUIET" = false ]; then
        echo -e "${YELLOW}[WARNING]${NC} $1"
    fi
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# Function to show help
show_help() {
    grep '^#' "$0" | tail -n +3 | head -n -1 | cut -c 3-
    exit 0
}

# Function to load .env file
load_env_file() {
    local env_file="${PROJECT_ROOT}/.env"
    if [ -f "$env_file" ]; then
        print_info "Loading configuration from .env file"
        # Export variables from .env file
        set -a
        source "$env_file"
        set +a
    fi
}

# Parse command line arguments
parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -d|--domain)
                AUTH0_DOMAIN="$2"
                shift 2
                ;;
            -c|--client-id)
                AUTH0_CLIENT_ID="$2"
                shift 2
                ;;
            -s|--client-secret)
                AUTH0_CLIENT_SECRET="$2"
                shift 2
                ;;
            -a|--audience)
                AUTH0_AUDIENCE="$2"
                shift 2
                ;;
            -o|--output)
                OUTPUT_FILE="$2"
                shift 2
                ;;
            -q|--quiet)
                QUIET=true
                shift
                ;;
            -h|--help)
                show_help
                ;;
            *)
                print_error "Unknown option: $1"
                echo "Use --help for usage information"
                exit 1
                ;;
        esac
    done
}

# Function to validate required variables
validate_config() {
    local missing=()

    if [ -z "$AUTH0_DOMAIN" ]; then
        missing+=("AUTH0_DOMAIN")
    fi

    if [ -z "$AUTH0_CLIENT_ID" ]; then
        missing+=("AUTH0_CLIENT_ID")
    fi

    if [ -z "$AUTH0_CLIENT_SECRET" ]; then
        missing+=("AUTH0_CLIENT_SECRET")
    fi

    if [ -z "$AUTH0_AUDIENCE" ]; then
        missing+=("AUTH0_AUDIENCE")
    fi

    if [ ${#missing[@]} -gt 0 ]; then
        print_error "Missing required configuration:"
        for var in "${missing[@]}"; do
            echo "  - $var"
        done
        echo ""
        echo "Provide values via:"
        echo "  1. Command line arguments (--domain, --client-id, --client-secret, --audience)"
        echo "  2. Environment variables (AUTH0_DOMAIN, AUTH0_CLIENT_ID, etc.)"
        echo "  3. .env file in project root"
        echo ""
        echo "Use --help for more information"
        exit 1
    fi
}

# Function to get access token
get_token() {
    local token_url="https://${AUTH0_DOMAIN}/oauth/token"

    print_info "Requesting access token from Auth0..."
    print_info "Token URL: ${token_url}"

    # Prepare the request payload
    local payload=$(cat <<EOF
{
  "client_id": "${AUTH0_CLIENT_ID}",
  "client_secret": "${AUTH0_CLIENT_SECRET}",
  "audience": "${AUTH0_AUDIENCE}",
  "grant_type": "client_credentials"
}
EOF
)

    # Make the request
    # Required headers:
    # - Content-Type: application/json (tells Auth0 we're sending JSON)
    local response=$(curl -s -w "\n%{http_code}" -X POST "$token_url" \
        -H "Content-Type: application/json" \
        -d "$payload")

    # Extract HTTP status code and body
    local http_code=$(echo "$response" | tail -n1)
    local body=$(echo "$response" | sed '$d')

    # Check if request was successful
    if [ "$http_code" != "200" ]; then
        print_error "Failed to get access token (HTTP $http_code)"
        print_error "Response: $body"
        exit 1
    fi

    # Parse the response using jq if available, otherwise use grep/sed
    if command -v jq &> /dev/null; then
        ACCESS_TOKEN=$(echo "$body" | jq -r '.access_token')
        TOKEN_TYPE=$(echo "$body" | jq -r '.token_type')
        EXPIRES_IN=$(echo "$body" | jq -r '.expires_in')
    else
        ACCESS_TOKEN=$(echo "$body" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
        TOKEN_TYPE=$(echo "$body" | grep -o '"token_type":"[^"]*' | cut -d'"' -f4)
        EXPIRES_IN=$(echo "$body" | grep -o '"expires_in":[0-9]*' | cut -d':' -f2)
    fi

    if [ -z "$ACCESS_TOKEN" ] || [ "$ACCESS_TOKEN" = "null" ]; then
        print_error "Failed to extract access token from response"
        print_error "Response: $body"
        exit 1
    fi

    print_success "Successfully obtained access token"
}

# Function to display token information
display_token_info() {
    if [ "$QUIET" = true ]; then
        echo "$ACCESS_TOKEN"
        return
    fi

    echo ""
    echo "=========================================="
    echo "Access Token Retrieved Successfully"
    echo "=========================================="
    echo ""
    echo "Token Type: $TOKEN_TYPE"
    echo "Expires In: $EXPIRES_IN seconds ($(($EXPIRES_IN / 60)) minutes)"
    echo ""
    echo "Access Token:"
    echo "----------------------------------------"
    echo "$ACCESS_TOKEN"
    echo "----------------------------------------"
    echo ""

    # Calculate expiration time
    if command -v date &> /dev/null; then
        if [[ "$OSTYPE" == "darwin"* ]]; then
            # macOS
            EXPIRES_AT=$(date -v+${EXPIRES_IN}S "+%Y-%m-%d %H:%M:%S")
        else
            # Linux
            EXPIRES_AT=$(date -d "+${EXPIRES_IN} seconds" "+%Y-%m-%d %H:%M:%S")
        fi
        echo "Token expires at: $EXPIRES_AT"
        echo ""
    fi
}

# Function to save token to file
save_token() {
    # Resolve output file path (make it relative to project root if not absolute)
    if [[ "$OUTPUT_FILE" != /* ]]; then
        OUTPUT_FILE="${PROJECT_ROOT}/${OUTPUT_FILE}"
    fi

    echo "$ACCESS_TOKEN" > "$OUTPUT_FILE"
    chmod 600 "$OUTPUT_FILE"  # Secure the file

    print_success "Token saved to: $OUTPUT_FILE"
    print_info "File permissions set to 600 (owner read/write only)"
}

# Function to show usage example
show_usage_example() {
    if [ "$QUIET" = false ]; then
        echo ""
        echo "Usage Example:"
        echo "=========================================="
        echo ""
        echo "Using the token in a curl request:"
        echo ""
        echo "  curl -H \"Authorization: Bearer \$ACCESS_TOKEN\" \\"
        echo "       http://localhost:8080/api/your-endpoint"
        echo ""
        echo "Or read from the saved file:"
        echo ""
        echo "  TOKEN=\$(cat $OUTPUT_FILE)"
        echo "  curl -H \"Authorization: Bearer \$TOKEN\" \\"
        echo "       http://localhost:8080/api/your-endpoint"
        echo ""
        echo "=========================================="
        echo ""
    fi
}

# Main execution
main() {
    # Load .env file if it exists
    load_env_file

    # Parse command line arguments
    parse_arguments "$@"

    # Validate configuration
    validate_config

    # Get the token
    get_token

    # Display token information
    display_token_info

    # Save token to file (only if not in quiet mode or output specified)
    if [ "$QUIET" = false ] || [ "$OUTPUT_FILE" != ".auth-token" ]; then
        save_token
    fi

    # Show usage example
    show_usage_example
}

# Run main function
main "$@"