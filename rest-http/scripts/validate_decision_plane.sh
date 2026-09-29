#!/usr/bin/env bash
# validate_decision_plane.sh — Jev decision-plane (ADR-jev-decision-plane) contract validator
#
# The decision plane is deliberately Deferred: no DecisionEngine bean exists, so every
# evaluation fails closed and intent routing stays on the deterministic baseline. The only
# externally observable surface of that posture is the classifier control plane, and until
# now it was covered solely by validate_onnx.sh — which only runs behind --onnx and needs a
# model bundle. This validator asserts the same contract with zero tokens and no model.
#
# Covers:
#   JEV-1..2   authorization matrix (unauthenticated, non-internal)
#   JEV-3      payload shape: exact classifier set and exact snapshot fields (payload-free)
#   JEV-4      snapshot coherence (effective/accepting/draining/requested/implementation)
#   JEV-5      Deferred posture: no classifier is effective while no engine is registered
#   JEV-6      enable fails closed for every classifier
#   JEV-7      a rejected enable leaves state untouched (revision stable, still ineffective)
#   JEV-8      disable is accepted without an implementation (asymmetric, non-mutating)
#   JEV-9      unknown classifier is rejected as a client error
#   JEV-10     mutation requires an internal key
#   JEV-11     startupEnabled implies requestedEnabled
#   JEV-12     the DecisionPlaneReadiness diagnostic fired exactly once and is well-formed
#
# Env:
#   AMSHA_VALIDATION_LOG_FILE                    server log to assert JEV-12 against (skipped when unset)
#   AMSHA_DECISION_PLANE_EXPECT_INERT_WARNING    true|false|unset — require/forbid the diagnostic
#
# Overlap with validate_onnx.sh ONNX-5..7 is intentional: the same invariant must hold both
# with and without the local ONNX bundle loaded. Do not "de-duplicate" one away.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0; SKIP=0

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
ok_detail() { printf "✅ %s\n" "$1"; PASS=$((PASS+1)); }
skip()  { printf "⏭️  SKIP — %s\n" "$1"; SKIP=$((SKIP+1)); }
fail_detail() { printf "❌ %s\n" "$1"; FAIL=$((FAIL+1)); }

RESP_FILE="$(mktemp)"
SNAPSHOTS_BEFORE="$(mktemp)"
SNAPSHOTS_AFTER="$(mktemp)"
trap 'rm -f "$RESP_FILE" "$SNAPSHOTS_BEFORE" "$SNAPSHOTS_AFTER"' EXIT

response_shape() {
  python3 - "$RESP_FILE" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, UnicodeError, json.JSONDecodeError):
    print("non-json-response")
else:
    if isinstance(payload, dict):
        keys = sorted(str(key) for key in payload)[:12]
        print("json-keys=" + ",".join(keys))
    else:
        print("json-type=" + type(payload).__name__)
PY
}

fail() { printf "❌ HTTP %s — %s\n" "$CODE" "$(response_shape)"; FAIL=$((FAIL+1)); }
mute() { curl -s -m "$CURL_TIMEOUT" -o "$RESP_FILE" -w "%{http_code}" "$@"; }

CLASSIFIERS_URL="$B/api/v1/admin/local-ml/onnx/classifiers"

# Extracts one TSV row per classifier. Booleans become 1/0 so the invariants below can be
# evaluated arithmetically. Exits non-zero when the payload shape is not exactly the contract.
extract_snapshots() {
  python3 - "$RESP_FILE" "$1" <<'PY'
import json
import sys

EXPECTED_IDS = {"intent", "tool-risk", "answer-quality"}
FIELDS = (
    "classifier",
    "startupEnabled",
    "requestedEnabled",
    "implementationAvailable",
    "effectiveEnabled",
    "acceptingRequests",
    "draining",
    "inFlight",
    "revision",
)

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    print(f"unreadable status payload: {error}")
    raise SystemExit(1) from error

classifiers = payload.get("classifiers")
if not isinstance(classifiers, list):
    print("missing classifiers list")
    raise SystemExit(1)

actual_ids = {item.get("classifier") for item in classifiers if isinstance(item, dict)}
if actual_ids != EXPECTED_IDS:
    print("classifier set mismatch: got " + ",".join(sorted(str(i) for i in actual_ids)))
    raise SystemExit(1)

for item in classifiers:
    extra = sorted(set(item) - set(FIELDS))
    missing = sorted(set(FIELDS) - set(item))
    if extra or missing:
        print(f"{item.get('classifier')}: unexpected fields {extra}, missing fields {missing}")
        raise SystemExit(1)

with open(sys.argv[2], "w", encoding="utf-8") as out:
    for item in sorted(classifiers, key=lambda entry: str(entry.get("classifier"))):
        row = []
        for field in FIELDS:
            value = item[field]
            if isinstance(value, bool):
                row.append("1" if value else "0")
            else:
                row.append(str(value))
        out.write("\t".join(row) + "\n")
PY
}

