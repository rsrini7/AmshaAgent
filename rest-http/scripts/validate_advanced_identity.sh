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

ok() { printf 'PASS %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf 'FAIL %s\n' "$1"; FAIL=$((FAIL + 1)); }
req() { curl -sS -m 20 -o /tmp/amsha-advanced-response -w '%{http_code}' "$@"; }

if [ -z "$OWNER_KEY" ] || [ -z "$WORKSPACE_ID" ] || [ -z "$OWNER_IDS" ]; then
  echo "Set TEST_OWNER_KEY, TEST_WORKSPACE_ID, and TEST_OWNER_IDS before running this validator."
  exit 2
fi

echo 'ADVANCED IDENTITY VALIDATION'

# Two concurrent owner removals must not remove the final active owner.
IFS=',' read -r FIRST_OWNER SECOND_OWNER _ <<< "$OWNER_IDS"
if [ -z "$SECOND_OWNER" ]; then
  echo 'TEST_OWNER_IDS must contain at least two owner IDs.'
  exit 2
fi

rm -f /tmp/amsha-advanced-race-a /tmp/amsha-advanced-race-b
(
  curl -sS -m 20 -X DELETE -H "X-Amsha-API-Key: $OWNER_KEY" \
    -o /tmp/amsha-advanced-race-a -w '%{http_code}' \
    "$R/identity/workspaces/$WORKSPACE_ID/members/$FIRST_OWNER"
) > /tmp/amsha-advanced-race-a.code &
PID_A=$!
(
  curl -sS -m 20 -X DELETE -H "X-Amsha-API-Key: $OWNER_KEY" \
    -o /tmp/amsha-advanced-race-b -w '%{http_code}' \
    "$R/identity/workspaces/$WORKSPACE_ID/members/$SECOND_OWNER"
) > /tmp/amsha-advanced-race-b.code &
PID_B=$!
wait "$PID_A"; wait "$PID_B"

CODE_A=$(cat /tmp/amsha-advanced-race-a.code)
CODE_B=$(cat /tmp/amsha-advanced-race-b.code)
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
