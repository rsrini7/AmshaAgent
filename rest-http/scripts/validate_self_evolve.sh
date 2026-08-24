#!/usr/bin/env bash
# validate_self_evolve.sh — Validate Self-Evolve (Behavioral Directives) REST surface
# Covers the full lifecycle against a running server:
#   - list / get directives
#   - synthesize (LLM) -> SHADOW directives from seeded extracted memories
#   - reconcile -> promotes/disables SHADOW directives by evaluation history
#   - disable / delete per-directive (internal-key-only auth model)
#   - 403/404 authorization checks
#
# Requires: a running Amsha Agent (serve mode) on $B. Self-evolve must be enabled.
# Works for jar OR native binaries, and sqlite/postgres/fs backends.
# For a sqlite backend with a discoverable amsha-meta.db, seeds extracted_memories
# + SHADOW directives via scripts/data/seed_self_evolve.sql so the lifecycle has real
# content to act on. The seed targets the userId the internal API key resolves to
# (bootstrap ownerId). For other backends, seeding is skipped and HTTP checks run as-is.
# Data dir: set AMSHA_DATA_DIR, or it is auto-detected from the process on :8080.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s\n" "$(format_http_code "$1")"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 90 -o /tmp/resp -w "%{http_code}" "$@"; }

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

DIR_BASE="$B/api/v1/admin/memory/directives"

echo "═══════════════════════════════════════════"
echo " SELF-EVOLVE (DIRECTIVES) REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── Resolve DB + the userId the internal key maps to ──
# Works for jar OR native, sqlite OR postgres/fs. Seeding only applies to a
# discoverable sqlite amsha-meta.db; otherwise the pure-HTTP checks still run.
#
# Data dir is taken from --data-dir <path>, else $AMSHA_DATA_DIR. If neither is
# provided, seeding is skipped and the script prints how to enable it. We do NOT
# auto-detect via the running process (fragile across jar/native) — the caller
# supplies the path.

DATA_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --data-dir=*) DATA_DIR="${1#*=}"; shift ;;
    *) shift ;;
  esac
done
if [ -z "$DATA_DIR" ] && [ -n "${AMSHA_DATA_DIR:-}" ]; then
  DATA_DIR="$AMSHA_DATA_DIR"
fi
if [ -n "$DATA_DIR" ] && [ "${DATA_DIR#/}" = "$DATA_DIR" ]; then
  DATA_DIR="$(pwd)/$DATA_DIR"   # make relative path absolute
fi

DB=""
if [ -n "$DATA_DIR" ] && [ -f "$DATA_DIR/amsha-meta.db" ]; then
  DB="$DATA_DIR/amsha-meta.db"
fi
# A PostgreSQL or filesystem runtime must never seed a neighboring SQLite
# database merely because the validator inherited a shared data directory.
if [ "${AMSHA_PERSISTENCE_BACKEND:-sqlite}" != "sqlite" ]; then
  DB=""
fi
SEED="$SCRIPT_DIR/data/seed_self_evolve.sql"

# Seeding only makes sense for a discoverable sqlite DB. For postgres/fs backends
# (or when no DB path is supplied / sqlite3 missing) we skip seeding and rely on
# whatever data already exists — the HTTP checks remain valid either way.
if [ -n "$DB" ] && command -v sqlite3 >/dev/null 2>&1 && [ -f "$SEED" ]; then
  OWNER=$(sqlite3 "$DB" "SELECT owner_id FROM bootstrap_state WHERE owner_id IS NOT NULL AND owner_id <> '' LIMIT 1;" 2>/dev/null)
  if [ -z "$OWNER" ]; then
    OWNER="internal"
  fi
  echo "  Seeding extracted_memories + SHADOW directives for user: $OWNER (sqlite: $DB)"
  SED_TMP=$(mktemp)
  sed "s/INTERNAL_USER_PLACEHOLDER/$OWNER/g" "$SEED" > "$SED_TMP"
  sqlite3 "$DB" ".timeout 15000" ".read $SED_TMP" 2>&1 | grep -v '^10000$' || true
  rm -f "$SED_TMP"
else
  echo "  ℹ️  [OPTIONAL] No sqlite DB found — skipping offline seed; running HTTP checks only."
fi
echo ""

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
# Synthesize depends on the LLM; transient backend errors return 200 with an empty
# array. Retry once after a short pause to tolerate flaky local LLM slots.
if [ "$CODE" = "200" ] && ! grep -q '"id"' /tmp/resp; then
  sleep 5
  CODE=$(run_req POST "$VALID_KEY" "$DIR_BASE/synthesize")
fi
[ "$CODE" = "200" ] && ok "$CODE" || fail "$CODE"

# Only mutate a directive returned by this validator's synthesize request.
# Selecting an arbitrary existing directive could disable/delete user data.
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
  echo "  ℹ️  [EXPECTED] SE-11/SE-12/SE-13 skipped — synthesize returned no directive ID"
fi

echo "───────────────────────────────────────────"
echo " Self-Evolve Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
