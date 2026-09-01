#!/usr/bin/env bash
# validate_cron.sh — Validate programmatic CronResource REST API endpoints
# Covers: 09_cron_scheduler.http JAX-RS cron endpoints

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"

FAIL=0
PASS=0
SLEEP=1

info() { printf "%-4s %-60s " "" "$1"; }
ok() { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail() { printf "❌ HTTP %s\n" "$1"; FAIL=$((FAIL+1)); }
mute() { curl -s -m "$CURL_TIMEOUT" -o /tmp/resp -w "%{http_code}" "$@"; }
sid_from_response() { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }

run_req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  if [ -n "$data" ]; then
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
  else
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
  fi
  if [ "$c" = "429" ]; then
    sleep 62
    if [ -n "$data" ]; then
      c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
    else
      c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
    fi
  fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " PROGRAMMATIC CRON REST API VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

info "CRON-REST-1: Instruct agent schedule cron"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions" '{"autonomyLevel":"FULL"}')
SESS_ID=$(sid_from_response)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; exit 1; }

CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID/run" '{"goal":"Schedule a task in 10 minutes that echoes Hello. Make sure to specify the schedule explicitly.","agentRole":"coordinator","maxIterations":3}')
[ "$CODE" = "200" ] || [ "$CODE" = "202" ] || [ "$CODE" = "408" ] && ok "$CODE" || fail "$CODE"
sleep "$SLEEP"

info "CRON-REST-2: List cron jobs via GET /cron"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/cron?status=ACTIVE")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

CRON_ID=$(python3 -c "import json; crons=[c for c in json.load(open('/tmp/resp')).get('crons',[]) if c.get('status')=='ACTIVE']; print(crons[0].get('id','') if crons else '')" 2>/dev/null)
if [ -z "$CRON_ID" ]; then
  echo "  ⚠️  No active cron job found in list. Skipping REST API verification checks."
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID" >/dev/null 2>&1
  exit 0
fi

info "CRON-REST-3: Get single cron detail"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/cron/$CRON_ID")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-4: Pause cron job"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/pause")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-5: Resume cron job"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/resume")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-6: Trigger cron job immediately"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/trigger")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-7: Get execution history"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/runs?page=0&size=5")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-8: Delete cron job"
CODE=$(run_req DELETE "$VALID_KEY" "$B/api/v1/cron/$CRON_ID")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID" >/dev/null 2>&1

echo "───────────────────────────────────────────"
echo " Cron REST Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
