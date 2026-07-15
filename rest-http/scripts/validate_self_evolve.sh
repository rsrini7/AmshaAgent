#!/usr/bin/env bash
# validate_self_evolve.sh — Validate Self-Evolve (Behavioral Directives) REST surface
# Covers: list/get, synthesize, reconcile, disable, delete + authorization model.
# Zero LLM token cost. Runs instantly. Requires self-evolve enabled + directives seeded.
#
# NOTE: SE-11 (disable) and SE-14 (delete) need a real directive ID. We capture the
# first ID from the synthesize response and reuse it; if synthesis returns empty we skip
# those two checks gracefully (they require an existing directive to act on).

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0

info()  { printf "%-4s %-60s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 120 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }

run_req() {
  local method="$1" key="$2" url="$3"
  local c
  c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" "$url")
  if [ "$c" = "429" ]; then
    sleep 62
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" "$url")
  fi
  echo "$c"
}

echo "═══════════════════════════════════════════"
echo " SELF-EVOLVE (DIRECTIVES) REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

DIR_BASE="$B/api/v1/admin/memory/directives"

# ── FLOW A: LIST & READ ──
echo "── FLOW A: List & Read ──"

info "SE-1: List directives (default limit)"
CODE=$(run_req GET "$VALID_KEY" "$DIR_BASE")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "SE-2: List directives (explicit limit=5)"
CODE=$(run_req GET "$VALID_KEY" "$DIR_BASE?limit=5")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

info "SE-4: Get non-existent directive (404)"
CODE=$(run_req GET "$VALID_KEY" "$DIR_BASE/does-not-exist-0000")
[ "$CODE" = "404" ] && ok "$CODE" || fail "$CODE"

echo ""

# ── FLOW B: WRITE ENDPOINTS — AUTHORIZATION ──
echo "── FLOW B: Write Authorization ──"

info "SE-5: Synthesize (unauthorized CI key)"
CODE=$(run_req POST "$CI_KEY" "$DIR_BASE/synthesize")
[ "$CODE" = "403" ] && ok "$CODE" || fail "$CODE"

info "SE-6: Synthesize (authorized internal key)"
CODE=$(run_req POST "$VALID_KEY" "$DIR_BASE/synthesize")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

# Capture first directive ID from the synthesize response body
DIR_ID=$(grep -o '"id":"[^"]*"' /tmp/resp | head -n 1 | sed 's/"id":"//;s/"//')

info "SE-8: Reconcile (unauthorized CI key)"
CODE=$(run_req POST "$CI_KEY" "$DIR_BASE/reconcile")
[ "$CODE" = "403" ] && ok "$CODE" || fail "$CODE"

info "SE-9: Reconcile (authorized internal key)"
CODE=$(run_req POST "$VALID_KEY" "$DIR_BASE/reconcile")
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

echo ""

# ── FLOW C: PER-DIRECTIVE WRITE — AUTH & NOT-FOUND ──
echo "── FLOW C: Per-Directive Write ──"

info "SE-10: Disable non-existent (404)"
CODE=$(run_req POST "$VALID_KEY" "$DIR_BASE/does-not-exist-0000/disable")
[ "$CODE" = "404" ] && ok "$CODE" || fail "$CODE"

if [ -n "$DIR_ID" ]; then
  info "SE-11: Disable directive (authorized, existing)"
  CODE=$(run_req POST "$VALID_KEY" "$DIR_BASE/$DIR_ID/disable")
  [ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

  info "SE-12: Delete directive (unauthorized CI key)"
  CODE=$(run_req DELETE "$CI_KEY" "$DIR_BASE/$DIR_ID")
  [ "$CODE" = "403" ] && ok "$CODE" || fail "$CODE"

  info "SE-13: Delete directive (authorized, existing)"
  CODE=$(run_req DELETE "$VALID_KEY" "$DIR_BASE/$DIR_ID")
  [ "$CODE" = "204" ] && ok "$CODE" || fail "$CODE"
else
  echo "  ⚠️  SE-11/SE-12/SE-13 skipped — no directive ID captured from synthesize (empty result)"
fi

echo "───────────────────────────────────────────"
echo " Self-Evolve Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
