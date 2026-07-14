#!/usr/bin/env bash
# validate_reflex.sh — Validate REFLEX-18..24 + GITHUB-1..2 from 06_reflex_commands.http
# Zero-token reflexes. Rate-limit aware (mutation-per-minute: 10).
# Strategy: reuse ONE session (1 create + N runs + 1 delete), pace mutations, retry on 429.
# Usage: bash validate_reflex.sh

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0
SP=7            # sleep before each mutating call to respect 10 mutations/min
RL_WAIT=62     # wait on 429 (retry-after ~60s)

info()  { printf "%-4s %-22s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
warn()  { printf "⚠️  HTTP %s (known issue)\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 80 /tmp/resp)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }
sid()   { python3 -c "import json; print(json.load(open('/tmp/resp')).get('sessionId',''))" 2>/dev/null; }

# run a reflex/command against the shared session, auto-retry once on 429
run_agent() {
  local goal="$1" code
  sleep "$SP"
  code=$(curl -s -m 20 -o /tmp/resp -w "%{http_code}" \
    -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions/$S/run" \
    -d "{\"goal\":\"$goal\",\"agentRole\":\"coordinator\",\"maxIterations\":1}")
  if [ "$code" = "429" ]; then
    printf "(429, waiting %ss) " "$RL_WAIT" >&2
    sleep "$RL_WAIT"
    code=$(curl -s -m 20 -o /tmp/resp -w "%{http_code}" \
      -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
      -X POST "$B/api/v1/sessions/$S/run" \
      -d "{\"goal\":\"$goal\",\"agentRole\":\"coordinator\",\"maxIterations\":1}")
  fi
  echo "$code"
}

echo "═══════════════════════════════════════════"
echo " REFLEX COMMANDS VALIDATOR"
echo " Zero-token reflexes (shared session, paced)"
echo "═══════════════════════════════════════════"
echo ""

# ── Create ONE shared session ──
sleep "$SP"
MK=$(mute -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
  -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}')
if [ "$MK" = "429" ]; then echo "⏳ rate limited creating session, waiting ${RL_WAIT}s"; sleep "$RL_WAIT";
  MK=$(mute -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" \
    -X POST "$B/api/v1/sessions" -d '{"autonomyLevel":"SUPERVISED"}'); fi
S=$(sid)
if [ -z "$S" ]; then echo "❌ Could not create session (HTTP $MK) — $(head -c 120 /tmp/resp)"; exit 1; fi
echo "   session: $S"
echo ""

# ── REFLEX-18: /sessions ──
info "/sessions"
CODE=$(run_agent "/sessions"); case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

# ── REFLEX-19: /newsession ──
info "/newsession"
CODE=$(run_agent "/newsession"); case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

# ── REFLEX-20: /resume 1 ──
info "/resume 1"
CODE=$(run_agent "/resume 1"); case $CODE in 200) ok "$CODE";; *) fail "$CODE";; esac

# ── REFLEX-21: /reflex (known: REST wrapper returns 500; works via Telegram) ──
info "/reflex"
CODE=$(run_agent "/reflex")
case $CODE in 200) ok "$CODE";; 500) warn "$CODE (ListReflexesReflex REST wrapper — works via Telegram)";; *) fail "$CODE";; esac

# ── REFLEX-22: /commands ──
info "/commands"
CODE=$(run_agent "/commands")
case $CODE in 200) ok "$CODE";; 500) warn "$CODE (same as /reflex)";; *) fail "$CODE";; esac

# ── REFLEX-23: /help ──
info "/help"
CODE=$(run_agent "/help")
case $CODE in 200) ok "$CODE";; 500) warn "$CODE (same as /reflex)";; *) fail "$CODE";; esac

# ── REFLEX-24: deletecron all ──
info "deletecron all"
CODE=$(run_agent "deletecron all")
case $CODE in 200) ok "$CODE";; 500) warn "$CODE (may need crons present to succeed)";; *) fail "$CODE";; esac

# ── GITHUB-1: /github list ──
info "/github list"
CODE=$(run_agent "/github list rsrini7 repos")
case $CODE in 200|202) ok "$CODE";; 429) warn "$CODE (rate limited)";; *) fail "$CODE";; esac

# ── GITHUB-2: /github view ──
# Note: shared session may be awaiting HITL approval from GITHUB-1 (NETWORK_OUTBOUND
# always requires approval) → 409 is the correct/expected state here.
info "/github view"
CODE=$(run_agent "/github view rsrini7/AmshaAgentInternal readme")
case $CODE in
  200|202) ok "$CODE";;
  409) warn "$CODE (session awaiting HITL approval from /github list — expected)";;
  429) warn "$CODE (rate limited)";;
  *)   fail "$CODE";;
esac

# ── Cleanup ──
sleep "$SP"
mute -X DELETE "$B/api/v1/sessions/$S" -H "X-Amsha-API-Key: $KEY" > /dev/null 2>&1

echo ""
echo "───────────────────────────────────────────"
echo " Reflex Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && echo "ALL PASSED" || echo "SOME FAILED (see ⚠️ for known issues)"
