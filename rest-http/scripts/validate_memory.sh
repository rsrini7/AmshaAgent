#!/usr/bin/env bash
# validate_memory.sh — Validate ARCHIVAL-1..6, CORE-1..7, MEM-ARABIC-1 from 10_memory_compaction.http
# Needs LLM (SUPERVISED sessions). ~2-3 min for all tests.
# Usage: bash validate_memory.sh [--archival] [--core] [--arabic]

set +e
KEY="JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs="
B="http://localhost:8080"
FAIL=0; PASS=0; SLEEP=7

info()  { printf "%-4s %-35s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 80 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }

run_agent() {
  local sid="$1" goal="$2" role="${3:-coordinator}" max="${4:-2}"
  curl -s -m 60 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" \
    -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions/$sid/run" \
    -d "{\"goal\":\"$goal\",\"agentRole\":\"$role\",\"maxIterations\":$max}"
}

create_session() {
  curl -s -m 10 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" \
    -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions" \
    -d '{"autonomyLevel":"SUPERVISED"}'
}

sid_from_response() {
  python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null
}

RUN_ALL=true
[ "$1" = "--archival" ] && RUN_ALL=false && ARCHIVAL=true
[ "$1" = "--core" ] && RUN_ALL=false && CORE=true
[ "$1" = "--arabic" ] && RUN_ALL=false && ARABIC=true

echo "═══════════════════════════════════════════"
echo " MEMORY VALIDATOR"
echo " Tests: archival search, core blocks, Arabic"
echo "═══════════════════════════════════════════"
echo ""

# ── ARCHIVAL MEMORY SEARCH ──
if [ "$RUN_ALL" = true ] || [ "$ARCHIVAL" = true ]; then
  echo "── FLOW: Archival Memory Search ──"
  info "Create session"
  CODE=$(create_session); sleep 2
  ASID=$(sid_from_response)
  [ "$CODE" = "201" ] && ok "$CODE" "session=$ASID" || { fail "$CODE"; ASID=""; }

  if [ -n "$ASID" ]; then
    info "ARCHIVAL-2: Store FACT"
    sleep $SLEEP; CODE=$(run_agent "$ASID" "store this in archival memory: Amsha Agent uses Quarkus with SQLite WAL mode. Type FACT" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "ARCHIVAL-3: Store PATTERN"
    sleep $SLEEP; CODE=$(run_agent "$ASID" "store this in long-term memory: Always use sealed classes for type hierarchies. Type PATTERN" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "ARCHIVAL-4: search for Quarkus"
    sleep $SLEEP; CODE=$(run_agent "$ASID" "search archival for Quarkus with limit 3" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "ARCHIVAL-5: search for patterns"
    sleep $SLEEP; CODE=$(run_agent "$ASID" "search my archival knowledge for patterns" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "ARCHIVAL-6: CODE type chunks"
    sleep $SLEEP; CODE=$(run_agent "$ASID" "search my archival for CODE type chunks" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "ARCHIVAL-ERR-1: invalid TYPE"
    sleep $SLEEP; CODE=$(run_agent "$ASID" "store this in archival memory: some fact. Type INVALID_TYPE" "coordinator" 2)
    case $CODE in 200|400) ok "$CODE";; *) fail "$CODE";; esac

    mute -X DELETE "$B/api/v1/sessions/$ASID" -H "X-Amsha-API-Key: $KEY" 2>/dev/null
  fi
  echo ""
fi

# ── CORE MEMORY BLOCKS ──
if [ "$RUN_ALL" = true ] || [ "$CORE" = true ]; then
  echo "── FLOW: Core Memory Blocks ──"
  info "Create session"
  CODE=$(create_session); sleep 2
  CSID=$(sid_from_response)
  [ "$CODE" = "201" ] && ok "$CODE" || { fail "$CODE"; CSID=""; }

  if [ -n "$CSID" ]; then
    info "CORE-2: read core memory"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "read my core memory" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "CORE-3: set user profile info"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "my name is Srini, my email is test@example.com, and I love to study and teach kids" "coordinator" 3)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "CORE-4: set active task"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "my active task is implementing tiered memory architecture" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "CORE-5: set constraints"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "update my constraints to: always use CAS for concurrent writes" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "CORE-6: read core + active_task"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "read my core memory show me my active_task block" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "CORE-7: read user_profile block"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "show me my user_profile block" "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    info "CORE-ERR-1: invalid block"
    sleep $SLEEP; CODE=$(run_agent "$CSID" "store something in block invalid_key" "coordinator" 2)
    case $CODE in 200|400) ok "$CODE";; *) fail "$CODE";; esac

    mute -X DELETE "$B/api/v1/sessions/$CSID" -H "X-Amsha-API-Key: $KEY" 2>/dev/null
  fi
  echo ""
fi

# ── ARABIC NON-ASCII ──
if [ "$RUN_ALL" = true ] || [ "$ARABIC" = true ]; then
  echo "── FLOW: Arabic Non-ASCII Search ──"
  info "Create session"
  CODE=$(create_session); sleep 2
  ARSID=$(sid_from_response)
  [ "$CODE" = "201" ] && ok "$CODE" || { fail "$CODE"; ARSID=""; }

  if [ -n "$ARSID" ]; then
    info "MEM-ARABIC-1: Arabic query"
    sleep $SLEEP; CODE=$(run_agent "$ARSID" "ابحث في ذاكرتك عن معلومات حول بنية Amsha Agent." "coordinator" 2)
    case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

    mute -X DELETE "$B/api/v1/sessions/$ARSID" -H "X-Amsha-API-Key: $KEY" 2>/dev/null
  fi
  echo ""
fi

echo "───────────────────────────────────────────"
echo " Memory Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && echo "ALL PASSED" || echo "SOME FAILED"
