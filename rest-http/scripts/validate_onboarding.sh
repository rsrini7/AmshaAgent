#!/usr/bin/env bash
# validate_onboarding.sh — Validate the P110 durable-draft onboarding API, provider
# catalog, settings connection-network-profile, the new bootstrap /recovery authz guard,
# and the new ActiveWorkspaceFilter (X-Amsha-Workspace-Id) behaviour.
#
# Covers (under /api/v1):
#   GET    /onboarding/status
#   GET    /onboarding/drafts
#   POST   /onboarding/drafts
#   GET    /onboarding/drafts/{draftId}
#   PUT    /onboarding/drafts/{draftId}/steps/{stepId}
#   POST   /onboarding/drafts/{draftId}/commit        (negative + successful saga)
#   GET    /onboarding/health/routing
#   GET    /onboarding/provider-catalog
#   GET    /onboarding/export
#   POST   /onboarding/import + GET /onboarding/export (round-trip verification)
#   POST   /onboarding/drafts/discard                  (cleanup)
#   PATCH  /settings/connection-network-profile        (valid-profile shape)
#   POST   /onboarding/bootstrap/recovery              (authz guard: non-owner -> 403)
#   GET    /onboarding/status + X-Amsha-Workspace-Id   (401 / 403 workspace scoping)
#
# Zero LLM token cost. Uses the skip-onboarding cookie when enabled; otherwise it
# requires the app bootstrapped with a usable VALID_KEY owner.
# The legacy numbered-step wizard (/onboarding/step/{N}, /onboarding/complete) was
# REMOVED in P110 and is intentionally NOT exercised here.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
B="${TEST_BASE:-$B}"
R="$B/api/v1"
FAIL=0; PASS=0
COOKIE_JAR=$(mktemp /tmp/amsha-onboarding-cookie.XXXXXX)
HEADERS_FILE=$(mktemp /tmp/amsha-onboarding-headers.XXXXXX)
trap 'rm -f "$COOKIE_JAR" "$HEADERS_FILE" /tmp/resp' EXIT

info()  { printf "%-4s %-62s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 140 /tmp/resp)"; FAIL=$((FAIL+1)); }
exp()   { if [ "$1" = "$2" ]; then ok "$1"; else fail "$1 (expected $2)"; fi }
# accept the response code if it is one of the accepted (space separated) codes
code_in() {
  local got="$1"; local accepted="$2"
  for c in $accepted; do [ "$got" = "$c" ] && { ok "$got"; return; }; done
  fail "$got (expected one of $accepted)"
}
mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }
req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  local -a auth_args=()
  if [ "$key" = "$VALID_KEY" ] && [ -s "$COOKIE_JAR" ]; then
    auth_args=(-b "$COOKIE_JAR")
  elif [ -n "$key" ]; then
    auth_args=(-H "X-Amsha-API-Key: $key")
  fi
  if [ -n "$data" ]; then
    c=$(mute -X "$method" "${auth_args[@]}" -H "Content-Type: application/json" -d "$data" "$url")
  else
    c=$(mute -X "$method" "${auth_args[@]}" "$url")
  fi
  if [ "$c" = "429" ]; then
    sleep 62
    if [ -n "$data" ]; then
      c=$(mute -X "$method" "${auth_args[@]}" -H "Content-Type: application/json" -d "$data" "$url")
    else
      c=$(mute -X "$method" "${auth_args[@]}" "$url")
    fi
  fi
  echo "$c"
}
json() { python3 -c "import json,sys; print(json.load(open('/tmp/resp')).get('$1',''))" 2>/dev/null; }
json_assert() {
  local expression="$1"
  python3 -c "import json; d=json.load(open('/tmp/resp')); assert $expression" 2>/dev/null
}

echo "════════════════════════════════════════════════════════"
echo " P110 ONBOARDING / PROVIDER / WORKSPACE VALIDATOR"
echo "════════════════════════════════════════════════════════"
echo ""

# ── FLOW 0: pre-auth skip-onboarding handshake ──
echo "── FLOW 0: Pre-auth Skip-Onboarding Handshake ──"
info "SKIP-1: GET /onboarding/status without credentials"
CODE=$(mute -D "$HEADERS_FILE" -c "$COOKIE_JAR" "$R/onboarding/status")
if [ "$CODE" = "200" ]; then
  if json_assert "d.get('skipOnboarding') is True and d.get('credentialsOk') is True"; then
    ok "$CODE + skip flags"
  else
    fail "$CODE (missing skipOnboarding/credentialsOk flags)"
  fi
  info "SKIP-2: status issues AMSHA_API_KEY cookie"
  if grep -qi '^Set-Cookie: AMSHA_API_KEY=' "$HEADERS_FILE" && [ -s "$COOKIE_JAR" ]; then
    ok "Set-Cookie"
  else
    fail "missing authentication cookie"
  fi
