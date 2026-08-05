#!/usr/bin/env bash

# MCP protocol and management validation.
#
# This validator deliberately uses the local inbound /mcp endpoint as its
# reachable test server. It verifies the approval boundary and durable state
# without requiring an external provider or an LLM.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "${1:-}" = "--fast" ]; then
    exec "$SCRIPT_DIR/validate_mcp_fast.sh"
fi

# shellcheck source=common_env.sh
source "$SCRIPT_DIR/common_env.sh"

KEY="${AMSHA_MCP_VALIDATION_KEY:-$KEY}"
BASE="${B%/}"
API_BASE="$BASE/api/v1"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/amsha-mcp.XXXXXX")"
MOCK_PORT_FILE="$TMP_DIR/mock-port"
MOCK_PID=""
VALIDATION_CONNECTION_CREATED=false

cleanup() {
    if [ "$VALIDATION_CONNECTION_CREATED" = true ]; then
        curl -sS -m 5 -X DELETE "$API_BASE/mcp/connect/mcp-validation-self" \
            -H "X-Amsha-API-Key: $KEY" >/dev/null 2>&1 || true
    fi
    if [ -n "$MOCK_PID" ]; then
        kill "$MOCK_PID" 2>/dev/null || true
        wait "$MOCK_PID" 2>/dev/null || true
    fi
    rm -rf "$TMP_DIR"
}

trap cleanup EXIT

# The suite performs more than the default mutation budget. Keep the default
# non-blocking; set AMSHA_MCP_VALIDATION_RATE_LIMIT_WAIT_SECONDS=62 to retry
# after the server's one-minute window when running a complete flow.
RATE_LIMIT_WAIT_SECONDS="${AMSHA_MCP_VALIDATION_RATE_LIMIT_WAIT_SECONDS:-0}"
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
    LAST_BODY="$TMP_DIR/${label}.body"
    LAST_HEADERS="$TMP_DIR/${label}.headers"
    LAST_CODE="$(curl -sS -m 20 -D "$LAST_HEADERS" -o "$LAST_BODY" -w '%{http_code}' "$@" 2>/dev/null)" || LAST_CODE="000"
    if [ "$LAST_CODE" = "429" ]; then
        printf '⏳ %s rate limited; waiting %ss before retry\n' "$label" "$RATE_LIMIT_WAIT_SECONDS"
        sleep "$RATE_LIMIT_WAIT_SECONDS"
        LAST_CODE="$(curl -sS -m 20 -D "$LAST_HEADERS" -o "$LAST_BODY" -w '%{http_code}' "$@" 2>/dev/null)" || LAST_CODE="000"
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

json_value() {
    local expression="$1"
    python3 - "$LAST_BODY" "$expression" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8").strip()
data_lines = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
if data_lines:
    text = "\n".join(data_lines)
body = json.loads(text)
safe_globals = {
    "__builtins__": {},
    "all": all,
    "any": any,
    "int": int,
    "isinstance": isinstance,
    "next": next,
}
value = eval(sys.argv[2], safe_globals, {"body": body})
if value is None:
    raise SystemExit(1)
print(value)
PY
}

assert_json() {
    local label="$1"
    local expression="$2"
    if json_value "$expression" >/dev/null 2>&1; then
        pass "$label"
    else
        fail "$label"
    fi
}

pending_task() {
    local operation="$1"
    TASK_ID="$(json_value 'body.get("taskId")' 2>/dev/null || true)"
    local actual_operation
    actual_operation="$(json_value 'body.get("operation")' 2>/dev/null || true)"
    if [ -z "$TASK_ID" ] || [ "$actual_operation" != "$operation" ]; then
        fail "approval task is $operation"
        return 1
    fi
    pass "approval task is $operation"
    return 0
}

approve_task() {
    local task_id="$1"
    request "approve-$task_id" -X POST "$API_BASE/mcp/connect/approvals/$task_id/approve" \
        -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json'
    expect_code "approve $task_id" 200
}

reject_task() {
    local task_id="$1"
    request "reject-$task_id" -X POST "$API_BASE/mcp/connect/approvals/$task_id/reject" \
        -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json'
    expect_code "reject $task_id" 200
}

python3 - "$MOCK_PORT_FILE" <<'PY' &
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

port_file = Path(sys.argv[1])


