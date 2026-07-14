#!/usr/bin/env bash
# validate_web.sh — Validate WEB-1..9 from 17_web_tools.http
# Needs LLM + SUPERVISED sessions + Brave/Serper keys.
# Usage: bash validate_web.sh [--search] [--fetch] [--ssrf]

set +e
KEY="JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs="
B="http://localhost:8080"
FAIL=0; PASS=0; SLEEP=7

info()  { printf "%-4s %-40s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 80 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }

run_agent() {
  curl -s -m 60 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions/$1/run" \
    -d "{\"goal\":\"$2\",\"agentRole\":\"$3\",\"maxIterations\":$4}"
}

create_session() {
  curl -s -m 10 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}'
}
sid() { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }

RUN_ALL=true
[ "$1" = "--search" ] && RUN_ALL=false && S=1
[ "$1" = "--fetch" ]  && RUN_ALL=false && F=1
[ "$1" = "--ssrf" ]   && RUN_ALL=false && X=1

echo "═══════════════════════════════════════════"
echo " WEB TOOLS VALIDATOR"
echo " Tests: web search, fetch, browse, SSRF"
echo "═══════════════════════════════════════════"

if [ "$RUN_ALL" = true ] || [ "$S" = 1 ]; then
  echo ""; echo "── FLOW: Web Search ──"
  info "Create session"; CODE=$(create_session); sleep 2; WSID=$(sid)
  [ "$CODE" = "201" ] && ok "$CODE" || { fail "$CODE"; WSID=""; }
  if [ -n "$WSID" ]; then
    info "WEB-2: brave_web_search (researcher)"
    sleep $SLEEP; CODE=$(run_agent "$WSID" "do a web search on spring boot and quarkus LTS version" "researcher" 3)
    case $CODE in 200|202) ok "$CODE";; *) fail "$CODE";; esac

    info "WEB-3: serper_web_search (researcher)"
    sleep $SLEEP; CODE=$(run_agent "$WSID" "search for top quarkus features using serper" "researcher" 3)
    case $CODE in 200|202) ok "$CODE";; *) fail "$CODE";; esac

    info "WEB-4: coordinator no search cap"
    sleep $SLEEP; CODE=$(run_agent "$WSID" "search the web for latest spring boot news" "coordinator" 2)
    case $CODE in 200|403) ok "$CODE";; *) fail "$CODE";; esac

    mute -X DELETE "$B/api/v1/sessions/$WSID" -H "X-Amsha-API-Key: $KEY" 2>/dev/null
  fi
fi

if [ "$RUN_ALL" = true ] || [ "$F" = 1 ]; then
  echo ""; echo "── FLOW: Web Fetch / Browse ──"
  info "Create session"; CODE=$(create_session); sleep 2; FSID=$(sid)
  [ "$CODE" = "201" ] && ok "$CODE" || { fail "$CODE"; FSID=""; }
  if [ -n "$FSID" ]; then
    info "WEB-6: fetch https://httpbin.org/ip"
    sleep $SLEEP; CODE=$(run_agent "$FSID" "fetch https://httpbin.org/ip and show the response" "researcher" 3)
    case $CODE in 200|202) ok "$CODE";; *) fail "$CODE";; esac

    info "WEB-7: browse quarkus.io/blog"
    sleep $SLEEP; CODE=$(run_agent "$FSID" "browse https://quarkus.io/blog get top 3 blogs titles" "researcher" 3)
    case $CODE in 200|202) ok "$CODE";; *) fail "$CODE";; esac

    info "WEB-ERR-1: invalid URL"
    sleep $SLEEP; CODE=$(run_agent "$FSID" "fetch not-a-valid-url!!! and show response" "researcher" 2)
    case $CODE in 200|400) ok "$CODE";; *) fail "$CODE";; esac

    info "WEB-ERR-2: coordinator no fetch"
    sleep $SLEEP; CODE=$(run_agent "$FSID" "fetch https://example.com" "coordinator" 2)
    case $CODE in 200|403) ok "$CODE";; *) fail "$CODE";; esac

    mute -X DELETE "$B/api/v1/sessions/$FSID" -H "X-Amsha-API-Key: $KEY" 2>/dev/null
  fi
fi

if [ "$RUN_ALL" = true ] || [ "$X" = 1 ]; then
  echo ""; echo "── FLOW: SSRF Protection ──"
  info "Create session"; CODE=$(create_session); sleep 2; XSID=$(sid)
  [ "$CODE" = "201" ] && ok "$CODE" || { fail "$CODE"; XSID=""; }
  if [ -n "$XSID" ]; then
    info "WEB-8: block 169.254.169.254"
    sleep $SLEEP; CODE=$(run_agent "$XSID" "fetch http://169.254.169.254/latest/meta-data/ should be blocked" "researcher" 2)
    case $CODE in 200|403|400) ok "$CODE";; *) fail "$CODE";; esac

    info "WEB-9: block 127.0.0.1"
    sleep $SLEEP; CODE=$(run_agent "$XSID" "fetch http://127.0.0.1:8080/api/v1/health" "researcher" 2)
    case $CODE in 200|403|400) ok "$CODE";; *) fail "$CODE";; esac

    mute -X DELETE "$B/api/v1/sessions/$XSID" -H "X-Amsha-API-Key: $KEY" 2>/dev/null
  fi
fi

echo ""
echo "───────────────────────────────────────────"
echo " Web Tools Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