# Coherence of one Snapshot, exactly as Slot.snapshot() derives it:
#   accepting = requestedEnabled && implementationAvailable
#   effectiveEnabled = acceptingRequests = accepting
#   draining = !accepting && inFlight > 0
coherence_violations() {
  awk -F'\t' '
    $5 != $6 { printf "%s(effective!=accepting) ", $1 }
    $6 != ($3 && $4) { printf "%s(accepting!=requested&&impl) ", $1 }
    $7 != (!$6 && $8 > 0) { printf "%s(draining-inconsistent) ", $1 }
  ' "$1"
}

inert_violations() {
  awk -F'\t' '$5 != 0 { printf "%s ", $1 }' "$1"
}

startup_violations() {
  awk -F'\t' '$2 == 1 && $3 != 1 { printf "%s ", $1 }' "$1"
}

revision_drift() {
  awk -F'\t' -v after="$1" '
    NR == FNR { before[$1] = $9; next }
    { if ($9 != before[$1]) printf "%s(%s->%s) ", $1, before[$1], $9 }
  ' "$SNAPSHOTS_BEFORE" "$2"
}

# Validates one enable/disable response: the body AND its HTTP status must agree.
# The endpoint contract is 409 for a rejected enable and 200 for an accepted or unchanged
# toggle, so a body alone is not evidence — a 200 carrying a rejection body, or a non-200
# carrying the expected body, means the HTTP contract is broken. Args: classifier, status.
# Prints a short token on success and the reason on failure.
toggle_shape() {
  python3 - "$RESP_FILE" "$1" "$2" <<'PY'
import json
import sys

expected_id = sys.argv[2]
raw_code = sys.argv[3]
try:
    code = int(raw_code)
except ValueError:
    print(f"non-numeric HTTP status {raw_code!r}")
    raise SystemExit(1)

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    print(f"unreadable toggle payload: {error}")
    raise SystemExit(1) from error

snapshot = payload.get("classifier")
if not isinstance(snapshot, dict) or snapshot.get("classifier") != expected_id:
    print(f"toggle response is not for {expected_id}")
    raise SystemExit(1)
if snapshot.get("effectiveEnabled"):
    print("mutation produced an effective classifier without a ready implementation")
    raise SystemExit(1)

accepted = payload.get("accepted")
changed = payload.get("changed")
reason = payload.get("reason")

if accepted is False:
    if changed is not False or reason != "MISSING_READY_IMPLEMENTATION":
        print(f"unexpected rejection reason={reason} changed={changed}")
        raise SystemExit(1)
    if code != 409:
        print(f"rejected toggle returned HTTP {code}, expected 409")
        raise SystemExit(1)
    print("rejected-fail-closed")
elif accepted is True:
    if changed is not False or reason != "UNCHANGED":
        print(f"unexpected acceptance reason={reason} changed={changed}")
        raise SystemExit(1)
    if code != 200:
        print(f"accepted toggle returned HTTP {code}, expected 200")
        raise SystemExit(1)
    print("accepted-unchanged")
else:
    print("response is missing the accepted flag")
    raise SystemExit(1)
PY
}

echo "═══════════════════════════════════════════"
echo " JEV DECISION-PLANE CONTRACT VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── JEV-1..2: Authorization matrix ──
info "JEV-1: Classifier status rejects unauthenticated callers"
CODE=$(mute -X GET "$CLASSIFIERS_URL")
if [ "$CODE" = "401" ]; then ok "$CODE"; else fail "$CODE"; fi
if [ "$CODE" = "000" ]; then
  fail_detail "server unreachable at $B — start it or set TEST_BASE"
  echo ""
  echo "───────────────────────────────────────────"
  echo " Jev Decision Plane Results: $PASS passed, $FAIL failed, $SKIP skipped"
  echo "───────────────────────────────────────────"
  exit 1