class MockMcpHandler(BaseHTTPRequestHandler):
    server_version = "AmshaMcpValidation/1.0"

    def log_message(self, _format, *_args):
        return

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        try:
            request = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self.send_error(400)
            return

        method = request.get("method")
        if method == "initialize":
            self.send_json(200, {
                "jsonrpc": "2.0",
                "id": request.get("id"),
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "amsha-validation-mcp", "version": "1.0"},
                },
            }, session=True)
        elif method == "notifications/initialized":
            self.send_response(202)
            self.end_headers()
        elif method == "tools/list":
            self.send_json(200, {
                "jsonrpc": "2.0",
                "id": request.get("id"),
                "result": {
                    "tools": [{
                        "name": "validation_remote_tool",
                        "description": "Deterministic MCP validation tool",
                        "inputSchema": {"type": "object", "properties": {}},
                    }],
                },
            })
        elif method == "tools/call":
            self.send_json(200, {
                "jsonrpc": "2.0",
                "id": request.get("id"),
                "result": {"content": [{"type": "text", "text": "validation-ok"}]},
            })
        else:
            self.send_json(400, {
                "jsonrpc": "2.0",
                "id": request.get("id"),
                "error": {"code": -32601, "message": "method not found"},
            })

    def send_json(self, status, payload, session=False):
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        if session:
            self.send_header("Mcp-Session-Id", "validation-session")
        self.end_headers()
        self.wfile.write(encoded)


server = ThreadingHTTPServer(("127.0.0.1", 0), MockMcpHandler)
port_file.write_text(str(server.server_address[1]), encoding="utf-8")
server.serve_forever()
PY
MOCK_PID=$!
for _ in $(seq 1 20); do
    [ -s "$MOCK_PORT_FILE" ] && break
    sleep 0.1
done
MOCK_PORT="$(cat "$MOCK_PORT_FILE" 2>/dev/null || true)"
if [ -z "$MOCK_PORT" ]; then
    fail 'local MCP fixture started'
    exit 1
fi
MOCK_URL="http://localhost:$MOCK_PORT/mcp"
pass 'local MCP fixture started'

echo "=== MCP validation: inbound protocol ==="

request status -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/status"
expect_code 'MCP status' 200
assert_json 'MCP status shape' 'body.get("serverEnabled") is True and isinstance(body.get("dynamicToolCount"), int)'

request admin-tools -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/tools"
expect_code 'MCP admin tools' 200
assert_json 'MCP admin tools shape' 'isinstance(body.get("exposedInternalTools"), list) and isinstance(body.get("registeredDynamicTools"), list)'

request initialize -X POST "$BASE/mcp" -H "X-Amsha-API-Key: $KEY" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"amsha-mcp-validation","version":"1.0"}}}'
expect_code 'MCP initialize' 200
assert_json 'MCP initialize response' 'body.get("jsonrpc") == "2.0" and isinstance(body.get("result", {}).get("protocolVersion"), str)'
MCP_SESSION_ID="$(awk 'tolower($1) == "mcp-session-id:" { gsub("\r", "", $2); print $2; exit }' "$LAST_HEADERS")"
if [ -n "$MCP_SESSION_ID" ]; then pass 'MCP returned session ID'; else fail 'MCP returned session ID'; fi

request initialized -X POST "$BASE/mcp" -H "X-Amsha-API-Key: $KEY" \
    -H "Mcp-Session-Id: $MCP_SESSION_ID" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
expect_code 'MCP initialized notification' 202

request tools-list -X POST "$BASE/mcp" -H "X-Amsha-API-Key: $KEY" \
    -H "Mcp-Session-Id: $MCP_SESSION_ID" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
expect_code 'MCP tools/list' 200
assert_json 'MCP tools/list shape' 'isinstance(body.get("result", {}).get("tools"), list)'

request unknown-tool -X POST "$BASE/mcp" -H "X-Amsha-API-Key: $KEY" \
    -H "Mcp-Session-Id: $MCP_SESSION_ID" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"__amsha_validation_unknown_tool__","arguments":{}}}'
expect_code 'MCP unknown tool transport' 200
assert_json 'MCP unknown tool JSON-RPC error' 'body.get("jsonrpc") == "2.0" and isinstance(body.get("error"), dict)'

echo "=== MCP validation: approval-gated management ==="

request list-initial -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect"
expect_code 'MCP connection list' 200
assert_json 'MCP connection list shape' 'isinstance(body.get("connections"), list) and all({"name", "endpoint", "state", "transport", "enabledToolCount", "discoveredToolCount", "revision"}.issubset(item) for item in body.get("connections", []))'

request discover -X POST "$API_BASE/mcp/connect/discover" \
    -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
    -d '{"name":"mcp-validation-preview","url":"'"$MOCK_URL"'"}'
