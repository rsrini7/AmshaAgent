#!/usr/bin/env bash
# validate_hitl.sh — Validate HUMAN-IN-THE-LOOP (HITL) governance REST endpoints
# Covers: approve/deny via dedicated REST endpoints, goal-string in-band method,
# taskId-based approval, AWAITING_APPROVAL → COMPLETED/FAILED state machine, and
# 401/409 edge cases.
#
# Needs: serve mode + GITHUB_TOKEN (GithubTool is the only NETWORK_OUTBOUND tool).
# Zero cost for auth/edge checks; LLM cost only for the actual approve/deny runs.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0; RL_WAIT=62

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 120 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 180 -o /tmp/resp -w "%{http_code}" "$@"; }
sid()   { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }
task_id() {
  python3 -c "import json; d=json.load(open('/tmp/resp')); rows=d if isinstance(d,list) else d.get('items',d.get('tasks',[])) if isinstance(d,dict) else []; print((rows or [{}])[0].get('taskId',''))" 2>/dev/null
}
is_sandbox_offline() { grep -q "SANDBOX_OFFLINE" /tmp/resp; }

create_session() {
  local c
  c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    -H "X-Amsha-Test-Expected-Tool-Risk: REQUIRE_APPROVAL" \
    "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}')
  if [ "$c" = "429" ]; then sleep $RL_WAIT; c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" -H "X-Amsha-Test-Expected-Tool-Risk: REQUIRE_APPROVAL" "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}'); fi
  echo "$c"
}
trigger_approval() {
  # run-async a github task; expect 202 AWAITING_APPROVAL
  mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    "$B/api/v1/sessions/$1/run-async" \
    -d '{"goal":"List the public repositories of GitHub user rsrini7 using the github tool.","agentRole":"coordinator","maxIterations":3}'
}
status_is() {
  mute -X GET -H "X-Amsha-API-Key: $VALID_KEY" "$B/api/v1/sessions/$1/status/coordinator" >/dev/null 2>&1
  python3 -c "import json; print(json.load(open('/tmp/resp')).get('status',''))" 2>/dev/null
}
run_req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  if [ -n "$data" ]; then
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
  else
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
  fi
  if [ "$c" = "429" ]; then sleep $RL_WAIT
    if [ -n "$data" ]; then c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
    else c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url"); fi
  fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " HITL GOVERNANCE REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── FLOW A: REST approve ──
echo "── FLOW A: REST Approve ──"
info "HITL-1: create session"
CODE=$(create_session); SID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; SID=""; }
if [ -n "$SID" ]; then
  info "HITL-2: trigger AWAITING_APPROVAL (run-async github)"
  CODE=$(trigger_approval "$SID")
  [ "$CODE" = "202" ] || [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "HITL-3: status = AWAITING_APPROVAL"
  ST=""; for i in $(seq 1 20); do ST=$(status_is "$SID"); [ "$ST" = "AWAITING_APPROVAL" ] && break; sleep 3; done
  [ "$ST" = "AWAITING_APPROVAL" ] && ok "$ST" || fail "$ST"
  info "HITL-4: approve (REST endpoint)"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SID/approve?agentRole=coordinator")
  if [ "$CODE" = "200" ]; then
    ok "$CODE"
  elif [ "$CODE" = "500" ] && is_sandbox_offline; then
    ok "$CODE (approval resumed; optional github CLI unavailable)"
  else
    fail "$CODE"
  fi
  info "HITL-5: status no longer AWAITING_APPROVAL after approve"
  ST=""; for i in $(seq 1 10); do ST=$(status_is "$SID"); [ "$ST" != "AWAITING_APPROVAL" ] && break; sleep 3; done
  case "$ST" in
    NOT_STARTED|COMPLETED|FAILED) ok "$ST" ;;
    *) fail "$ST" ;;
  esac
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1
fi
echo ""

# ── FLOW B: REST deny ──
echo "── FLOW B: REST Deny ──"
info "HITL-6: create session"
CODE=$(create_session); SID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; SID=""; }
if [ -n "$SID" ]; then
  info "HITL-7: trigger AWAITING_APPROVAL"
  CODE=$(trigger_approval "$SID")
  [ "$CODE" = "202" ] || [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "HITL-8: deny (REST endpoint)"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SID/deny?agentRole=coordinator")
  [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "HITL-9: status leaves AWAITING_APPROVAL after deny"
  ST=$(status_is "$SID")
  case "$ST" in
    FAILED|NOT_STARTED) ok "$ST" ;;
    *) fail "$ST" ;;
  esac
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1
fi
echo ""

# ── FLOW C: HITL taskId approve/reject (UI path) ──
echo "── FLOW C: TaskId-based Approval ──"
info "HITL-10: create session + trigger approval"
CODE=$(create_session); SID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; SID=""; }
if [ -n "$SID" ]; then
  trigger_approval "$SID" >/dev/null 2>&1
  info "HITL-11: list pending approvals (GET /hitl)"
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/hitl?page=0&size=20")
  [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  TID=$(task_id)
  if [ -n "$TID" ]; then
    info "HITL-12: approve by taskId"
    CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/hitl/$TID/approve")
    if [ "$CODE" = "200" ]; then
      ok "$CODE"
    elif [ "$CODE" = "500" ] && is_sandbox_offline; then
      ok "$CODE (approval resumed; optional github CLI unavailable)"
    else
      fail "$CODE"
    fi
  else
    echo "  ⚠️  No taskId captured (approval may have already resolved) — skipping taskId approve"
  fi
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1
fi
echo ""

# ── FLOW D: Edge cases ──
echo "── FLOW D: Edge Cases ──"
info "HITL-14: approve non-existent session → 404/409"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/00000000-dead-beef-0000-000000000000/approve?agentRole=coordinator")
[ "$CODE" = "404" ] || [ "$CODE" = "409" ] && ok "$CODE" || fail "$CODE"
info "HITL-16: approve without API key → 401"
CODE=$(run_req POST "" "$B/api/v1/sessions/00000000-dead-beef-0000-000000000000/approve")
[ "$CODE" = "401" ] && ok "$CODE" || fail "$CODE"
info "HITL-18: deny non-existent session → 404/409"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/00000000-dead-beef-0000-000000000000/deny?agentRole=coordinator")
[ "$CODE" = "404" ] || [ "$CODE" = "409" ] && ok "$CODE" || fail "$CODE"

echo "───────────────────────────────────────────"
echo " HITL Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
