#!/usr/bin/env bash
# validate_data_collection.sh — Generate automated classifier data collection ground truth
# Needs: serve mode with AMSHA_TEST_DATA_COLLECTION_ENABLED=true

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0; RL_WAIT=62

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s\n" "$1"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m "$CURL_TIMEOUT" -o /tmp/resp -w "%{http_code}" "$@"; }
sid()   { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }
is_sandbox_offline() { grep -q "SANDBOX_OFFLINE" /tmp/resp; }

create_session() {
  local c
  c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    -H "X-Amsha-Test-Expected-Tool-Risk: LOW_RISK" \
    -H "X-Amsha-Test-Expected-Answer-Quality: ACCEPTABLE" \
    -H "X-Amsha-Test-Expected-Intent: coder" \
    "$B/api/v1/sessions" -d '{"autonomyLevel":"FULL"}')
    if [ "$c" = "429" ]; then sleep $RL_WAIT; c=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" -H "X-Amsha-Test-Expected-Tool-Risk: LOW_RISK" -H "X-Amsha-Test-Expected-Answer-Quality: ACCEPTABLE" -H "X-Amsha-Test-Expected-Intent: coder" "$B/api/v1/sessions" -d '{"autonomyLevel":"FULL"}'); fi
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
  if [ "$c" = "429" ]; then sleep $RL_WAIT
    if [ -n "$data" ]; then c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
    else c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url"); fi
  fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " DATA COLLECTION REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

if [ "${AMSHA_TEST_DATA_COLLECTION_ENABLED:-false}" != "true" ]; then
  echo "  ℹ️  [OPTIONAL] Skipped: amsha.test-data-collection.enabled is false"
  echo "      To run classifier data collection, restart server with AMSHA_TEST_DATA_COLLECTION_ENABLED=true."
  exit 0
fi

if ! curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/v1/models >/dev/null 2>&1; then
  echo "❌ OMLX server is not running on http://127.0.0.1:8000. Please start 'omlx serve' in a separate terminal before running this script."
  exit 1
fi

info "DATA-1: create session"
DATA_DIR="${AMSHA_DATA_DIR:-$SCRIPT_DIR/../../data}"
  rm -f "$DATA_DIR/classifier-training/review-working/tool-risk.autotest.jsonl"
  rm -f "$DATA_DIR/classifier-training/review-working/answer-quality.autotest.jsonl"
  rm -f "$DATA_DIR/classifier-training/review-working/intent.autotest.jsonl"
CODE=$(create_session); SID=$(sid)
[ "$CODE" = "201" ] || [ "$CODE" = "200" ] && ok "$CODE" || { fail "$CODE"; SID=""; }
if [ -n "$SID" ]; then
  info "DATA-2: submit synthetic low-risk tool call (trigger tool_risk and answer_quality)"
  # The Python gate fails on PII (email, uuid, phone, home path). We ensure parameter is safe.
  CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" \
    "$B/api/v1/sessions/$SID/run-async" \
    -d '{"goal":"Call the sandbox_exec tool with code=\"echo safe_test_data\" and language=\"bash\". After the tool returns successfully, you MUST call FINISH.","agentRole":"coder","maxIterations":3}')
  [ "$CODE" = "202" ] || [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

  info "DATA-3: wait for completion"
  ST=""; for i in $(seq 1 60); do
    mute -X GET -H "X-Amsha-API-Key: $VALID_KEY" "$B/api/v1/sessions/$SID/status/coder" >/dev/null 2>&1
    ST=$(python3 -c "import json; print(json.load(open('/tmp/resp')).get('status',''))" 2>/dev/null)
    if [ "$ST" = "AWAITING_APPROVAL" ]; then
      mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" -H "Content-Type: application/json" -d '{"goal":"approve","agentRole":"coder"}' "$B/api/v1/sessions/$SID/run-async" >/dev/null 2>&1
    fi
    [ "$ST" = "COMPLETED" ] || [ "$ST" = "FAILED" ] && break
    sleep 3
  done
  
  if [ "$ST" = "COMPLETED" ]; then
    ok "$ST"
  elif [ "$ST" = "FAILED" ] && ( is_sandbox_offline || grep -q "Connection refused" /tmp/resp ); then
    ok "FAILED (tolerated SANDBOX/LLM_OFFLINE)"
  else
    fail "$ST"
  fi

  run_req DELETE "$VALID_KEY" "$B/api/v1/sessions/$SID" >/dev/null 2>&1

  info "DATA-4: execute de-identification gates on generated files"
  DATA_DIR="${AMSHA_DATA_DIR:-$SCRIPT_DIR/../../data}"
  for task in intent tool-risk answer-quality; do
    source_file="$DATA_DIR/classifier-training/review-working/$task.autotest.jsonl"
    packet_file="$DATA_DIR/classifier-training/review-working/$task.packet.jsonl"
    rm -f "$packet_file"
    if [ -f "$source_file" ] && bash "$SCRIPT_DIR/../../scripts/classifier_training.sh" prepare-review \
      "$source_file" "$packet_file" >/dev/null 2>&1; then
      ok "$task GATE PASSED"
    else
      fail "$task GATE FAILED"
    fi
  done
fi

echo "───────────────────────────────────────────"
info "DATA-5: generated JSONL files are non-empty and parseable"
for task in intent tool-risk answer-quality; do
  file="$DATA_DIR/classifier-training/review-working/$task.autotest.jsonl"
  if [ -s "$file" ] && python3 - "$file" <<'PY'
import json
import re
import sys

required = {"id", "task", "label", "split", "input", "source", "review"}
identifiers = set()
pii = re.compile(
    r"(?:[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|"
    r"/Users/|/home/|\b\d{3}[-. ]\d{3}[-. ]\d{4}\b|"
    r"\b[0-9a-f]{8}-[0-9a-f-]{27,}\b|\b\d{9,10}:[A-Za-z0-9_-]{20,}\b)"
)

for line in open(sys.argv[1], encoding="utf-8"):
    record = json.loads(line)
    if not required.issubset(record) or record["id"] in identifiers:
        raise SystemExit(1)
    identifiers.add(record["id"])
    if pii.search(json.dumps(record, ensure_ascii=False, sort_keys=True)):
        raise SystemExit(1)
PY
  then
    ok "$task schema"
  else
    fail "$task schema"
  fi
done

echo " DATA COLLECTION Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