elif [ "$CODE" = "401" ] && [ "${EXPECT_SKIP_ONBOARDING:-false}" != "true" ]; then
  ok "$CODE (skip disabled)"
  : > "$COOKIE_JAR"
else
  fail "$CODE (expected 200 when skip onboarding is enabled)"
fi

# ── FLOW 1: draft lifecycle (authenticated owner/cookie) ──
echo "── FLOW 1: Onboarding Draft Lifecycle ──"
info "ONB-1: GET /onboarding/status (authenticated)"
CODE=$(req GET "$VALID_KEY" "$R/onboarding/status"); exp "$CODE" "200"
info "ONB-2: POST /onboarding/drafts (create/resume)"
CODE=$(req POST "$VALID_KEY" "$R/onboarding/drafts")
if [ "$CODE" = "200" ]; then
  ok "$CODE"
  DID=$(json draftId)
  OWNER_ID=$(json ownerId)
else
  fail "$CODE"
  DID=""
  OWNER_ID=""
fi
info "ONB-3: GET /onboarding/drafts (active id)"
CODE=$(req GET "$VALID_KEY" "$R/onboarding/drafts"); exp "$CODE" "200"
if [ -n "$DID" ]; then
  info "ONB-4: GET /onboarding/drafts/{id}"
  CODE=$(req GET "$VALID_KEY" "$R/onboarding/drafts/$DID"); exp "$CODE" "200"
  info "ONB-5: POST /onboarding/drafts/{id}/commit before review"
  CODE=$(req POST "$VALID_KEY" "$R/onboarding/drafts/$DID/commit"); exp "$CODE" "409"
  info "ONB-6: advance connections -> routes -> review"
  CODE=$(req PUT "$VALID_KEY" "$R/onboarding/drafts/$DID/steps/connections" '{"selectedNonSecretIds":[]}'); exp "$CODE" "200"
  CODE=$(req PUT "$VALID_KEY" "$R/onboarding/drafts/$DID/steps/routes" '{"selectedNonSecretIds":[]}'); exp "$CODE" "200"
  CODE=$(req PUT "$VALID_KEY" "$R/onboarding/drafts/$DID/steps/review" '{"selectedNonSecretIds":[]}'); exp "$CODE" "200"
  info "ONB-7: POST /onboarding/drafts/{id}/commit successfully"
  CODE=$(req POST "$VALID_KEY" "$R/onboarding/drafts/$DID/commit")
  if [ "$CODE" = "200" ] && json_assert "d.get('state') == 'COMPLETED'"; then
    ok "$CODE + COMPLETED"
  else
    fail "$CODE (expected completed saga)"
  fi
fi
info "ONB-8: GET /onboarding/health/routing"
CODE=$(req GET "$VALID_KEY" "$R/onboarding/health/routing"); exp "$CODE" "200"
info "ONB-9: GET /onboarding/provider-catalog (typed descriptors)"
CODE=$(req GET "$VALID_KEY" "$R/onboarding/provider-catalog")
if [ "$CODE" = "200" ] && json_assert "isinstance(d,list) and len(d)>0 and {'openai','groq'}.issubset({x.get('id') for x in d}) and all(isinstance(x.get('modelCatalog'),list) for x in d)"; then
  ok "$CODE + descriptors"
else
  fail "$CODE (catalog must contain typed openai/groq descriptors)"
fi

# Import/export round trip with unique IDs proves persistence instead of merely route reachability.
IMPORT_SUFFIX="rest$(date +%s)"
IMPORT_CONN="conn_$IMPORT_SUFFIX"
IMPORT_PROFILE="profile_$IMPORT_SUFFIX"
IMPORT_ROUTE="route_$IMPORT_SUFFIX"
IMPORT_JSON="{\"connections\":[{\"id\":\"$IMPORT_CONN\",\"displayName\":\"REST import\",\"providerAdapterId\":\"ollama\"}],\"profiles\":[{\"id\":\"$IMPORT_PROFILE\",\"connectionId\":\"$IMPORT_CONN\",\"modelId\":\"llama3\"}],\"routes\":[{\"id\":\"$IMPORT_ROUTE\",\"scopeType\":\"USER\",\"scopeId\":\"${OWNER_ID:-internal}\",\"targetKind\":\"AGENT_LOOP\",\"orderedProfileIds\":[\"$IMPORT_PROFILE\"],\"enabled\":true}]}"
info "ONB-10: POST /onboarding/import (non-empty profile graph)"
CODE=$(req POST "$VALID_KEY" "$R/onboarding/import" "$IMPORT_JSON")
if [ "$CODE" = "200" ] && json_assert "d.get('imported') is True"; then ok "$CODE + imported"; else fail "$CODE"; fi
info "ONB-11: GET /onboarding/export contains imported graph"
CODE=$(req GET "$VALID_KEY" "$R/onboarding/export")
if [ "$CODE" = "200" ] && json_assert "'$IMPORT_CONN' in {x.get('id') for x in d.get('connections',[])} and '$IMPORT_PROFILE' in {x.get('id') for x in d.get('profiles',[])} and '$IMPORT_ROUTE' in {x.get('id') for x in d.get('routes',[])}"; then
  ok "$CODE + round-trip"
