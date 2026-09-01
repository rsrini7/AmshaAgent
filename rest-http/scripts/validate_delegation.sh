#!/usr/bin/env bash

# validate_delegation.sh — Verify garuda delegation for every built-in persona.
#
# This is an LLM-backed REST check. Each role is asked to delegate a small,
# self-contained task and the session audit must contain DELEGATION_DISPATCHED.
# A 403/TOOL_BLOCKED result means that a persona lost its garuda capability.
#
# Usage:
#   bash validate_delegation.sh
#   DELEGATION_ROLES=coordinator,optimizer bash validate_delegation.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common_env.sh
source "$SCRIPT_DIR/common_env.sh"

B="${TEST_BASE:-$B}"
API_BASE="${B%/}/api/v1"
TIMEOUT_SECONDS="${DELEGATION_TIMEOUT_SECONDS:-${CURL_TIMEOUT:-300}}"
PACE_SECONDS="${DELEGATION_PACE_SECONDS:-1}"
MAX_ITERATIONS="${DELEGATION_MAX_ITERATIONS:-2}"
AUDIT_WAIT_SECONDS="${DELEGATION_AUDIT_WAIT_SECONDS:-${CURL_TIMEOUT:-300}}"
AUDIT_POLL_SECONDS="${DELEGATION_AUDIT_POLL_SECONDS:-2}"
AUDIT_POLL_COUNT=$(( (AUDIT_WAIT_SECONDS + AUDIT_POLL_SECONDS - 1) / AUDIT_POLL_SECONDS ))
AUDIT_REQUEST_TIMEOUT_SECONDS="${DELEGATION_AUDIT_REQUEST_TIMEOUT_SECONDS:-5}"
APPROVAL_REQUEST_TIMEOUT_SECONDS="${DELEGATION_APPROVAL_REQUEST_TIMEOUT_SECONDS:-5}"
RATE_LIMIT_WAIT_SECONDS="${AMSHA_RATE_LIMIT_WAIT_SECONDS:-62}"
RESP_FILE="$(mktemp "${TMPDIR:-/tmp}/amsha-delegation-response.XXXXXX")"
HEADERS_FILE="$(mktemp "${TMPDIR:-/tmp}/amsha-delegation-headers.XXXXXX")"
RUN_RESP_FILE=""
RUN_HEADERS_FILE=""
RUN_CODE_FILE=""
RUN_PID=""

PASS=0
FAIL=0
RUN_COUNT=0
SESSION_ID=""

pass() {
    PASS=$((PASS + 1))
    printf '✅ %s\n' "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    printf '❌ %s\n' "$1"
}

# Keep response bodies on disk. Validators must not echo prompts, completions,
# authorization headers, or other model/application payloads into the log.
request() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    local retry_rate_limit="${4:-true}"
    local request_timeout="${5:-$TIMEOUT_SECONDS}"
    local code
    local -a args=(
        -sS -m "$request_timeout"
        -D "$HEADERS_FILE"
        -o "$RESP_FILE"
        -w '%{http_code}'
        -X "$method"
        -H "X-Amsha-API-Key: $VALID_KEY"
    )

    if [ -n "$data" ]; then
        args+=( -H 'Content-Type: application/json' -d "$data" )
    fi

    code="$(curl "${args[@]}" "$url" 2>/dev/null)" || code="000"
    if [ "$code" = "429" ] && [ "$retry_rate_limit" = true ]; then
        sleep "$RATE_LIMIT_WAIT_SECONDS"
        code="$(curl "${args[@]}" "$url" 2>/dev/null)" || code="000"
    fi
    printf '%s' "$code"
}

# run-async keeps its HTTP response open until the complete agent chain
# finishes. A specialist can legitimately pause for approval or take longer
# than the probe needs. Run the request in the background and observe the
# dispatch audit independently, otherwise a slow specialist looks like a
# missing garuda capability (especially for Sentinel's security prompts).
start_async_run() {
    local url="$1"
    local data="$2"
    RUN_RESP_FILE="$(mktemp "${TMPDIR:-/tmp}/amsha-delegation-run-response.XXXXXX")"
    RUN_HEADERS_FILE="$(mktemp "${TMPDIR:-/tmp}/amsha-delegation-run-headers.XXXXXX")"
    RUN_CODE_FILE="$(mktemp "${TMPDIR:-/tmp}/amsha-delegation-run-code.XXXXXX")"

    curl -sS -m "$TIMEOUT_SECONDS" \
        -D "$RUN_HEADERS_FILE" \
        -o "$RUN_RESP_FILE" \
        -w '%{http_code}' \
        -X POST \
        -H "X-Amsha-API-Key: $VALID_KEY" \
        -H 'Content-Type: application/json' \
        -d "$data" \
        "$url" >"$RUN_CODE_FILE" 2>/dev/null &
    RUN_PID="$!"
}

