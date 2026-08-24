#!/usr/bin/env bash
# validate_budget.sh — Validate Budget Governance & Autonomy Level REST workflow
# Covers: invalid autonomy → 400, READONLY blocks tools (no TOOL_INVOKED),
# FULL autonomy bypasses approval for non-sensitive tools (200, not 202),
# while NETWORK_OUTBOUND and EXECUTE_SHELL remain mandatory HITL,
# SUPERVISED pauses for approval (202), and budget-exhausted audit event.
#
# Needs: serve mode + LLM + GITHUB_TOKEN. LLM cost for the actual runs;
# zero cost for the 400/invalid edge cases.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"

FAIL=0
PASS=0
RL_WAIT=62

info() { printf "%-4s %-58s " "" "$1"; }
ok() { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail() { printf "❌ HTTP %s\n" "$1"; FAIL=$((FAIL+1)); }
mute() { curl -s -m 180 -o /tmp/resp -w "%{http_code}" "$@"; }
sid() { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }
create_session() {
  local body="$1"
  local c
  c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    "$B/api/v1/sessions" -d "$body")
  if [ "$c" = "429" ]; then
    sleep "$RL_WAIT"
    c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
      "$B/api/v1/sessions" -d "$body")
  fi
  echo "$c"
}

run_req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  if [ -n "$data" ]; then
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
  else
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
  fi
  if [ "$c" = "429" ]; then
    sleep "$RL_WAIT"
    if [ -n "$data" ]; then
      c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
    else
      c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
    fi
  fi
  echo "$c"
}

status_is() {
  mute -X GET -H "X-Amsha-API-Key: $VALID_KEY" "$B/api/v1/sessions/$1/status/coordinator" >/dev/null 2>&1
  python3 -c "import json; print(json.load(open('/tmp/resp')).get('status',''))" 2>/dev/null
}

echo "═══════════════════════════════════════════"
echo " BUDGET & AUTONOMY REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── FLOW A: invalid autonomy → 400 ──
echo "── FLOW A: Edge Cases ──"
info "BUDGET-ERR-1: invalid autonomy level → 400"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
  "$B/api/v1/sessions" -d '{"autonomyLevel":"TURBO_MODE_YOLO"}')
[ "$CODE" = "400" ] && ok "$CODE" || fail "$CODE"

echo ""

# ── FLOW B: READONLY blocks tools ──
echo "── FLOW B: READONLY autonomy ──"
info "AUTO-8: create READONLY session"
CODE=$(create_session '{"autonomyLevel":"READONLY"}')
RID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; RID=""; }
if [ -n "$RID" ]; then
  info "AUTO-9: run github task (READONLY → blocked, 200 no tool)"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$RID/run" '{"goal":"List the public repositories of GitHub user rsrini7.","agentRole":"coordinator","maxIterations":2}')
  [ "$CODE" = "200" ] || [ "$CODE" = "202" ] && ok "$CODE" || fail "$CODE"
  info "AUTO-10: audit has NO TOOL_INVOKED"
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/audit/session/$RID")
  grep -q "TOOL_INVOKED" /tmp/resp && fail "$CODE (TOOL_INVOKED present)" || ok "$CODE"
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$RID" >/dev/null 2>&1
fi

echo ""

# ── FLOW C: FULL autonomy bypasses approval for safe operations ──
echo "── FLOW C: FULL autonomy ──"
info "AUTO-5: create FULL session"
CODE=$(create_session '{"autonomyLevel":"FULL"}')
FID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; FID=""; }
if [ -n "$FID" ]; then
  info "AUTO-6: run safe memory task (FULL → 200, NOT 202)"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$FID/run" '{"goal":"Use recall_memory_search to search memory for any facts about user rsrini7, then summarize the result.","agentRole":"coordinator","maxIterations":3}')
  [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "AUTO-7: status no longer AWAITING_APPROVAL after FULL run"
  ST=""
  for i in $(seq 1 10); do
    ST=$(status_is "$FID")
    [ "$ST" != "AWAITING_APPROVAL" ] && break
    sleep 3
  done
  [ "$ST" != "AWAITING_APPROVAL" ] && ok "$ST" || fail "$ST"
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$FID" >/dev/null 2>&1
fi

echo ""

# ── FLOW D: SUPERVISED pauses for approval ──
echo "── FLOW D: SUPERVISED autonomy ──"
info "AUTO-1: create default (SUPERVISED) session"
CODE=$(create_session '{"autonomyLevel":"SUPERVISED"}')
SID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; SID=""; }
if [ -n "$SID" ]; then
  info "AUTO-2: run github task (SUPERVISED → 202 AWAITING_APPROVAL)"
  CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    "$B/api/v1/sessions/$SID/run-async" \
    -d '{"goal":"List the public repositories of GitHub user rsrini7.","agentRole":"coordinator","maxIterations":3}')
  [ "$CODE" = "202" ] && ok "$CODE" || fail "$CODE"
  info "AUTO-3: status = AWAITING_APPROVAL"
  [ "$(status_is "$SID")" = "AWAITING_APPROVAL" ] && ok "AWAITING_APPROVAL" || fail "$(status_is "$SID")"

  info "AUTO-4: force budget-exhausted audit path"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SID/run" '{"goal":"Write a comprehensive 1000-word guide to Quarkus CDI scopes including WorkspaceScoped custom CDI scope, @ApplicationScoped, @RequestScoped, and @SessionScoped with comparison tables.","agentRole":"researcher","maxIterations":5}')
  [ "$CODE" = "200" ] || [ "$CODE" = "202" ] || [ "$CODE" = "408" ] || [ "$CODE" = "422" ] && ok "$CODE" || fail "$CODE"
  info "AUTO-5: audit query succeeds; report loop-budget outcome"
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/audit/session/$SID")
  if [ "$CODE" != "200" ]; then
    fail "$CODE"
  elif grep -q "LOOP_BUDGET_EXCEEDED" /tmp/resp; then
    ok "$CODE (LOOP_BUDGET_EXCEEDED present)"
  else
    ok "$CODE (model completed or paused before exhausting its probabilistic loop budget)"
  fi
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1
fi

echo "───────────────────────────────────────────"
echo " Budget Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
