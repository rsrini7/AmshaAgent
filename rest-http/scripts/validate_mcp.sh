#!/usr/bin/env bash
set +e
KEY="JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs="
SECRET="$KEY"
B=http://localhost:8080
AB=$B/api/v1
hdr(){ printf "\n### %s\n" "$1"; }
code(){ curl -s -m 20 -o /tmp/body -w "HTTP %{http_code}" "$@"; printf "  body="; head -c 320 /tmp/body; echo; }

hdr "FLOW A-1 status"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/mcp/status

hdr "FLOW A-2 tools"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/mcp/tools

hdr "FLOW B-1 init"
SID=$(curl -s -m 20 -D - -o /tmp/b1 -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"rest-client-test","version":"1.0"}}}' | grep -i "mcp-session-id" | tr -d '\r' | awk '{print $2}')
echo "SID=$SID"; head -c 200 /tmp/b1; echo

hdr "FLOW B-2 notifications/initialized"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: $SID" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'

hdr "FLOW B-3 tools/list"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: $SID" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

hdr "FLOW B-4 recall_memory_search"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: $SID" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"recall_memory_search","arguments":{"query":"Amsha Agent architecture overview"}}}'

hdr "FLOW B-5 github"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: $SID" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"github","arguments":{"operation":"list_repos","paramsJson":"{}"}}}'

hdr "FLOW C-1 sandbox_exec (allowlist)"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: $SID" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"sandbox_exec","arguments":{}}}'

hdr "FLOW C-2 tools/list no session"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":6,"method":"tools/list","params":{}}'

hdr "FLOW C-3 notifications/initialized bad session"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: 00000000-dead-beef-0000-000000000000" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'

hdr "FLOW C-4 unknown tool"
code -H "X-Amsha-API-Key: $KEY" -H "Mcp-Session-Id: $SID" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST $B/mcp -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"does_not_exist","arguments":{}}}'

hdr "FLOW D-1 self-connect"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"self","url":"http://localhost:8080/mcp"}'

hdr "FLOW D-2 tools (dynamic)"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/mcp/tools

hdr "FLOW D-3 list"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/mcp/connect

hdr "FLOW D-5 disconnect self"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $AB/mcp/connect/self

hdr "FLOW D-6 tools cleared"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/mcp/tools

hdr "FLOW E-1 missing url"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"no-url"}'

hdr "FLOW E-2 unreachable"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"dead","url":"http://localhost:9999/mcp"}'

hdr "FLOW E-3 disconnect never-connected"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $AB/mcp/connect/never-connected

hdr "FLOW E-4 duplicate (connect self while connected)"
# first connect self
curl -s -m 20 -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"self","url":"http://localhost:8080/mcp"}' >/dev/null
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"self","url":"http://localhost:8080/mcp"}'
# cleanup
curl -s -m 20 -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $AB/mcp/connect/self >/dev/null

hdr "FLOW E-5 no api key"
code -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"unauth","url":"http://localhost:8080/mcp"}'

hdr "FLOW F-1 secure connect"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d "{\"name\":\"secure\",\"url\":\"http://localhost:8080/mcp\",\"headers\":{\"Authorization\":\"Bearer $SECRET\"}}"

hdr "FLOW F-2 tools (secure dynamic)"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/mcp/tools

hdr "FLOW F-3 disconnect secure"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $AB/mcp/connect/secure

hdr "FLOW F-ERR-1 forbidden header"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"forbidden","url":"http://localhost:8080/mcp","headers":{"Content-Type":"application/json"}}'

hdr "FLOW F-ERR-2 injection header"
code -H "X-Amsha-API-Key: $KEY" -H "Content-Type: application/json" -X POST $AB/mcp/connect -d '{"name":"injection","url":"http://localhost:8080/mcp","headers":{"X-Evil":"$(curl evil)"}}'

hdr "GAP: register_mcp_server tool descriptor present?"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $AB/tools

hdr "CLEANUP"
for n in self secure; do curl -s -m 20 -o /dev/null -H "X-Amsha-API-Key: $KEY" -X DELETE $AB/mcp/connect/$n; done
echo "done"
