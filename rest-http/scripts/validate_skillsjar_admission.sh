#!/usr/bin/env bash
# Validates the SkillsJar admission/quarantine API without invoking an LLM.
set +e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"

ADMISSION_URL="$B/api/v1/skills/admission"
REFRESH_URL="$B/api/v1/skills/refresh"
RESPONSE_FILE="$(mktemp "${TMPDIR:-/tmp}/amsha-skillsjar-admission.XXXXXX")"
trap 'rm -f "$RESPONSE_FILE"' EXIT

PASS=0
FAIL=0

ok() {
  printf "✅ %s\n" "$1"
  PASS=$((PASS + 1))
}

fail() {
  printf "❌ %s — %s\n" "$1" "$(head -c 240 "$RESPONSE_FILE")"
  FAIL=$((FAIL + 1))
}

get_admission() {
  curl -s -m 20 -o "$RESPONSE_FILE" -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" \
    -H "Accept: application/json" \
    "$ADMISSION_URL"
}

assert_json() {
  local description="$1"
  local expression="$2"
  if python3 -c '
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
safe_functions = {
    "__builtins__": {},
    "all": all,
    "any": any,
    "isinstance": isinstance,
    "len": len,
    "list": list,
    "str": str,
}
if not eval(sys.argv[2], safe_functions, {"document": document}):
    raise SystemExit(1)
' "$RESPONSE_FILE" "$expression" 2>/dev/null; then
    ok "$description"
  else
    fail "$description"
  fi
}

printf "\n### SKILLSJAR-ADMISSION-1 endpoint responds\n"
CODE="$(get_admission)"
if [ "$CODE" = "200" ]; then
  ok "GET /skills/admission returned HTTP 200"
else
  fail "GET /skills/admission returned HTTP $CODE"
fi

printf "\n### SKILLSJAR-ADMISSION-2 response contract\n"
assert_json "response contains active and quarantined arrays" \
  'isinstance(document.get("active"), list) and isinstance(document.get("quarantined"), list)'

printf "\n### SKILLSJAR-ADMISSION-3 rejected dependency cannot become active\n"
assert_json "algorithmic-art is not active" \
  'not any(item.get("name") == "algorithmic-art" for item in document["active"])'

printf "\n### SKILLSJAR-ADMISSION-4 quarantine entries expose reasons\n"
assert_json "every quarantine entry has name, source, and nonempty reasons" \
  'all(isinstance(item.get("name"), str) and isinstance(item.get("source"), str) and isinstance(item.get("reasons"), list) and len(item["reasons"]) > 0 for item in document["quarantined"])'

printf "\n### SKILLSJAR-ADMISSION-5 refresh preserves admission contract\n"
CODE="$(curl -s -m 20 -o "$RESPONSE_FILE" -w "%{http_code}" \
  -H "X-Amsha-API-Key: $VALID_KEY" \
  -H "Accept: application/json" \
  "$REFRESH_URL")"
if [ "$CODE" = "200" ]; then
  ok "GET /skills/refresh returned HTTP 200"
else
  fail "GET /skills/refresh returned HTTP $CODE"
fi

CODE="$(get_admission)"
if [ "$CODE" = "200" ]; then
  assert_json "admission response remains valid after refresh" \
    'isinstance(document.get("active"), list) and isinstance(document.get("quarantined"), list)'
else
  fail "post-refresh GET /skills/admission returned HTTP $CODE"
fi

printf "\n SkillsJar Admission Results: %d passed, %d failed\n" "$PASS" "$FAIL"
exit "$FAIL"
