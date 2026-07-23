#!/usr/bin/env bash
# validate_identity_access.sh — Validate P109 Identity / Access / Workspace surface
# Covers: 19_identity_access.http, 20_auth_oauth_session.http (automatable subset).
# Zero LLM token cost. Requires a FRESH serve-mode DB (bootstrap UNCLAIMED).
#
# It claims the first owner, extracts the raw key from the Set-Cookie header
# (the key is intentionally absent from the JSON body), and exercises:
#   bootstrap status/claim/confirm, /identity/me, workspace members,
#   invitations, personal API credentials, last-owner guard, ownership
#   transfer negative path, provider discovery, OAuth negative paths,
#   and revoke-all-sessions. Workspace credential rotation runs last because
#   it revokes the bootstrap owner's active API credential.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
# Allow overriding the target (e.g. TEST_BASE=http://localhost:8081)
B="${TEST_BASE:-$B}"
# This deployment serves REST under the /api/v1 context path.
R="$B/api/v1"
INVALID_KEY="sk-invalid-amsha-test-key-zzzzzzzzzzzzzzzz"
FAIL=0; PASS=0

info()  { printf "%-4s %-62s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "❌ HTTP %s — %s\n" "$1" "$(head -c 140 /tmp/resp)"; FAIL=$((FAIL+1)); }
exp() { # exp GOT EXPECTED LABEL
  if [ "$1" = "$2" ]; then ok "$1"; else fail "$1 (expected $2)"; fi
}

mute()  { curl -s -m 15 -o /tmp/resp -w "%{http_code}" "$@"; }
# body + headers (for cookie extraction): -D headers only, -o body only
raw()   { curl -s -m 15 -D /tmp/headers -o /tmp/resp -w "%{http_code}" "$@"; }

# Generic request with API key + optional JSON body
req() {
  local method="$1" key="$2" url="$3" data="$4"
  local c
  if [ -n "$data" ]; then
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url")
  elif [ -n "$key" ]; then
    c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url")
  else
    c=$(mute -X "$method" "$url")
  fi
  # simple 429 backoff so the suite survives rate limits
  if [ "$c" = "429" ]; then sleep 62;
    if [ -n "$data" ]; then c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" -H "Content-Type: application/json" -d "$data" "$url");
    elif [ -n "$key" ]; then c=$(mute -X "$method" -H "X-Amsha-API-Key: $key" "$url");
    else c=$(mute -X "$method" "$url"); fi
  fi
  echo "$c"
}

json() { python3 -c "import json,sys; print(json.load(open('/tmp/resp')).get('$1',''))" 2>/dev/null; }

echo "════════════════════════════════════════════════════════"
echo " P109 IDENTITY / ACCESS VALIDATOR"
echo "════════════════════════════════════════════════════════"
echo ""

# ── FLOW A: FIRST-OWNER BOOTSTRAP ──
echo "── FLOW A: First-Owner Bootstrap Claim ──"

info "A1: bootstrap status (fresh)"
CODE=$(req GET "" "$R/onboarding/bootstrap/status")
ST=$(json status)
if [ "$CODE" = "200" ] && [ "$ST" = "UNCLAIMED" ]; then
  ok "$CODE ($ST)"
else
  fail "$CODE ($ST; requires a fresh UNCLAIMED database)"
  echo "Identity validation stopped: bootstrap ownership tests are destructive and require a fresh database."
  exit 1
fi

info "A2: claim first owner"
HTTP=$(raw -X POST "$R/onboarding/bootstrap/claim")
CODE=$(echo "$HTTP" | tail -n1)
# extract raw key from: Set-Cookie: AMSHA_API_KEY=sk-...; ...
OWNER_KEY=$(grep -i 'set-cookie' /tmp/headers | grep -i 'AMSHA_API_KEY=' \
            | sed -E 's/.*AMSHA_API_KEY=([^; ]+).*/\1/' | head -n1)
OWNER_ID=$(json ownerId)
WS_ID=$(json workspaceId)
if [ "$CODE" = "200" ] && [ -n "$OWNER_KEY" ] && [ -n "$OWNER_ID" ]; then
  ok "$CODE (owner=$OWNER_ID ws=$WS_ID)"
else
  fail "$CODE (key=${OWNER_KEY:-MISSING})"
  echo "Identity validation stopped: first-owner claim did not issue a usable credential."
  exit 1
fi

info "A3: bootstrap status after claim"
CODE=$(req GET "" "$R/onboarding/bootstrap/status")
ST=$(json status)
if [ "$CODE" = "200" ] && { [ "$ST" = "CLAIMED" ] || [ "$ST" = "CREDENTIAL_ISSUED" ]; }; then
  ok "$CODE ($ST)"; else fail "$CODE ($ST)"; fi

info "A4: re-claim (CREDENTIAL_ISSUED) → 200 recovery re-issue"
HTTP=$(raw -X POST "$R/onboarding/bootstrap/claim")
CODE=$(echo "$HTTP" | tail -n1)
RECOVERED_KEY=$(grep -i 'set-cookie' /tmp/headers | grep -i 'AMSHA_API_KEY=' \
                | sed -E 's/.*AMSHA_API_KEY=([^; ]+).*/\1/' | head -n1)
if [ "$CODE" = "200" ] && [ -n "$RECOVERED_KEY" ]; then
  OWNER_KEY="$RECOVERED_KEY"
  ok "$CODE (credential re-issued)"
else
  fail "$CODE (re-issued key missing)"
  exit 1
fi

info "A5: confirm claim"
CODE=$(req POST "$OWNER_KEY" "$R/onboarding/bootstrap/confirm" '{}'); exp "$CODE" "200"

info "A6: confirm again → 400 (state no longer CREDENTIAL_ISSUED)"
CODE=$(req POST "$OWNER_KEY" "$R/onboarding/bootstrap/confirm" '{}'); exp "$CODE" "400"

echo ""

# ── FLOW B: IDENTITY /me ──
echo "── FLOW B: Identity /me ──"

info "B1: /identity/me with owner key"
CODE=$(req GET "$OWNER_KEY" "$R/identity/me")
if [ "$CODE" = "200" ] && [ -n "$(json user)" ]; then ok "$CODE"; else fail "$CODE"; fi

info "B2: /identity/me with NO key → 401"
CODE=$(req GET "" "$R/identity/me"); exp "$CODE" "401"

info "B3: /identity/me with invalid key → 401"
CODE=$(req GET "$INVALID_KEY" "$R/identity/me"); exp "$CODE" "401"

echo ""

# ── FLOW C: WORKSPACE MEMBERS ──
echo "── FLOW C: Workspace Members ──"

info "C1: list members (owner)"
CODE=$(req GET "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/members")
if [ "$CODE" = "200" ]; then ok "$CODE"; else fail "$CODE"; fi

info "C2: list members NO key → 401"
CODE=$(req GET "" "$R/identity/workspaces/$WS_ID/members"); exp "$CODE" "401"

info "C3: list members unknown ws → 403"
CODE=$(req GET "$OWNER_KEY" "$R/identity/workspaces/ws_doesnotexist/members"); exp "$CODE" "403"

echo ""

# ── FLOW D: INVITATIONS ──
echo "── FLOW D: Invitations ──"

info "D1: create MEMBER invitation"
CODE=$(req POST "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/invitations" '{"role":"MEMBER"}')
INV_TOKEN=$(json rawToken)
if [ "$CODE" = "200" ] && [ -n "$INV_TOKEN" ]; then ok "$CODE"; else fail "$CODE"; fi

info "D2: create OWNER invitation (owner may)"
CODE=$(req POST "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/invitations" '{"role":"OWNER"}'); exp "$CODE" "200"

info "D3: invalid role → 403"
CODE=$(req POST "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/invitations" '{"role":"WIZARD"}'); exp "$CODE" "403"

info "D4: accept bogus token → 404"
CODE=$(req POST "$OWNER_KEY" "$R/identity/invitations/bogus-token/accept"); exp "$CODE" "404"

echo ""

# ── FLOW E: PERSONAL API CREDENTIALS ──
echo "── FLOW E: Personal API Credentials ──"

info "E1: create OWNER credential"
CODE=$(req POST "$OWNER_KEY" "$R/identity/api-credentials" '{"roleSet":"OWNER","expiresInSeconds":3600}')
CRED_ID=$(json credentialId)
if [ "$CODE" = "200" ] && [ -n "$CRED_ID" ]; then ok "$CODE"; else fail "$CODE"; fi

info "E2: grant INTERNAL role → 403"
CODE=$(req POST "$OWNER_KEY" "$R/identity/api-credentials" '{"roleSet":"INTERNAL"}'); exp "$CODE" "403"

info "E3: grant ADMIN (not held) → 403"
CODE=$(req POST "$OWNER_KEY" "$R/identity/api-credentials" '{"roleSet":"ADMIN"}'); exp "$CODE" "403"

info "E4: revoke credential → 200"
CODE=$(req DELETE "$OWNER_KEY" "$R/identity/api-credentials/$CRED_ID"); exp "$CODE" "200"

info "E5: revoke non-existent → 404"
CODE=$(req DELETE "$OWNER_KEY" "$R/identity/api-credentials/cred_doesnotexist"); exp "$CODE" "404"

echo ""

# ── FLOW F: MEMBER REMOVAL (last-owner guard) ──
echo "── FLOW F: Member Removal ──"

info "F1: remove non-member → 404"
CODE=$(req DELETE "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/members/usr_doesnotexist"); exp "$CODE" "404"

info "F2: remove only OWNER → 400 LAST_OWNER_REMOVAL_BLOCKED"
CODE=$(req DELETE "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/members/$OWNER_ID"); exp "$CODE" "400"

echo ""

# ── FLOW G: OWNERSHIP TRANSFER (negative) ──
echo "── FLOW G: Ownership Transfer ──"

info "G1: transfer to non-member → 400"
CODE=$(req POST "$OWNER_KEY" "$R/identity/workspaces/$WS_ID/owner/transfer" '{"targetUserId":"usr_doesnotexist"}'); exp "$CODE" "400"

echo ""

# ── FLOW H: PROVIDER DISCOVERY + OAUTH NEGATIVE ──
echo "── FLOW H: Auth Providers & OAuth Negative Paths ──"

info "H1: list providers → 200"
CODE=$(req GET "" "$R/auth/providers"); exp "$CODE" "200"

info "H2: login unknown provider → 404"
CODE=$(req GET "" "$R/auth/login/doesnotexist"); exp "$CODE" "404"

info "H3: login disabled oidc → 404 PROVIDER_DISABLED"
CODE=$(req GET "" "$R/auth/login/oidc");
[ "$CODE" = "404" ] || [ "$CODE" = "302" ] && ok "$CODE" || fail "$CODE"

info "H4: callback bad state → 400"
CODE=$(req GET "" "$R/auth/callback/oidc?code=abc&state=unknown-state"); exp "$CODE" "400"

info "H5: revoke-all NO auth → 401"
CODE=$(req POST "" "$R/auth/session/revoke-all"); exp "$CODE" "401"

info "H6: revoke-all with API key → 200"
CODE=$(req POST "$OWNER_KEY" "$R/auth/session/revoke-all"); exp "$CODE" "200"

echo ""

# ── FLOW I: WEB SESSION ROTATE (negative) ──
echo "── FLOW I: Web Session Rotate ──"

info "I1: rotate with no session cookie → 401"
CODE=$(req POST "" "$R/auth/session/rotate"); exp "$CODE" "401"

echo ""

# ── FLOW J: INVITATION ACCEPT (positive) — run LAST ──
# Accepting an invitation re-assigns the acceptor's workspace role to the
# invitation's role, so doing this with the owner key DEMOTES the owner to
# MEMBER. That is expected product behaviour for "accept your own invite";
# we run it last so it does not break the owner-gated flows above.
echo "── FLOW J: Invitation Accept (positive, run last) ──"

info "J1: accept real token → 200 (re-assigns acceptor role)"
CODE=$(req POST "$OWNER_KEY" "$R/identity/invitations/$INV_TOKEN/accept"); exp "$CODE" "200"

echo ""
echo "──────────────────────────────────────────────────────────"
echo " Identity/Access Results: $PASS passed, $FAIL failed"
echo "──────────────────────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1
