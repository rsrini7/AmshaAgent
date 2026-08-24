#!/usr/bin/env bash
# common_env.sh — Common environment variables for Amsha Agent validation scripts

# API Keys resolved from the registry
VALID_KEY="${VALID_KEY:-cWevfEoRvstUv0PqGpf02LzDY5kPNaz1y9GSl0LZOwU=}"
CI_KEY="${CI_KEY:-JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs=}"
INVALID_KEY="${INVALID_KEY:-totally-wrong-key}"

# Key alias for compatibility across older scripts
KEY="$CI_KEY"

# Target Server Base URL
B="${TEST_BASE:-http://localhost:8080}"
BASE_URL="$B"

# Auto-discover default data directory if not explicitly set
COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$COMMON_DIR/../.." && pwd)"

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

# Formats HTTP status codes, explaining 000 as timeout/unreachable
format_http_code() {
  local code="$1"
  if [ "$code" = "000" ] || [ -z "$code" ]; then
    echo "000 (CONNECTION TIMEOUT / SERVER UNREACHABLE)"
  else
    echo "$code"
  fi
}