expect_code 'MCP discover requests approval' 202
if pending_task DISCOVER; then
    DISCOVER_TASK_ID="$TASK_ID"
    approve_task "$DISCOVER_TASK_ID"
    assert_json 'MCP discover returns bounded preview' 'body.get("operation") == "DISCOVER" and isinstance(body.get("result", {}).get("tools"), list) and "state" not in body.get("result", {})'
fi

request list-after-discover -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect"
expect_code 'MCP list after discover' 200
assert_json 'MCP discover leaves no durable row' 'all(item.get("name") != "mcp-validation-preview" for item in body.get("connections", []))'

request connect -X POST "$API_BASE/mcp/connect" \
    -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
    -d '{"name":"mcp-validation-self","url":"'"$MOCK_URL"'"}'
expect_code 'MCP connect requests approval' 202
if pending_task CONNECT; then
    CONNECT_TASK_ID="$TASK_ID"
    approve_task "$CONNECT_TASK_ID"
    assert_json 'MCP connect persists disabled policies' 'body.get("operation") == "CONNECT" and body.get("result", {}).get("state") == "CONNECTED" and body.get("result", {}).get("enabledToolCount") == 0 and body.get("result", {}).get("discoveredToolCount", 0) >= 1'
    if [ "$LAST_CODE" = "200" ]; then VALIDATION_CONNECTION_CREATED=true; fi
fi

request list-connected -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect"
expect_code 'MCP list connected state' 200
assert_json 'MCP durable connection metadata' 'any(item.get("name") == "mcp-validation-self" and item.get("state") == "CONNECTED" and item.get("enabledToolCount") == 0 for item in body.get("connections", []))'

request tools-policy -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect/mcp-validation-self/tools"
expect_code 'MCP discovered tool policies' 200
assert_json 'MCP policy list is bounded' 'isinstance(body, list) and len(body) >= 1 and all("toolName" in item and "enabled" in item for item in body)'
VALIDATION_TOOL="$(json_value 'body[0].get("toolName")' 2>/dev/null || true)"

if [ -n "$VALIDATION_TOOL" ]; then
    request enable -X POST "$API_BASE/mcp/connect/mcp-validation-self/tools/$VALIDATION_TOOL/enable" \
        -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json'
    expect_code 'MCP enable requests tool approval' 202
    if pending_task ENABLE_TOOL; then
        ENABLE_TASK_ID="$TASK_ID"
        approve_task "$ENABLE_TASK_ID"
        assert_json 'MCP tool enable registers one selected tool' 'body.get("operation") == "ENABLE_TOOL" and body.get("result", {}).get("enabledToolCount") == 1'
    fi
fi

request dynamic-tools-after-enable -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/tools"
expect_code 'MCP dynamic registry after enable' 200
assert_json 'MCP dynamic registry contains selected tool' 'any(item.get("mcpServerName") == "mcp-validation-self" and item.get("toolName") == "'"$VALIDATION_TOOL"'" for item in body.get("registeredDynamicTools", []))'

request list-before-reconfigure -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect"
REVISION="$(json_value 'next(item.get("revision") for item in body.get("connections", []) if item.get("name") == "mcp-validation-self")' 2>/dev/null || true)"
if [ -n "$REVISION" ]; then pass 'MCP revision is available'; else fail 'MCP revision is available'; fi

request reconfigure -X PATCH "$API_BASE/mcp/connect/mcp-validation-self" \
    -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
    -H "If-MCP-Revision: $REVISION" \
    -d '{"url":"'"$MOCK_URL"'","headers":{}}'
expect_code 'MCP reconfigure requests approval' 202
if pending_task RECONFIGURE; then
    RECONFIGURE_TASK_ID="$TASK_ID"
    approve_task "$RECONFIGURE_TASK_ID"
    assert_json 'MCP reconfigure returns connected snapshot' 'body.get("operation") == "RECONFIGURE" and body.get("result", {}).get("state") == "CONNECTED" and body.get("result", {}).get("revision", 0) > int("'"$REVISION"'")'
    NEW_REVISION="$(json_value 'body.get("result", {}).get("revision")' 2>/dev/null || true)"
    if [ -n "$NEW_REVISION" ]; then REVISION="$NEW_REVISION"; fi
fi

request stale-reconfigure -X PATCH "$API_BASE/mcp/connect/mcp-validation-self" \
    -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
    -H "If-MCP-Revision: $((REVISION - 1))" \
    -d '{"url":"'"$MOCK_URL"'","headers":{}}'