completed_run_code() {
    if [ -z "$RUN_PID" ]; then
        printf '000'
        return
    fi
    if [ ! -s "$RUN_CODE_FILE" ]; then
        printf 'pending'
        return
    fi
    tr -d '\n' <"$RUN_CODE_FILE"
}

stop_async_run() {
    if [ -n "$RUN_PID" ]; then
        kill "$RUN_PID" 2>/dev/null || true
        wait "$RUN_PID" 2>/dev/null || true
    fi
    rm -f "$RUN_RESP_FILE" "$RUN_HEADERS_FILE" "$RUN_CODE_FILE"
    RUN_RESP_FILE=""
    RUN_HEADERS_FILE=""
    RUN_CODE_FILE=""
    RUN_PID=""
}

json_field() {
    local field="$1"
    python3 - "$RESP_FILE" "$field" <<'PY'
import json
import sys

try:
    value = json.load(open(sys.argv[1], encoding="utf-8"))
    for part in sys.argv[2].split("."):
        value = value.get(part) if isinstance(value, dict) else None
    if value is not None:
        print(value)
except (OSError, ValueError, TypeError):
    pass
PY
}

audit_state() {
    local session_id="$1"
    local code
    code="$(request GET "$API_BASE/audit?sessionId=$session_id&size=200" "" false "$AUDIT_REQUEST_TIMEOUT_SECONDS")"
    if [ "$code" != "200" ]; then
        printf 'unavailable'
    elif grep -q 'TOOL_BLOCKED' "$RESP_FILE"; then
        printf 'blocked'
    elif grep -q 'DELEGATION_DISPATCHED' "$RESP_FILE"; then
        printf 'dispatched'
    else
        printf 'pending'
    fi
}

cleanup() {
    stop_async_run
    if [ -n "$SESSION_ID" ]; then
        request DELETE "$API_BASE/sessions/$SESSION_ID" >/dev/null 2>&1 || true
    fi
}
trap 'cleanup; rm -f "$RESP_FILE" "$HEADERS_FILE"' EXIT

echo '═══════════════════════════════════════════'
echo ' ALL-PERSONA DELEGATION VALIDATOR'
echo ' garuda capability + delegation audit coverage'
echo '═══════════════════════════════════════════'

# role|target|task. Tasks deliberately cross persona boundaries so this
# exercises real sub-agent dispatch rather than allowing a direct answer.
ROLE_MATRIX=(
    'coordinator|researcher|Research the current difference between SQLite WAL and PostgreSQL durability.'
    'researcher|coordinator|Schedule a daily task for a health check; do not schedule it yourself.'
    'coder|researcher|Use the unavailable github capability to look up the latest API for a Java library.'
    'debugger|coder|Write the Java fix for this bug after delegation: a null value reaches UserService line 42.'
    'architect|coder|Write production Java code for a UserDto record; you must not code, so delegate the implementation.'
    'synthesizer|architect|Produce an ADR for event-driven versus direct calls; have the architect handle the design.'
    'optimizer|debugger|Fix the root cause bug in UserService; you must not debug code, so delegate to the debugger.'
    'sentinel|debugger|Debug a JWT validation failure caused by an expired signing key.'
    'healer|optimizer|Optimize the token usage of a recursive algorithm; delegate the optimization analysis.'
    'protector|researcher|Research the applicable policy requirements for retaining user data.'
    'purifier|healer|Analyze a failed recovery job and ask the healer for a recovery plan.'
    'nurturer|researcher|Research factual guidance for responding empathetically to a frustrated user.'
)

requested_roles="${DELEGATION_ROLES:-}"
if [ -n "$requested_roles" ]; then
    IFS=',' read -r -a requested <<< "$requested_roles"
