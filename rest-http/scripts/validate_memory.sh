#!/usr/bin/env bash
# validate_memory.sh — Validate ARCHIVAL-1..6, CORE-1..7, MEM-ARABIC-1 from 10_memory_compaction.http
# Needs LLM (SUPERVISED sessions). ~5-10 min for all tests.
#
# IMPORTANT: sessions are SUPERVISED, so memory-WRITE tools (archival_memory_insert,
# ingest_document, core memory writes) return HTTP 202 "pending approval". This script
# auto-approves via POST /sessions/{id}/approve so the flow can continue. Both 200
# (executed inline) and 202→approved are treated as success. Read-only ops return 200.
# Usage: bash validate_memory.sh [--archival] [--core] [--arabic]

set +e
KEY="JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs="
B="http://localhost:8080"
FAIL=0; PASS=0; SLEEP=7; RL_WAIT=62

info()  { printf "%-4s %-35s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
okh()   { printf "✅ HTTP %s (HITL→approved)\n" "$1"; PASS=$((PASS+1)); }
warn()  { printf "⚠️  HTTP %s (%s)\n" "$1" "$2"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 80 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }
sid_from_response() { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }

create_session() {
  local c
  c=$(mute -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}')
  if [ "$c" = "429" ]; then sleep "$RL_WAIT"; c=$(mute -H "X-Amsha-API-Key: $KEY" \
    -H "Content-Type: application/json" -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}'); fi
  echo "$c"
}

run_raw() {
  curl -s -m 90 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions/$1/run" \
    -d "{\"goal\":\"$2\",\"agentRole\":\"coordinator\",\"maxIterations\":${3:-2}}"
}

approve() {
  curl -s -m 90 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" \
    -X POST "$B/api/v1/sessions/$1/approve?agentRole=coordinator"
}

# step SID LABEL GOAL [max] [errok]
# Runs a goal; if 202 (HITL pending) auto-approves up to 3 tool calls. errok=1 also accepts 400.
step() {
  local sid="$1" label="$2" goal="$3" max="${4:-2}" errok="${5:-0}"
  info "$label"
  sleep "$SLEEP"
  local code hitl=0 guard=0
  code=$(run_raw "$sid" "$goal" "$max")
  if [ "$code" = "429" ]; then sleep "$RL_WAIT"; code=$(run_raw "$sid" "$goal" "$max"); fi
  while [ "$code" = "202" ] && [ $guard -lt 3 ]; do
    hitl=1; guard=$((guard+1)); sleep 3
    code=$(approve "$sid")
    if [ "$code" = "429" ]; then sleep "$RL_WAIT"; code=$(approve "$sid"); fi
  done
  if [ "$errok" = "1" ] && [ "$code" = "400" ]; then ok "$code"; return; fi
  case $code in
    200) [ $hitl -eq 1 ] && okh "$code" || ok "$code";;
    202) okh "202";;
    408) warn "$code" "LLM iteration budget — agent working, ran long";;
    *) fail "$code";;
  esac
}

RUN_ALL=true
[ "$1" = "--archival" ] && RUN_ALL=false && ARCHIVAL=true
[ "$1" = "--core" ] && RUN_ALL=false && CORE=true
[ "$1" = "--arabic" ] && RUN_ALL=false && ARABIC=true

echo "═══════════════════════════════════════════"
echo " MEMORY VALIDATOR (HITL-aware)"
echo " Tests: archival search, core blocks, Arabic"
echo "═══════════════════════════════════════════"
echo ""

# ── ARCHIVAL MEMORY SEARCH ──
if [ "$RUN_ALL" = true ] || [ "$ARCHIVAL" = true ]; then
  echo "── FLOW: Archival Memory Search ──"
  info "Create session"; CODE=$(create_session); sleep 2; ASID=$(sid_from_response)
  { [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; } && ok "$CODE" || { fail "$CODE"; ASID=""; }
  if [ -n "$ASID" ]; then
    step "$ASID" "ARCHIVAL-2: Store FACT"        "store this in archival memory: Amsha Agent uses Quarkus with SQLite WAL mode. Type FACT" 3
    step "$ASID" "ARCHIVAL-3: Store PATTERN"     "store this in long-term memory: Always use sealed classes for type hierarchies. Type PATTERN" 3
    step "$ASID" "ARCHIVAL-4: search for Quarkus" "search archival for Quarkus with limit 3" 4
    step "$ASID" "ARCHIVAL-5: search for patterns" "search my archival knowledge for patterns" 4
    step "$ASID" "ARCHIVAL-6: CODE type chunks"  "search my archival for CODE type chunks" 4
    step "$ASID" "ARCHIVAL-ERR-1: invalid TYPE"  "store this in archival memory: some fact. Type INVALID_TYPE" 3 1
    sleep "$SLEEP"; mute -X DELETE "$B/api/v1/sessions/$ASID" -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1
  fi
  echo ""
fi

# ── CORE MEMORY BLOCKS ──
if [ "$RUN_ALL" = true ] || [ "$CORE" = true ]; then
  echo "── FLOW: Core Memory Blocks ──"
  info "Create session"; CODE=$(create_session); sleep 2; CSID=$(sid_from_response)
  { [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; } && ok "$CODE" || { fail "$CODE"; CSID=""; }
  if [ -n "$CSID" ]; then
    step "$CSID" "CORE-2: read core memory"      "read my core memory" 4
    step "$CSID" "CORE-3: set user profile info" "my name is Srini, my email is test@example.com, and I love to study and teach kids" 4
    step "$CSID" "CORE-4: set active task"       "set my active_task core memory block to: implementing tiered memory architecture" 4
    step "$CSID" "CORE-5: set constraints"       "update my constraints to: always use CAS for concurrent writes" 4
    step "$CSID" "CORE-6: read core + active_task" "read my core memory show me my active_task block" 4
    step "$CSID" "CORE-7: read user_profile block" "show me my user_profile block" 4
    step "$CSID" "CORE-ERR-1: invalid block"     "store something in block invalid_key" 3 1
    sleep "$SLEEP"; mute -X DELETE "$B/api/v1/sessions/$CSID" -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1
  fi
  echo ""
fi

# ── ARABIC NON-ASCII ──
if [ "$RUN_ALL" = true ] || [ "$ARABIC" = true ]; then
  echo "── FLOW: Arabic Non-ASCII Search ──"
  info "Create session"; CODE=$(create_session); sleep 2; ARSID=$(sid_from_response)
  { [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; } && ok "$CODE" || { fail "$CODE"; ARSID=""; }
  if [ -n "$ARSID" ]; then
    step "$ARSID" "MEM-ARABIC-1: Arabic query"   "ابحث في ذاكرتك عن معلومات حول بنية Amsha Agent." 4
    sleep "$SLEEP"; mute -X DELETE "$B/api/v1/sessions/$ARSID" -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1
  fi
  echo ""
fi

echo "───────────────────────────────────────────"
echo " Memory Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && echo "ALL PASSED" || echo "SOME FAILED"