else
  fail "$CODE (imported graph not exported)"
fi

# ── FLOW 2: settings connection-network-profile (new in P110) ──
echo "── FLOW 2: Settings Connection Network Profile ──"
info "NET-1: PATCH /settings/provider opens draft with connection"
CODE=$(req PATCH "$VALID_KEY" "$R/settings/provider" '{"provider":"ollama","model":"llama3"}'); exp "$CODE" "200"
info "NET-1b: create active draft and stage a connection selection"
CODE=$(req POST "$VALID_KEY" "$R/onboarding/drafts")
NET_DRAFT_ID=$(json draftId)
if [ "$CODE" = "200" ] && [ -n "$NET_DRAFT_ID" ]; then
  CONNECTION_SELECTION='{"selectedNonSecretIds":["{\"connections\":[{\"provider\":\"ollama\",\"modelId\":\"llama3\",\"contextWindow\":8192,\"structuredOutputMode\":\"NATIVE\",\"capabilities\":[\"tool-calling\"]}],\"concernRoutes\":{\"agentLoop\":[0],\"compaction\":[0],\"classifier\":[0],\"embedding\":[0]}}"]}'
  CODE=$(req PUT "$VALID_KEY" "$R/onboarding/drafts/$NET_DRAFT_ID/steps/connections" "$CONNECTION_SELECTION")
  exp "$CODE" "200"
else
  fail "$CODE (active draft was not created)"
fi
info "NET-2: PATCH /settings/connection-network-profile persists valid profile"
CODE=$(req PATCH "$VALID_KEY" "$R/settings/connection-network-profile" '{"connectionIndex":0,"networkProfileJson":"{\"egressMode\":\"ALLOW_ALL\",\"tlsTrustMode\":\"SYSTEM\"}"}')
if [ "$CODE" = "200" ] && json_assert "d.get('updated') is True and bool(d.get('draftId'))"; then
  ok "$CODE + updated"
else
  fail "$CODE (valid network profile was not persisted)"
fi
info "NET-3: malformed network profile is rejected"
CODE=$(req PATCH "$VALID_KEY" "$R/settings/connection-network-profile" '{"connectionIndex":0,"networkProfileJson":"not-json"}'); exp "$CODE" "400"

# ── FLOW 3: bootstrap /recovery authz + state guards (new in P110) ──
echo "── FLOW 3: Bootstrap Recovery Authz/State Guards ──"
info "BOOT-1: POST /onboarding/bootstrap/recovery with invalid key -> 401"
CODE=$(req POST "$INVALID_KEY" "$R/onboarding/bootstrap/recovery"); exp "$CODE" "401"
info "BOOT-2: POST /onboarding/bootstrap/recovery with valid key (confirmed state) -> 400"
CODE=$(req POST "$VALID_KEY" "$R/onboarding/bootstrap/recovery")
if [ "$CODE" = "400" ] && json_assert "d.get('code') == 'INVALID_STATE'"; then
  ok "$CODE + INVALID_STATE"
else
  fail "$CODE (expected INVALID_STATE)"
fi

# ── FLOW 4: ActiveWorkspaceFilter (X-Amsha-Workspace-Id) — new in P110 ──
echo "── FLOW 4: Workspace Header Scoping (ActiveWorkspaceFilter) ──"
info "WS-1: GET /onboarding/status + bogus workspace, no key -> 401"
CODE=$(mute -X GET -H "X-Amsha-Workspace-Id: ws_validator_bogus" "$R/onboarding/status"); exp "$CODE" "401"
info "WS-2: GET /onboarding/status + bogus workspace, authenticated -> 403"
if [ -s "$COOKIE_JAR" ]; then
  CODE=$(mute -X GET -b "$COOKIE_JAR" -H "X-Amsha-Workspace-Id: ws_validator_bogus" "$R/onboarding/status")
else
  CODE=$(mute -X GET -H "X-Amsha-Workspace-Id: ws_validator_bogus" -H "X-Amsha-API-Key: $VALID_KEY" "$R/onboarding/status")
fi
exp "$CODE" "403"
info "WS-3: GET /onboarding/status + valid key, no workspace header -> 200"
CODE=$(req GET "$VALID_KEY" "$R/onboarding/status"); exp "$CODE" "200"

# ── cleanup ──
if [ -n "${DID:-}" ]; then
  req POST "$VALID_KEY" "$R/onboarding/drafts/discard" >/dev/null 2>&1
fi

echo "────────────────────────────────────────────────────"
echo " ONBOARDING RESULTS: $PASS passed, $FAIL failed"
echo "────────────────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