else
    requested=()
fi

role_requested() {
    local role="$1"
    local selected
    if [ "${#requested[@]}" -eq 0 ]; then
        return 0
    fi
    for selected in "${requested[@]}"; do
        [ "${selected// /}" = "$role" ] && return 0
    done
    return 1
}

role_requires_approval() {
    case "$1" in
        architect|coordinator|researcher|coder|debugger|synthesizer|optimizer|sentinel|healer|protector|purifier|nurturer)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

for entry in "${ROLE_MATRIX[@]}"; do
    role="${entry%%|*}"
    remainder="${entry#*|}"
    target="${remainder%%|*}"
    task="${remainder#*|}"
    role_requested "$role" || continue
    RUN_COUNT=$((RUN_COUNT + 1))

    printf '%-4s %-16s → %-12s ' '' "$role" "$target"
    code="$(request POST "$API_BASE/sessions" '{"autonomyLevel":"SUPERVISED"}')"
    if [ "$code" != "201" ] && [ "$code" != "200" ]; then
        printf '❌ session HTTP %s\n' "$code"
        FAIL=$((FAIL + 1))
        continue
    fi
    SESSION_ID="$(json_field sessionId)"
    if [ -z "$SESSION_ID" ]; then
        printf '❌ session response missing sessionId\n'
        FAIL=$((FAIL + 1))
        continue
    fi
    goal="Call garuda now and delegate to $target. $task Do not answer directly before the specialist returns."
    # Allow one follow-up turn for heavier personas to dispatch after their
    # first reasoning/tool step, while the audit event remains the assertion.
    payload="{\"goal\":\"$goal\",\"agentRole\":\"$role\",\"maxIterations\":$MAX_ITERATIONS}"
    start_async_run "$API_BASE/sessions/$SESSION_ID/run-async" "$payload"

    dispatched=false
    blocked=false
    approval_sent=false
    for _ in $(seq 1 "$AUDIT_POLL_COUNT"); do
        audit_result="$(audit_state "$SESSION_ID")"
        if [ "$audit_result" = dispatched ]; then
            dispatched=true
            break
        fi

        if [ "$audit_result" = blocked ]; then
            blocked=true
            break
        fi

        run_code="$(completed_run_code)"
        # Approval-required roles pause before garuda executes. The run-async
        # response cannot report that pending state until the callback returns,
        # so retry the approval endpoint independently during the audit poll.
        if role_requires_approval "$role" && [ "$approval_sent" = false ]; then
            approval_code="$(request POST "$API_BASE/sessions/$SESSION_ID/approve?agentRole=$role" "" true "$APPROVAL_REQUEST_TIMEOUT_SECONDS")"
            [ "$approval_code" = "200" ] || [ "$approval_code" = "202" ] || true
            if [ "$approval_code" = "200" ] || [ "$approval_code" = "202" ]; then
                approval_sent=true
            fi
        fi
        sleep "$AUDIT_POLL_SECONDS"
    done

    run_code="$(completed_run_code)"

    if [ "$blocked" = true ]; then
        printf '❌ HTTP %s (TOOL_BLOCKED)\n' "$run_code"
        FAIL=$((FAIL + 1))
    elif [ "$run_code" = "403" ] && [ "$dispatched" = false ]; then
        printf '❌ HTTP 403 (garuda blocked)\n'
        FAIL=$((FAIL + 1))
    elif [ "$dispatched" = true ]; then
        printf '✅ HTTP %s (DELEGATION_DISPATCHED)\n' "$run_code"
        PASS=$((PASS + 1))
    elif [ "$run_code" = "000" ]; then
        printf '❌ HTTP 000 (request failed)\n'
        FAIL=$((FAIL + 1))
    else
        printf '❌ HTTP %s (missing DELEGATION_DISPATCHED)\n' "$run_code"
        FAIL=$((FAIL + 1))
    fi
    cleanup
    SESSION_ID=""
    sleep "$PACE_SECONDS"
done

if [ "$RUN_COUNT" -eq 0 ]; then
    fail 'no requested roles matched the built-in persona matrix'
fi

echo '───────────────────────────────────────────'
printf ' Delegation results: %s passed, %s failed\n' "$PASS" "$FAIL"
echo '───────────────────────────────────────────'
[ "$FAIL" -eq 0 ]