fi

info "JEV-2: Classifier status rejects non-internal callers"
CODE=$(mute -X GET -H "X-Amsha-API-Key: $CI_KEY" "$CLASSIFIERS_URL")
if [ "$CODE" = "403" ]; then ok "$CODE"; else fail "$CODE"; fi

# ── JEV-3: Payload shape ──
info "JEV-3: Snapshot shape is exactly the documented contract"
CODE=$(mute -X GET -H "X-Amsha-API-Key: $VALID_KEY" "$CLASSIFIERS_URL")
SHAPE_ERROR=""
if [ "$CODE" = "200" ]; then
  SHAPE_ERROR=$(extract_snapshots "$SNAPSHOTS_BEFORE"); SHAPE_RC=$?
  if [ "$SHAPE_RC" -eq 0 ]; then
    ok "$CODE"
  else
    fail_detail "shape: ${SHAPE_ERROR:-status payload rejected}"
  fi
else
  fail "$CODE"
fi

if [ ! -s "$SNAPSHOTS_BEFORE" ]; then
  fail_detail "no classifier snapshot could be parsed; skipping dependent checks"
  echo ""
  echo "───────────────────────────────────────────"
  echo " Jev Decision Plane Results: $PASS passed, $FAIL failed, $SKIP skipped"
  echo "───────────────────────────────────────────"
  exit 1
fi

# ── JEV-4: Snapshot coherence ──
info "JEV-4: Snapshot fields are mutually coherent"
VIOLATIONS=$(coherence_violations "$SNAPSHOTS_BEFORE")
if [ -z "$VIOLATIONS" ]; then ok_detail "coherent"; else fail_detail "incoherent: $VIOLATIONS"; fi

# ── JEV-5: Deferred posture ──
info "JEV-5: No classifier is effective while no engine is registered"
VIOLATIONS=$(inert_violations "$SNAPSHOTS_BEFORE")
if [ -z "$VIOLATIONS" ]; then
  ok_detail "deferred"
else
  fail_detail "effective without a ready implementation: $VIOLATIONS"
fi

# ── JEV-11: Startup request consistency ──
info "JEV-11: A startup-enabled classifier is also request-enabled"
VIOLATIONS=$(startup_violations "$SNAPSHOTS_BEFORE")
if [ -z "$VIOLATIONS" ]; then ok_detail "consistent"; else fail_detail "inconsistent: $VIOLATIONS"; fi

# ── JEV-6: Fail-closed enable ──
for classifier in intent tool-risk answer-quality; do
  info "JEV-6: $classifier enable fails closed without a ready implementation"
  CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" "$CLASSIFIERS_URL/$classifier/enable")
  TOKEN=$(toggle_shape "$classifier" "$CODE"); TOGGLE_RC=$?
  if [ "$TOGGLE_RC" -eq 0 ]; then
    ok "$CODE $TOKEN"
  else
    fail_detail "$classifier enable: $TOKEN (HTTP $CODE)"
  fi
done

# ── JEV-7: A rejected mutation is inert ──
info "JEV-7: Rejected enables left every revision and the posture untouched"
CODE=$(mute -X GET -H "X-Amsha-API-Key: $VALID_KEY" "$CLASSIFIERS_URL")
AFTER_ERROR=""
if [ "$CODE" = "200" ]; then
  AFTER_ERROR=$(extract_snapshots "$SNAPSHOTS_AFTER"); AFTER_RC=$?
fi
if [ "$CODE" = "200" ] && [ "$AFTER_RC" = "0" ]; then
  DRIFT=$(revision_drift "$SNAPSHOTS_BEFORE" "$SNAPSHOTS_AFTER")
  STILL_INERT=$(inert_violations "$SNAPSHOTS_AFTER")
  if [ -n "$DRIFT" ]; then
    fail_detail "revision drifted on a rejected enable: $DRIFT"
  elif [ -n "$STILL_INERT" ]; then
    fail_detail "an enable became effective: $STILL_INERT"
  else
    ok_detail "stable"
  fi
elif [ "$CODE" = "200" ]; then
  fail_detail "status payload rejected after the enable attempts: ${AFTER_ERROR:-unparseable}"
else
  fail "$CODE"
fi

