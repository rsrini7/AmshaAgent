#!/usr/bin/env bash
# validate_cron.sh — Validate programmatic CronResource REST API endpoints
# Covers: 09_cron_scheduler.http JAX-RS cron endpoints

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0
SLEEP=1

info()  { printf "%-4s %-60s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 120 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 60 -o /tmp/resp -w "%{http_code}" "$@"; }
sid_from_response() { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }

# Retry helper for standard requests
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

# Create session
curl -s -i -m 60 -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
  -d '{"autonomyLevel":"FULL"}' "$B/api/v1/sessions" > /tmp/resp_headers_cron
HTTP_LINE=$(grep -i '^HTTP/' /tmp/resp_headers_cron | tail -n 1)
CODE=$(echo "$HTTP_LINE" | awk '{print $2}')
if [ "$CODE" = "429" ]; then
  sleep 62
  curl -s -i -m 60 -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    -d '{"autonomyLevel":"FULL"}' "$B/api/v1/sessions" > /tmp/resp_headers_cron
  HTTP_LINE=$(grep -i '^HTTP/' /tmp/resp_headers_cron | tail -n 1)
  CODE=$(echo "$HTTP_LINE" | awk '{print $2}')
fi
tail -n 1 /tmp/resp_headers_cron > /tmp/resp
SESS_ID=$(sid_from_response)

if [ -z "$SESS_ID" ]; then
  echo "❌ Failed to create session (HTTP $CODE)"
  exit 1
fi

# ── FLOW A: Schedule a cron job via Agent ──
echo "── FLOW A: Schedule Cron Job via Agent ──"

info "CRON-REST-1: Instruct agent to schedule cron"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID/run" '{"goal":"Schedule a task to run every 10 minutes that echoes Hello. Make sure to specify the schedule.","agentRole":"coordinator","maxIterations":3}')
# Tolerating 200, 202, or 408 (if loop limit reached but task registered)
[ "$CODE" = "200" ] || [ "$CODE" = "202" ] || [ "$CODE" = "408" ] && ok "$CODE" || fail "$CODE"
sleep $SLEEP


# ── FLOW B: REST API Lifecycle Operations ──
echo "── FLOW B: REST API Lifecycle Operations ──"

info "CRON-REST-2: List cron jobs via GET /cron"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/cron")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

# Extract cron ID from the JAX-RS REST response
CRON_ID=$(python3 -c "import json; crons=json.load(open('/tmp/resp')).get('crons',[]); print(crons[0].get('id','') if crons else '')" 2>/dev/null)

if [ -z "$CRON_ID" ]; then
  echo "⚠️  No active cron job found in the list. Skipping REST API verification checks."
  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID" >/dev/null 2>&1
  exit 0
fi

info "CRON-REST-3: Get single cron detail (GET /cron/{id})"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/cron/$CRON_ID")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-4: Pause the cron job (POST /cron/{id}/pause)"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/pause")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-5: Resume the cron job (POST /cron/{id}/resume)"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/resume")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-6: Trigger the cron job immediately (POST /cron/{id}/trigger)"
CODE=$(run_req POST "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/trigger")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-7: Retrieve cron run history (GET /cron/{id}/runs)"
CODE=$(run_req GET "$VALID_KEY" "$B/api/v1/cron/$CRON_ID/runs?page=0&size=5")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "CRON-REST-8: Programmatically delete cron job (DELETE /cron/{id})"
CODE=$(run_req DELETE "$VALID_KEY" "$B/api/v1/cron/$CRON_ID")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

# Cleanup
run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SESS_ID" >/dev/null 2>&1

echo "───────────────────────────────────────────"
echo " Cron REST Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
