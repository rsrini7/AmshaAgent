#!/usr/bin/env bash
# common_env.sh — Common environment variables for Amsha Agent validation scripts

# API Keys resolved from the registry
if [ -z "${AMSHA_API_KEYS_INTERNAL:-}" ]; then
  if [ -f "$SCRIPT_DIR/../../dist/.env" ]; then
    _k=$(grep -E "^AMSHA_API_KEYS_INTERNAL=" "$SCRIPT_DIR/../../dist/.env" 2>/dev/null | cut -d= -f2- | tr -d ' "\r\n')
    [ -n "$_k" ] && AMSHA_API_KEYS_INTERNAL="$_k"
  fi
  if [ -z "${AMSHA_API_KEYS_INTERNAL:-}" ] && [ -f "$SCRIPT_DIR/../../.env" ]; then
    _k=$(grep -E "^AMSHA_API_KEYS_INTERNAL=" "$SCRIPT_DIR/../../.env" 2>/dev/null | cut -d= -f2- | tr -d ' "\r\n')
    [ -n "$_k" ] && AMSHA_API_KEYS_INTERNAL="$_k"
  fi
fi
if [ -n "${AMSHA_API_KEYS_INTERNAL:-}" ]; then
  VALID_KEY="${VALID_KEY:-$AMSHA_API_KEYS_INTERNAL}"
else
  VALID_KEY="${VALID_KEY:-key-internal-changeme}"
fi
if [ -n "${AMSHA_API_KEYS_CI:-}" ]; then
  CI_KEY="$AMSHA_API_KEYS_CI"
elif [ "${AMSHA_CI:-false}" = "true" ] && [ -z "${CI_KEY:-}" ]; then
  echo "Error: CI mode requires AMSHA_API_KEYS_CI or CI_KEY to be set." >&2
  exit 1
fi
CI_KEY="${CI_KEY:-key-ci-changeme}"
INVALID_KEY="${INVALID_KEY:-totally-wrong-key}"

# Key alias for compatibility across older scripts
KEY="$CI_KEY"

# Target Server Base URL
B="${TEST_BASE:-http://localhost:8080}"
BASE_URL="$B"

# Auto-discover default data directory if not explicitly set
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$COMMON_DIR/../../scripts/lib/repo_root.sh" ]; then
  # shellcheck source=../../scripts/lib/repo_root.sh
  source "$COMMON_DIR/../../scripts/lib/repo_root.sh"
  REPO_ROOT="$(get_repo_root "$COMMON_DIR")"
else
  REPO_ROOT="$(cd "$COMMON_DIR/../.." && pwd)"
fi

if [ -z "${AMSHA_DATA_DIR:-}" ]; then
  if [ -f "$REPO_ROOT/dist/data/amsha-meta.db" ]; then
    export AMSHA_DATA_DIR="$REPO_ROOT/dist/data"
  elif [ -f "$REPO_ROOT/data/amsha-meta.db" ]; then
    export AMSHA_DATA_DIR="$REPO_ROOT/data"
  elif [ -d "$REPO_ROOT/dist/data" ]; then
    export AMSHA_DATA_DIR="$REPO_ROOT/dist/data"
  elif [ -d "$REPO_ROOT/data" ]; then
    export AMSHA_DATA_DIR="$REPO_ROOT/data"
  fi
fi

# Default curl timeout in seconds for agent run/approve endpoints (configurable via AMSHA_VALIDATION_TIMEOUT)
CURL_TIMEOUT="${AMSHA_VALIDATION_TIMEOUT:-300}"

# Formats HTTP status codes, explaining 000 as timeout/unreachable
format_http_code() {
  local code="$1"
  if [ "$code" = "000" ] || [ -z "$code" ]; then
    echo "000 (CONNECTION TIMEOUT / SERVER UNREACHABLE)"
  else
    echo "$code"
  fi
}