expect_code 'MCP stale revision is rejected' 409

request disable -X DELETE "$API_BASE/mcp/connect/mcp-validation-self/tools/$VALIDATION_TOOL" \
    -H "X-Amsha-API-Key: $KEY" -H "If-MCP-Revision: $REVISION"
expect_code 'MCP disable drains selected tool' 200
assert_json 'MCP disable clears enablement' 'body.get("enabledToolCount") == 0'
DISABLE_REVISION="$(json_value 'body.get("revision")' 2>/dev/null || true)"
if [ -n "$DISABLE_REVISION" ]; then REVISION="$DISABLE_REVISION"; fi

request list-for-pause -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json' "$API_BASE/mcp/connect"
REVISION="$(json_value 'next(item.get("revision") for item in body.get("connections", []) if item.get("name") == "mcp-validation-self")' 2>/dev/null || true)"
request pause -X POST "$API_BASE/mcp/connect/mcp-validation-self/pause" \
    -H "X-Amsha-API-Key: $KEY" -H "If-MCP-Revision: $REVISION"
expect_code 'MCP pause drains and persists state' 200
assert_json 'MCP pause state' 'body.get("state") == "PAUSED"'

request resume -X POST "$API_BASE/mcp/connect/mcp-validation-self/resume" \
    -H "X-Amsha-API-Key: $KEY" -H "If-MCP-Revision: $REVISION"
expect_code 'MCP resume requests approval' 202
if pending_task RECONNECT; then
    RESUME_TASK_ID="$TASK_ID"
    approve_task "$RESUME_TASK_ID"
    assert_json 'MCP resume reconnects' 'body.get("operation") == "RECONNECT" and body.get("result", {}).get("state") == "CONNECTED"'
fi

request import-preview -X POST "$API_BASE/mcp/import/preview" \
    -H "X-Amsha-API-Key: $KEY" \
    -F 'file=@-;filename=mcp-validation.json;type=application/json' \
    <<< '{"mcpServers":{"mcp-validation-import":{"url":"'"$MOCK_URL"'"}}}'
expect_code 'MCP import preview is local and bounded' 200
assert_json 'MCP import preview shape' 'isinstance(body.get("operationId"), str) and body.get("entries", [{}])[0].get("supported") is True'
IMPORT_OPERATION_ID="$(json_value 'body.get("operationId")' 2>/dev/null || true)"

if [ -n "$IMPORT_OPERATION_ID" ]; then
    request import-apply -X POST "$API_BASE/mcp/import/apply" \
        -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
        -d '{"operationId":"'"$IMPORT_OPERATION_ID"'","name":"mcp-validation-import","headers":{}}'
    expect_code 'MCP import apply requests approval' 202
    if pending_task CONNECT; then
        IMPORT_TASK_ID="$TASK_ID"
        reject_task "$IMPORT_TASK_ID"
    fi
fi

request setup-issue -X POST "$API_BASE/mcp/setup/issue" \
    -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
    -d '{"serverName":"mcp-validation-setup","channelType":"rest_api","channelId":"validation"}'
expect_code 'MCP setup token issue' 200
SETUP_TOKEN="$(json_value 'body.get("token")' 2>/dev/null || true)"
if [ -n "$SETUP_TOKEN" ]; then
    request setup-redeem -X POST "$API_BASE/mcp/setup/redeem" \
        -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
        -d '{"token":"'"$SETUP_TOKEN"'"}'
    expect_code 'MCP setup token redeem' 200
    request setup-redeem-again -X POST "$API_BASE/mcp/setup/redeem" \
        -H "X-Amsha-API-Key: $KEY" -H 'Content-Type: application/json' \
        -d '{"token":"'"$SETUP_TOKEN"'"}'
    expect_code 'MCP setup token is single use' 403
fi

request disconnect -X DELETE "$API_BASE/mcp/connect/mcp-validation-self" \
    -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json'
expect_code 'MCP disconnect' 200
assert_json 'MCP disconnect response' 'body.get("name") == "mcp-validation-self" and body.get("deregistered") is True'

request disconnect-missing -X DELETE "$API_BASE/mcp/connect/mcp-validation-missing" \
    -H "X-Amsha-API-Key: $KEY" -H 'Accept: application/json'
expect_code 'MCP disconnect missing server' 404

request unauthorized -X GET "$API_BASE/mcp/status" -H 'Accept: application/json' -H 'X-Amsha-API-Key: totally-wrong-key'
expect_code 'MCP rejects invalid API key' 401

printf 'MCP VALIDATION COMPLETE: %s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
