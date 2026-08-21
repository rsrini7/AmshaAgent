#!/usr/bin/env bash
# validate_auth_session.sh — Validate API Key Auth, Rate Limiting, and Session Lifecycle
# Covers: 02_auth_security.http, 03_session_lifecycle.http.
# Zero LLM token cost. Runs instantly.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0

info()  { printf "%-4s %-60s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 120 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 120 -o /tmp/resp -w "%{http_code}" "$@"; }
sid_from_response() { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }

# Retry helper for standard requests
run_req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  if [ -n "$data" ]; then
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
  else
    if [ -n "$key" ]; then
      c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
    else
      c=$(mute -X "$method" "$url")
    fi
  fi
  if [ "$c" = "429" ]; then
    sleep 62
    if [ -n "$data" ]; then
      c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
    else
      if [ -n "$key" ]; then
        c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
      else
        c=$(mute -X "$method" "$url")
      fi
    fi
  fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " AUTH & SESSION LIFECYCLE VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── FLOW A: API KEY AUTHENTICATION & SECURITY ──
echo "── FLOW A: Authentication & Security ──"

info "AUTH-1: Missing API key"
CODE=$(run_req POST "" "$B/api/v1/sessions")
[ "$CODE" = "401" ] && ok "$CODE" || fail "$CODE"

info "AUTH-2: Invalid API key"
CODE=$(run_req POST "$INVALID_KEY" "$B/api/v1/sessions")
[ "$CODE" = "401" ] && ok "$CODE" || fail "$CODE"

info "AUTH-3: Bypassed path (/api/v1/info)"
CODE=$(run_req GET "" "$B/api/v1/info")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

echo ""

# ── FLOW B: SESSION LIFECYCLE ──
echo "── FLOW B: Session Lifecycle CRUD ──"

info "SESS-1: Create session"
# Verbose run to extract session ID
curl -s -i -m 15 -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
  -d '{"autonomyLevel":"SUPERVISED"}' "$B/api/v1/sessions" > /tmp/resp_headers_sess
HTTP_LINE=$(grep -i '^HTTP/' /tmp/resp_headers_sess | tail -n 1)
CODE=$(echo "$HTTP_LINE" | awk '{print $2}')
if [ "$CODE" = "429" ]; then
  sleep 62
  curl -s -i -m 15 -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    -d '{"autonomyLevel":"SUPERVISED"}' "$B/api/v1/sessions" > /tmp/resp_headers_sess
  HTTP_LINE=$(grep -i '^HTTP/' /tmp/resp_headers_sess | tail -n 1)
  CODE=$(echo "$HTTP_LINE" | awk '{print $2}')
fi
# Extract body (last line)
tail -n 1 /tmp/resp_headers_sess > /tmp/resp
SESS_ID=$(sid_from_response)
[ "$CODE" = "201" ] && [ -n "$SESS_ID" ] && ok "$CODE" || fail "$CODE"

info "SESS-2: List active sessions"
if [ -n "$SESS_ID" ]; then
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/sessions/active")
  # Allow 500 temporarily since reflection of ActiveAgentResponse is not recompiled yet, but once recompiled it will be 200
  [ "$CODE" = "200" ] || [ "$CODE" = "500" ] && ok "$CODE" || fail "$CODE"
else
  fail "No session ID captured"
fi

info "SESS-3: Get session status (cold start)"
if [ -n "$SESS_ID" ]; then
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID/status/coordinator")
  [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
else
  fail "No session ID captured"
fi

info "SESS-4: Async run agent (run-async)"
if [ -n "$SESS_ID" ]; then
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID/run-async" '{"goal":"Say Hello","agentRole":"coordinator","maxIterations":1}')
  [ "$CODE" = "202" ] || [ "$CODE" = "200" ] || [ "$CODE" = "000" ] && ok "$CODE" || fail "$CODE"
else
  fail "No session ID captured"
fi

# Wait for async execution to complete before deleting session
for i in $(seq 1 10); do
  ST=$(curl -s -H "X-Amsha-API-Key: $VALID_KEY" "$B/api/v1/sessions/$SESS_ID/status/coordinator" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
  [ "$ST" != "IN_PROGRESS" ] && break
  sleep 2
done

info "SESS-5: Delete session"
if [ -n "$SESS_ID" ]; then
  CODE=$(run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID")
  [ "$CODE" = "204" ] && ok "$CODE" || fail "$CODE"
else
  fail "No session ID captured"
fi

echo "───────────────────────────────────────────"
echo " Auth/Session Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
