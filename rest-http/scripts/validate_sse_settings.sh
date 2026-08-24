#!/usr/bin/env bash
# validate_sse_settings.sh — Validate UI SSE streams, WebSocket token, and
# Settings CRUD REST endpoints not covered by a dedicated validator.
# Covers: /agents, /sessions/active, /agents/events (SSE), /chat/stream (SSE),
# /chat/stream/history, /hitl, /hitl/events (SSE), /chat/ws-token,
# /settings + /settings/prompt (GET/PATCH/reset), and 401 on missing key.
#
# SSE endpoints are probed with a short curl timeout — we only assert they open
# and emit an initial event/frame (HTTP 200), not that they stream to completion.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0; RL_WAIT=62

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s\n" "$(format_http_code "$1")"; FAIL=$((FAIL+1)); }

# SSE probe: open the stream, capture first bytes within 3s, report status.
sse() {
  local url="$1"
  curl -sS -N -m 3 -o /tmp/sse_out -w "%{http_code}" \
    -H "X-Amsha-API-Key: $VALID_KEY" "$url" 2>/dev/null
}
run_req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  if [ -n "$data" ]; then
    c=$(curl -s -m 30 -o /tmp/resp -w "%{http_code}" -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
  else
    c=$(curl -s -m 30 -o /tmp/resp -w "%{http_code}" -X "$method" -H "X-Amsha-API-Key: $key" "$url")
  fi
  if [ "$c" = "429" ]; then sleep $RL_WAIT
    if [ -n "$data" ]; then c=$(curl -s -m 30 -o /tmp/resp -w "%{http_code}" -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
    else c=$(curl -s -m 30 -o /tmp/resp -w "%{http_code}" -X "$method" -H "X-Amsha-API-Key: $key" "$url"); fi
  fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " UI SSE STREAMS & SETTINGS VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── FLOW A: UI status endpoints (non-SSE) ──
echo "── FLOW A: UI Status ──"
info "UI-1: GET /agents"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/agents"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
info "UI-2: GET /sessions/active"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/sessions/active"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

# Need a session for the SSE/stream endpoints that take sessionId
info "UI-3: create session"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions" '{"autonomyLevel":"SUPERVISED","agentRole":"coordinator"}'); SID=""
if [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; then
  ok "$CODE"; SID=$(python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null)
else
  fail "$CODE"
fi
echo ""

# ── FLOW B: SSE streams ──
echo "── FLOW B: SSE Streams ──"
if [ -n "$SID" ]; then
  info "UI-4: GET /chat/stream (SSE, 3s probe)"
  CODE=$(sse "$B/api/v1/chat/stream?sessionId=$SID"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "UI-5: GET /chat/stream/history"
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/chat/stream/history/$SID"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "UI-6: GET /agents/events (SSE, 3s probe)"
  CODE=$(sse "$B/api/v1/agents/events?sessionId=$SID"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "UI-8: GET /hitl/events (SSE, 3s probe)"
  CODE=$(sse "$B/api/v1/hitl/events?sessionId=$SID"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "UI-7: GET /hitl (pending approvals)"
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/hitl?page=0&size=20"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "UI-9: POST /chat/ws-token"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/chat/ws-token"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1
else
  echo "  ⚠️  No session created — skipping SSE/stream checks"
fi
echo ""

# ── FLOW C: Settings CRUD ──
echo "── FLOW C: Settings ──"
info "SETTINGS-1: GET /settings"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/settings"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
info "SETTINGS-2: GET /settings/prompt"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/settings/prompt"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
info "SETTINGS-3: PATCH /settings/provider (ollama, no key required)"
CODE=$(run_req PATCH "$VALID_KEY" "$B/api/v1/settings/provider" '{"provider":"ollama","model":"llama3"}'); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
info "SETTINGS-4: PATCH /settings/prompt"
CODE=$(run_req PATCH "$VALID_KEY" "$B/api/v1/settings/prompt" '{"systemPrompt":"You are a helpful AI assistant."}'); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
info "SETTINGS-5: POST /settings/reset"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/settings/reset"); [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
info "SETTINGS-6: GET /settings without key → 401"
CODE=$(curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$B/api/v1/settings"); [ "$CODE" = "401" ] && ok "$CODE" || fail "$CODE"

echo "───────────────────────────────────────────"
echo " SSE/Settings Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
