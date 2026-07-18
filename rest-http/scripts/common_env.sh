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
