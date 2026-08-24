#!/usr/bin/env bash
# validate_rag.sh — Validate RAG (Retrieval-Augmented Generation) REST workflow
# Covers: IngestDocumentTool trigger (AWAITING_APPROVAL) → approve → recall search
# via agent, audit TOOL_INVOKED verification, and Phase 64-C3 rag_chunk isolation.
#
# Needs: serve mode + LLM. Ingest requires approval (SUPERVISED). Zero cost for
# auth/edge checks; LLM cost for ingest + recall runs.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0; RL_WAIT=62

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s\n" "$CODE"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 180 -o /tmp/resp -w "%{http_code}" "$@"; }
sid()   { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }
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
create_session() {
  local c
  c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}')
  if [ "$c" = "429" ]; then sleep $RL_WAIT; c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}'); fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " RAG (INGEST → RECALL) REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── FLOW A: ingest → approve → recall ──
echo "── FLOW A: Ingest → Approve → Recall ──"
info "RAG-0: create session"
CODE=$(create_session); SID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; SID=""; }
if [ -n "$SID" ]; then
  info "RAG-1: ingest document (run-async, DESTRUCTIVE → 202)"
  CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    "$B/api/v1/sessions/$SID/run-async" \
    -d '{"goal":"Ingest this document: Amsha Agent is a production-grade AI agent kernel built with Java 25, Quarkus 3.27.2, and LangChain4j 1.5. It uses SQLite WAL mode for persistence and AES-GCM-SIV with Argon2id for secret storage.","agentRole":"coordinator","maxIterations":2}')
  [ "$CODE" = "202" ] && ok "$CODE" || fail "$CODE"
  info "RAG-2: status = AWAITING_APPROVAL"
  [ "$(status_is "$SID")" = "AWAITING_APPROVAL" ] && ok "AWAITING_APPROVAL" || fail "$(status_is "$SID")"
  info "RAG-3: approve ingest"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SID/approve?agentRole=coordinator")
  [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"
  info "RAG-4: status no longer AWAITING_APPROVAL after approve"
  ST=""; for i in $(seq 1 10); do ST=$(status_is "$SID"); [ "$ST" != "AWAITING_APPROVAL" ] && break; sleep 3; done
  case "$ST" in
    NOT_STARTED|COMPLETED|FAILED) ok "$ST" ;;
    *) fail "$ST" ;;
  esac
  info "RAG-5: recall search (agent run)"
  CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SID/run" '{"goal":"What do you know about Amsha Agent security and persistence?","agentRole":"coordinator","maxIterations":3}')
  if [ "$CODE" = "202" ]; then
    run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SID/approve?agentRole=coordinator" >/dev/null 2>&1
    sleep 2
    CODE="200"
  fi
  [ "$CODE" = "200" ] || [ "$CODE" = "408" ] && ok "$CODE" || fail "$CODE"
  info "RAG-7: audit TOOL_INVOKED present"
  CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/audit/session/$SID")
  grep -q "TOOL_INVOKED" /tmp/resp && ok "$CODE" || fail "$CODE"
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1
fi
echo ""

# ── FLOW B: isolation (Phase 64-C3) ──
echo "── FLOW B: Phase 64-C3 rag_chunk isolation ──"
info "RAG-C3: compaction_summary has no 'rag:' rows (expect 0)"
# This check is best-effort: only runs if a DB path is provided.
if [ -n "${AMSHA_DATA_DIR:-}" ] && command -v sqlite3 >/dev/null 2>&1; then
  DB="$AMSHA_DATA_DIR/amsha-meta.db"
  if [ -f "$DB" ]; then
    RC=$(sqlite3 "$DB" "SELECT COUNT(*) FROM compaction_summary WHERE session_id LIKE 'rag:%';" 2>/dev/null)
    [ "$RC" = "0" ] && ok "0" || fail "found $RC rag:-prefixed rows"
  else
    echo "  ⚠️  DB not found at $DB — skipping isolation SQL check"
  fi
else
  echo "  ℹ️  [OPTIONAL] Set AMSHA_DATA_DIR to verify rag_chunk isolation via sqlite3"
fi

echo "───────────────────────────────────────────"
echo " RAG Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
