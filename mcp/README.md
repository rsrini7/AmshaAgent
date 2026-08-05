# amsha-mcp

The MCP module provides both sides of the Model Context Protocol:

- an inbound MCP server that exposes allowlisted Amsha tools; and
- an outbound MCP client that connects to remote MCP servers and exposes explicitly enabled remote tools to the agent.

Version 1 remote management is user-private and HTTP/SSE-only. STDIO remains available only through the separately governed agent registration path; the browser management UI and REST management API never launch arbitrary local processes.

The lifecycle is illustrated in [the MCP execution diagram](../docs/assets/amsha_mcp_execution.svg) and described for operators in [the MCP management guide](../docs/MCP_MANAGEMENT.md).

## Remote connection lifecycle

All lifecycle surfaces delegate to the same management service and persistence model:

1. **Discover** validates the endpoint, requests `MCP_CONNECT` approval, performs a handshake and `tools/list` after approval, and returns a bounded throwaway preview. It does not persist a connection, vault secret, or registered tool. Connect performs a new handshake.
2. **Connect** requests `MCP_CONNECT` approval before network I/O, then persists the connection and all discovered tools disabled. A durable row can be `PENDING_APPROVAL`, `CONNECTING`, `CONNECTED`, `PAUSED`, `DEGRADED`, `FAILED`, or `DISCONNECTED`.
3. **Enable a tool** requests `MCP_TOOL_ENABLE` approval, verifies the server is reachable, and registers only that tool for the owning user. New or schema-changed tools stay disabled.
4. **Pause/resume/reconnect/disconnect** preserve user ownership and apply bounded drain before deregistration. Startup reconnect restores only explicitly enabled tools for durable connections.
5. **Reconfigure** atomically changes an endpoint or credentials after `MCP_CONNECT` approval. Send `If-MCP-Revision` with the current revision; stale mutations return `409`. Existing enabled policy is retained only when the tool name and schema hash are unchanged. `rotate-headers` is the compatibility alias for credential rotation.

No tool is executable merely because it was returned by `tools/list`; activation is explicit.

## REST management API

REST management requests use the normal Amsha API authentication. Mutating operations return `202 Accepted` with a pending task when approval is required. Complete the task with the MCP approval endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/mcp/connect` | List durable connections, including state, transport, revision, enabled count, and discovered count. |
| `POST` | `/api/v1/mcp/connect/discover` | Approval-gated, non-persistent handshake preview. |
| `POST` | `/api/v1/mcp/connect` | Approval-gated durable connect or create/update. |
| `GET` | `/api/v1/mcp/connect/approvals` | Restore pending MCP approvals after a browser refresh. |
| `POST` | `/api/v1/mcp/connect/approvals/{taskId}/approve` | Approve and execute an MCP lifecycle task. |
| `POST` | `/api/v1/mcp/connect/approvals/{taskId}/reject` | Reject a pending MCP lifecycle task. |
| `PATCH` | `/api/v1/mcp/connect/{name}` | Approval-gated endpoint/credential reconfiguration. |
| `POST` | `/api/v1/mcp/connect/{name}/rotate-headers` | Compatibility alias for credential rotation. |
| `POST` | `/api/v1/mcp/connect/{name}/activate` | Approval-gated activation/reconnect. |
| `POST` | `/api/v1/mcp/connect/{name}/pause` | Pause and drain active tools. |
| `POST` | `/api/v1/mcp/connect/{name}/resume` | Request reconnect/resume. |
| `POST` | `/api/v1/mcp/connect/{name}/reconnect` | Request reconnect. |
| `GET` | `/api/v1/mcp/connect/{name}/tools` | List discovered tool policy and schema metadata. |
| `POST` | `/api/v1/mcp/connect/{name}/tools/{toolName}/enable` | Approval-gated explicit tool activation. |
| `DELETE` | `/api/v1/mcp/connect/{name}/tools/{toolName}` | Disable and drain one tool. |
| `DELETE` | `/api/v1/mcp/connect/{name}` | Disconnect, deregister, and clean up vault references. |

The read-only legacy inspection endpoints remain available at `GET /api/v1/mcp/status` and `GET /api/v1/mcp/tools`.

### Secure headers

Header names are validated as HTTP tokens, duplicates are rejected case-insensitively, and transport-owned or hop-by-hop headers are rejected. Header values are written only to `SecretVault` under `USER:<userId>`; durable records retain only header names and opaque vault references. Values are resolved just in time, never returned by the API, and zeroed after use. Do not put MCP credentials in chat, Telegram commands, URLs, `.mcp.json` responses, logs, or audit payloads.

### User identity and local development

Connections are scoped by user, not by channel. In authenticated mode the REST security principal is authoritative. When `amsha.channel.web.require-auth=false`, browser MCP REST calls may carry the stable per-tab `X-AMSHA-PLATFORM-ID` continuity header; it is accepted only as a local fallback and is not authentication. The authenticated principal always wins.

The native CLI uses the claimed local bootstrap owner when one exists, falling back to `cli-user` before onboarding. This makes a local UI-managed connection visible to native CLI chat after restarting the rebuilt binary. Different authenticated users, browser tabs in unauthenticated mode, persistence backends, or data directories intentionally have separate scopes.

## Chat commands

Type `/mcp` to print status and this help. MCP approvals use the MCP-specific commands below, not the global agent `/approve` command:

```text
/mcp                         status plus help
/mcp help                    print help
/mcp list                    list durable connections
/mcp setup <name>            issue a short-lived add-server UI link
/mcp edit <name>             issue a short-lived edit-server UI link
/mcp discover <name> <url>   preview a remote server
/mcp connect <name> <url>    connect a new HTTP server
/mcp activate <name>         activate/reconnect a server
/mcp pause <name>            pause a server
/mcp resume <name>           resume a server
/mcp reconnect <name>        reconnect a server
/mcp tools <name>            list discovered tools
/mcp enable <name> <tool>    request explicit tool activation
/mcp disable <name> <tool>   disable a tool
/mcp approve <task-id>       approve an MCP lifecycle task
/mcp deny <task-id>          deny an MCP lifecycle task
/mcp disconnect <name>       disconnect a server
```

Setup and edit links are single-use, user-bound, and valid for ten minutes. Configure `AMSHA_MCP_SETUP_UI_BASE_URL` for absolute links in Telegram or other remote channels. The UI redeems the token immediately and replaces browser history; a redeemed or expired link returns `403`.

## Web UI

Open `#/mcp`. Each server is rendered as a collapsible card so multiple servers remain manageable. The card shows endpoint, transport, state, revision, and `enabled/discovered` counts. Use **Test and discover** for a throwaway preview, then **Connect** to create the durable server after approval. Expand the card to enable/disable individual tools, activate, pause, resume, reconnect, edit, or disconnect.

