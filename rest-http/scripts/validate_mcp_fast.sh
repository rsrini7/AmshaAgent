#!/usr/bin/env bash

# Deterministic MCP protocol smoke checks. The full validate_mcp.sh flow also
# exercises tool calls and outbound self-connect; this suite deliberately avoids
# LLM, external-network, and mutating MCP work so it is safe for --fast runs.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common_env.sh
source "$SCRIPT_DIR/common_env.sh"

BASE="${B%/}"
API_BASE="$BASE/api/v1"
TIMEOUT_SECONDS="${AMSHA_MCP_FAST_TIMEOUT_SECONDS:-10}"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/amsha-mcp-fast.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

PASSED=0
FAILED=0
LAST_CODE="000"
LAST_BODY=""
LAST_HEADERS=""

pass() {
    PASSED=$((PASSED + 1))
    printf '✅ %s\n' "$1"
}

fail() {
    FAILED=$((FAILED + 1))
    printf '❌ %s\n' "$1"
}

request() {
    local label="$1"
    shift
    LAST_BODY="$TMP_DIR/$label.body"
    LAST_HEADERS="$TMP_DIR/$label.headers"

    if ! LAST_CODE="$(curl -sS -m "$TIMEOUT_SECONDS" -D "$LAST_HEADERS" -o "$LAST_BODY" -w '%{http_code}' "$@")"; then
        LAST_CODE="000"
    fi
}

expect_code() {
    local label="$1"
    local expected="$2"
    if [ "$LAST_CODE" = "$expected" ]; then
        pass "$label (HTTP $LAST_CODE)"
    else
        fail "$label expected HTTP $expected, got $LAST_CODE"
    fi
}

expect_json() {
    local label="$1"
    local kind="$2"
    if python3 - "$LAST_BODY" "$kind" >/dev/null 2>&1 <<'PY'
import json
import sys
from pathlib import Path


def payload(path):
    text = Path(path).read_text(encoding="utf-8").strip()
    data_lines = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
    if data_lines:
        text = "\n".join(data_lines)
    return json.loads(text)


body = payload(sys.argv[1])
kind = sys.argv[2]

if kind == "status":
    assert body.get("serverEnabled") is True
    assert isinstance(body.get("dynamicToolCount"), int)
elif kind == "admin-tools":
    assert isinstance(body.get("exposedInternalTools"), list)
    assert isinstance(body.get("registeredDynamicTools"), list)
    names = {item.get("name") for item in body["exposedInternalTools"] if isinstance(item, dict)}
    assert {"github", "recall_memory_search"}.issubset(names)
elif kind == "initialize":
    assert body.get("jsonrpc") == "2.0"
    assert isinstance(body.get("result", {}).get("protocolVersion"), str)
elif kind == "tools-list":
    tools = body.get("result", {}).get("tools")
    assert isinstance(tools, list)
    names = {item.get("name") for item in tools if isinstance(item, dict)}
    assert {"github", "recall_memory_search"}.issubset(names)
elif kind == "jsonrpc-error":
    assert body.get("jsonrpc") == "2.0"
    assert isinstance(body.get("error"), dict)
elif kind == "connect-list":
    assert isinstance(body, dict)
    connections = body.get("connections")
    assert isinstance(connections, list)
    for connection in connections:
        assert {"name", "endpoint", "state", "transport", "enabledToolCount", "discoveredToolCount", "revision"} <= set(connection)
else:
    raise AssertionError(f"unknown check kind: {kind}")
PY
    then
        pass "$label"
    else
        fail "$label returned an unexpected JSON shape"
    fi
}

printf '═══════════════════════════════════════════\n'
printf ' AMsha MCP FAST VALIDATION\n'
printf '═══════════════════════════════════════════\n'

request admin_status -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/status"
expect_code 'MCP admin status' 200
expect_json 'MCP admin status shape' status

request admin_tools -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/tools"
expect_code 'MCP admin tools' 200
expect_json 'MCP admin tools shape' admin-tools

request admin_status_unauthorized -H "X-Amsha-API-Key: $INVALID_KEY" -H 'Accept: application/json' "$API_BASE/mcp/status"
expect_code 'MCP admin rejects invalid API key' 401

request connect_list -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect"
expect_code 'MCP connection list' 200
expect_json 'MCP connection list shape' connect-list

request initialize \
    -H "X-Amsha-API-Key: $KEY" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -X POST "$BASE/mcp" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"amsha-mcp-fast","version":"1.0"}}}'
expect_code 'MCP initialize' 200
expect_json 'MCP initialize response' initialize

MCP_SESSION_ID="$(awk 'tolower($1) == "mcp-session-id:" { gsub("\r", "", $2); print $2; exit }' "$LAST_HEADERS")"
if [ -n "$MCP_SESSION_ID" ]; then
    pass 'MCP initialize returned a session ID'
else
    fail 'MCP initialize did not return a session ID'
fi

request initialized \
    -H "X-Amsha-API-Key: $KEY" \
    -H "Mcp-Session-Id: $MCP_SESSION_ID" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -X POST "$BASE/mcp" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
expect_code 'MCP initialized notification' 202

request tools_list \
    -H "X-Amsha-API-Key: $KEY" \
    -H "Mcp-Session-Id: $MCP_SESSION_ID" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -X POST "$BASE/mcp" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
expect_code 'MCP tools/list' 200
expect_json 'MCP tools/list response' tools-list

request invalid_tool \
    -H "X-Amsha-API-Key: $KEY" \
    -H "Mcp-Session-Id: $MCP_SESSION_ID" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -X POST "$BASE/mcp" \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"__amsha_fast_unknown_tool__","arguments":{}}}'
expect_code 'MCP rejects unknown tool' 200
expect_json 'MCP unknown-tool JSON-RPC error' jsonrpc-error

printf 'MCP FAST VALIDATION COMPLETE: %d passed, %d failed\n' "$PASSED" "$FAILED"
exit "$FAILED"