# ── JEV-8: Disable is accepted and non-mutating ──
# Only exercised for slots that are already request-disabled: disabling a deliberately
# enabled slot would mutate runtime state for the remainder of the suite.
for classifier in intent tool-risk answer-quality; do
  REQUESTED=$(awk -F'\t' -v id="$classifier" '$1 == id { print $3 }' "$SNAPSHOTS_BEFORE")
  if [ "$REQUESTED" != "0" ]; then
    info "JEV-8: $classifier disable is accepted without an implementation"
    skip "$classifier is request-enabled; refusing to mutate runtime state"
    continue
  fi
  info "JEV-8: $classifier disable is accepted without an implementation"
  CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" "$CLASSIFIERS_URL/$classifier/disable")
  TOKEN=$(toggle_shape "$classifier" "$CODE"); TOGGLE_RC=$?
  if [ "$TOGGLE_RC" -eq 0 ]; then
    ok "$CODE $TOKEN"
  else
    fail_detail "$classifier disable: $TOKEN (HTTP $CODE)"
  fi
done

# ── JEV-9: Unknown classifier ──
info "JEV-9: Unknown classifier is rejected as a client error"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" "$CLASSIFIERS_URL/unknown/enable")
if [ "$CODE" = "400" ] && grep -q '"unknown_classifier"' "$RESP_FILE"; then
  ok "$CODE"
else
  fail "$CODE"
fi

# ── JEV-10: Mutation requires an internal key ──
# The probe must be incapable of changing live state, because TEST_BASE can point this
# validator at a live instance. It therefore targets an UNKNOWN id with the non-internal key:
# OnnxAdminResource.updateClassifier() checks isInternal() before it resolves the classifier
# id, so an unknown id returns 403 without ever reaching setEnabled(). A regressed
# authorization check would fall through to 400 unknown_classifier, which still fails this
# assertion — so the same code path is exercised, with no mutation possible and no race
# against a slot becoming enabled between the snapshot and the request.
info "JEV-10: Mutation rejects a non-internal key"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $CI_KEY" "$CLASSIFIERS_URL/unknown/enable")
if [ "$CODE" = "403" ]; then ok "$CODE [unknown id — no mutation possible]"; else fail "$CODE"; fi

# ── JEV-12: Startup readiness diagnostic ──
LOG_FILE="${AMSHA_VALIDATION_LOG_FILE:-}"
EXPECT="${AMSHA_DECISION_PLANE_EXPECT_INERT_WARNING:-}"
if [ -z "$LOG_FILE" ] || [ ! -r "$LOG_FILE" ]; then
  info "JEV-12: DecisionPlaneReadiness diagnostic is present once and well-formed"
  skip "set AMSHA_VALIDATION_LOG_FILE to assert the startup diagnostic"
else
  info "JEV-12: DecisionPlaneReadiness diagnostic is present once and well-formed"
  COUNT=$(grep -c 'DecisionPlaneReadiness' "$LOG_FILE")
  if [ "$COUNT" -gt 1 ]; then
    fail_detail "diagnostic logged $COUNT times; @Startup initialization is not idempotent"
  elif [ "$COUNT" -eq 0 ]; then
    if [ "$EXPECT" = "true" ]; then
      fail_detail "expected the inert-plane diagnostic; the switches are enabled but nothing was logged"
    else
      ok_detail "absent (switches disabled)"
    fi
  else
    LINE=$(grep -m 1 'DecisionPlaneReadiness' "$LOG_FILE")
    if [ "$EXPECT" = "false" ]; then
      fail_detail "diagnostic logged although AMSHA_DECISION_PLANE_EXPECT_INERT_WARNING=false"
    elif ! printf '%s' "$LINE" | grep -q ' WARN '; then
      fail_detail "diagnostic is not logged at WARN level"
    elif ! printf '%s' "$LINE" | grep -q 'MISSING_MODEL'; then
      fail_detail "diagnostic does not name the MISSING_MODEL fail-closed reason"
    elif ! printf '%s' "$LINE" | grep -q 'ADR-jev-decision-plane'; then
      fail_detail "diagnostic does not cite ADR-jev-decision-plane"
    else
      ok_detail "WARN x1"
    fi
  fi
fi

echo ""
echo "───────────────────────────────────────────"
echo " Jev Decision Plane Results: $PASS passed, $FAIL failed, $SKIP skipped"
echo "───────────────────────────────────────────"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