Pending approvals are restored from the server after refresh and appear in the MCP approval panel. A `409` means the card has a stale revision; reload the connection and retry the mutation. A failed fetch can be retried without losing the add-server form.

## `.mcp.json` import

Import is opt-in and upload-only. The preview accepts at most 256 KiB, parses local JSON only, performs no network I/O, launches no STDIO process, and returns a ten-minute single-use preview ID. Apply selects one supported entry and routes it through the normal approval-gated connect or reconfigure flow. The store and vault remain the source of truth; a failed import cannot replace a working connection.

## Setup and configuration

- `amsha.mcp.server-enabled` / `AMSHA_MCP_SERVER_ENABLED` (default `true`) enables the MCP management API.
- `amsha.mcp.setup-token-secret` / `AMSHA_MCP_SETUP_TOKEN_SECRET` is the HMAC-SHA-256 setup-link secret. Set a stable random value in production; an empty value uses a process-local secret and invalidates links on restart.
- `amsha.mcp.setup-ui-base-url` / `AMSHA_MCP_SETUP_UI_BASE_URL` is the public UI base URL for absolute setup/edit links. Empty keeps the relative `/#/mcp/setup` path.
- `amsha.mcp.health-check-interval-ms` / `AMSHA_MCP_HEALTH_CHECK_INTERVAL_MS` controls active HTTP/SSE health checks (default `15000`).
- `amsha.mcp.podman.health-check-interval-ms` / `AMSHA_MCP_PODMAN_HEALTH_CHECK_INTERVAL_MS` controls Podman MCP health checks (default `15000`).
- `amsha.channel.web.require-auth` / `AMSHA_WEB_REQUIRE_AUTH` controls WebSocket authentication. The browser platform-ID fallback is permitted only when this is `false`.

## Inbound MCP server

Amsha also exposes an inbound MCP server at `/mcp` and `/mcp/sse`. The exposed internal tools are controlled by `amsha.mcp.exposed-tools` and `amsha.mcp.tool-allowlist`. Use [the REST Client flows](../rest-http/12_mcp.http) for initialize, `tools/list`, `tools/call`, session-header, error, and self-connect tests.

## Key implementation types

- `McpManagementService` — shared approval-aware lifecycle orchestration.
- `McpClientService` — MCP handshake, transport, and selective registration.
- `McpConnectionLifecycleService` — validation, durable persistence, vault staging, reconnect, drain, and cleanup.
- `McpConnectionCredentialPort` / `DefaultMcpConnectionCredentialPort` — ephemeral vault header resolution.
- `McpConnectionStorePort`, `McpConnectionDto`, and `McpConnectionToolPolicyDto` — durable user-private state and per-tool policy.
- `McpApprovalService` — bounded, user-bound MCP lifecycle approval queue.
- `McpReflex` — channel-neutral `/mcp ...` command surface.

Persistence has SQLite, PostgreSQL, and filesystem adapters. See [Architecture Invariants](../docs/Architecture_Invariants.md) for persistence, transaction, native-image, and secret-handling rules.
