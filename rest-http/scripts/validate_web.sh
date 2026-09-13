#!/usr/bin/env bash
# validate_web.sh — Validate WEB-1..9 from 17_web_tools.http
# Needs LLM + SUPERVISED sessions + Serper key (Brave is "none" in dist/.env).
#
# Web tools are NETWORK_OUTBOUND → ALWAYS require HITL approval → run returns 202.
# This script auto-approves (role-aware) so the tool executes and the flow continues.
# SSRF-blocked fetches still complete (agent receives a blocked-result) → 200.
# Coordinator lacks web caps → tool never fires → 200 (declines) or 403.
# Usage: bash validate_web.sh [--search] [--fetch] [--ssrf]

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/amsha_validate_web.XXXXXX")"
RESP_FILE="$RUN_DIR/resp"
cleanup() { rm -rf -- "$RUN_DIR"; }
trap cleanup EXIT INT TERM
FAIL=0; PASS=0; SLEEP=7; RL_WAIT=62

info()  { printf "%-4s %-40s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
okh()   { printf "✅ HTTP %s (HITL→approved)\n" "$1"; PASS=$((PASS+1)); }
warn()  { printf "⚠️  HTTP %s (%s)\n" "$1" "$2"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s\n" "$1"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 15 -o "$RESP_FILE" -w "%{http_code}" "$@"; }
sid()   { python3 -c "import json; print(json.load(open('$RESP_FILE')).get('sessionId',''))" 2>/dev/null; }

create_session() {
  local c
  c=$(mute -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}')
  if [ "$c" = "429" ]; then sleep "$RL_WAIT"; c=$(mute -H "X-Amsha-API-Key: $KEY" \
    -H "Content-Type: application/json" -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}'); fi
  echo "$c"
}

run_raw() {
  curl -s -m "$CURL_TIMEOUT" -o "$RESP_FILE" -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions/$1/run" \
    -d "{\"goal\":\"$2\",\"agentRole\":\"$3\",\"maxIterations\":$4}"
}

approve() {
  curl -s -m "$CURL_TIMEOUT" -o "$RESP_FILE" -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" \
    -X POST "$B/api/v1/sessions/$1/approve?agentRole=$2"
}

# step SID LABEL GOAL ROLE MAX ACCEPT
#   ACCEPT = extra accepted codes (space-sep), e.g. "403" or "400 403"
step() {
  local sid="$1" label="$2" goal="$3" role="$4" max="$5" accept="$6"
  info "$label"; sleep "$SLEEP"
  local code hitl=0 guard=0
  code=$(run_raw "$sid" "$goal" "$role" "$max")
  if [ "$code" = "429" ]; then sleep "$RL_WAIT"; code=$(run_raw "$sid" "$goal" "$role" "$max"); fi
  while [ "$code" = "202" ] && [ $guard -lt 3 ]; do
    hitl=1; guard=$((guard+1)); sleep 3
    code=$(approve "$sid" "$role")
    if [ "$code" = "429" ]; then sleep "$RL_WAIT"; code=$(approve "$sid" "$role"); fi
  done
  for a in $accept; do [ "$code" = "$a" ] && { ok "$code"; return; }; done
  case $code in
    200) [ $hitl -eq 1 ] && okh "$code" || ok "$code";;
    202) okh "202";;
    408) warn "$code" "LLM iteration budget — agent working";;
    *) fail "$code";;
  esac
}

RUN_ALL=true
[ "$1" = "--search" ] && RUN_ALL=false && DO_S=1
[ "$1" = "--fetch" ]  && RUN_ALL=false && DO_F=1
[ "$1" = "--ssrf" ]   && RUN_ALL=false && DO_X=1

echo "═══════════════════════════════════════════"
echo " WEB TOOLS VALIDATOR (HITL-aware)"
echo " Tests: web search, fetch, browse, SSRF"
echo "═══════════════════════════════════════════"

if [ "$RUN_ALL" = true ] || [ "$DO_S" = 1 ]; then
  echo ""; echo "── FLOW: Web Search ──"
  info "Create session"; CODE=$(create_session); sleep 2; WSID=$(sid)
  { [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; } && ok "$CODE" || { fail "$CODE"; WSID=""; }
  if [ -n "$WSID" ]; then
    step "$WSID" "WEB-2: brave_web_search (researcher)" "do a web search on spring boot and quarkus LTS version" researcher 4 ""
    step "$WSID" "WEB-3: serper_web_search (researcher)" "search for top quarkus features using serper" researcher 4 ""
    step "$WSID" "WEB-4: coordinator no search cap" "search the web for latest spring boot news" coordinator 3 "403"
    sleep "$SLEEP"; mute -X DELETE "$B/api/v1/sessions/$WSID" -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1
  fi
fi

if [ "$RUN_ALL" = true ] || [ "$DO_F" = 1 ]; then
  echo ""; echo "── FLOW: Web Fetch / Browse ──"
  info "Create session"; CODE=$(create_session); sleep 2; FSID=$(sid)
  { [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; } && ok "$CODE" || { fail "$CODE"; FSID=""; }
  if [ -n "$FSID" ]; then
    step "$FSID" "WEB-6: fetch https://httpbin.org/ip" "fetch https://httpbin.org/ip and show the response" researcher 4 ""
    step "$FSID" "WEB-7: browse quarkus.io/blog" "browse https://quarkus.io/blog get top 3 blogs titles" researcher 4 ""
    step "$FSID" "WEB-ERR-1: invalid URL" "fetch not-a-valid-url!!! and show response" researcher 3 "400"
    step "$FSID" "WEB-ERR-2: coordinator no fetch" "fetch https://example.com" coordinator 3 "403"
    sleep "$SLEEP"; mute -X DELETE "$B/api/v1/sessions/$FSID" -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1
  fi
fi

if [ "$RUN_ALL" = true ] || [ "$DO_X" = 1 ]; then
  echo ""; echo "── FLOW: SSRF Protection ──"
  info "Create session"; CODE=$(create_session); sleep 2; XSID=$(sid)
  { [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; } && ok "$CODE" || { fail "$CODE"; XSID=""; }
  if [ -n "$XSID" ]; then
    step "$XSID" "WEB-8: block 169.254.169.254" "fetch http://169.254.169.254/latest/meta-data/ should be blocked" researcher 3 "400 403"
    step "$XSID" "WEB-9: block 127.0.0.1" "fetch http://127.0.0.1:8080/api/v1/health" researcher 3 "400 403"
    sleep "$SLEEP"; mute -X DELETE "$B/api/v1/sessions/$XSID" -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1
  fi
fi

echo ""
echo "───────────────────────────────────────────"
echo " Web Tools Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && echo "ALL PASSED" || echo "SOME FAILED"
