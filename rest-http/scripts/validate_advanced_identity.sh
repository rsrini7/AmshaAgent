#!/usr/bin/env bash
# Advanced identity checks requiring seeded users and credentials.
# Required: TEST_OWNER_KEY, TEST_WORKSPACE_ID, TEST_OWNER_IDS (comma separated).
# Optional: TEST_SECOND_OWNER_KEY, TEST_SECOND_OWNER_ID.

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
B="${TEST_BASE:-$B}"
R="$B/api/v1"
OWNER_KEY="${TEST_OWNER_KEY:-}"
WORKSPACE_ID="${TEST_WORKSPACE_ID:-}"
OWNER_IDS="${TEST_OWNER_IDS:-}"
SECOND_KEY="${TEST_SECOND_OWNER_KEY:-}"
SECOND_ID="${TEST_SECOND_OWNER_ID:-}"
FAIL=0; PASS=0

HAS_ADVANCED_FLAG=false
for arg in "$@"; do
  if [ "$arg" = "--advanced-identity" ]; then
    HAS_ADVANCED_FLAG=true
  fi
done

if [ "$HAS_ADVANCED_FLAG" != true ]; then
  echo "Error: validate_advanced_identity.sh must be explicitly invoked with --advanced-identity." >&2
  exit 2
fi

if [ "${AMSHA_ALLOW_DESTRUCTIVE_TESTS:-false}" != "true" ]; then
  echo "Error: Destructive identity tests require AMSHA_ALLOW_DESTRUCTIVE_TESTS=true." >&2
  exit 2
fi

if [ -z "$OWNER_KEY" ] || [ -z "$WORKSPACE_ID" ] || [ -z "$OWNER_IDS" ]; then
  echo "Error: Set TEST_OWNER_KEY, TEST_WORKSPACE_ID, and TEST_OWNER_IDS before running this validator." >&2
  exit 2
fi

RUN_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/amsha_adv_ident.XXXXXX")"
trap 'rm -rf "$RUN_TMP_DIR" 2>/dev/null || true' EXIT INT TERM

ok() { printf 'PASS %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf 'FAIL %s\n' "$1"; FAIL=$((FAIL + 1)); }
req() { curl -sS -m 20 -o "$RUN_TMP_DIR/response" -w '%{http_code}' "$@"; }

echo 'ADVANCED IDENTITY VALIDATION'

# Two concurrent owner removals must not remove the final active owner.
IFS=',' read -r FIRST_OWNER SECOND_OWNER _ <<< "$OWNER_IDS"
if [ -z "$SECOND_OWNER" ]; then
  echo 'Error: TEST_OWNER_IDS must contain at least two owner IDs.' >&2
  exit 2
fi

(
  curl -sS -m 20 -X DELETE -H "X-Amsha-API-Key: $OWNER_KEY" \
    -o "$RUN_TMP_DIR/race-a" -w '%{http_code}' \
    "$R/identity/workspaces/$WORKSPACE_ID/members/$FIRST_OWNER"
) > "$RUN_TMP_DIR/race-a.code" &
PID_A=$!
(
  curl -sS -m 20 -X DELETE -H "X-Amsha-API-Key: $OWNER_KEY" \
    -o "$RUN_TMP_DIR/race-b" -w '%{http_code}' \
    "$R/identity/workspaces/$WORKSPACE_ID/members/$SECOND_OWNER"
) > "$RUN_TMP_DIR/race-b.code" &
PID_B=$!
wait "$PID_A"; wait "$PID_B"

CODE_A=$(cat "$RUN_TMP_DIR/race-a.code")
CODE_B=$(cat "$RUN_TMP_DIR/race-b.code")
if { [ "$CODE_A" = 200 ] && [ "$CODE_B" = 400 ]; } || \
   { [ "$CODE_A" = 400 ] && [ "$CODE_B" = 200 ]; }; then
  ok "concurrent last-owner removal: one success, one blocked"
else
  bad "concurrent last-owner removal returned $CODE_A and $CODE_B"
fi

# A second seeded user must be able to exercise independent authorization.
if [ -n "$SECOND_KEY" ] && [ -n "$SECOND_ID" ]; then
  CODE=$(req GET -H "X-Amsha-API-Key: $SECOND_KEY" "$R/identity/me")
  [ "$CODE" = 200 ] && ok 'second-user identity authentication' || bad "second-user identity returned $CODE"
  CODE=$(req GET -H "X-Amsha-API-Key: $SECOND_KEY" "$R/identity/workspaces/$WORKSPACE_ID/members")
  [ "$CODE" = 200 ] && ok 'second-user workspace authorization' || bad "second-user workspace returned $CODE"
else
  echo 'SKIP second-user checks: set TEST_SECOND_OWNER_KEY and TEST_SECOND_OWNER_ID.'
fi

echo "Advanced identity results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
