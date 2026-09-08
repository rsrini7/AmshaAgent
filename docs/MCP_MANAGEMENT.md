# MCP Management

Amsha provides one user-private MCP management service behind the web UI, chat commands, REST, and channel adapters. The same connection, credential, policy, approval, and cleanup rules apply regardless of where the operation starts.

![MCP management and selective tool execution](./assets/amsha_mcp_execution.svg)

## What is managed

The `#/mcp` panel supports multiple remote servers as collapsible cards. Each card shows the server name, endpoint, transport, state, revision, and enabled/discovered tool counts. It supports:

- throwaway **Test and discover** previews;
- approval-gated durable connect and reconnect;
- explicit per-tool enable/disable;
- pause, resume, activate, reconnect, and disconnect;
- endpoint and credential editing with optimistic revision checks; and
- upload-only `.mcp.json` preview and apply.

Tools returned by `tools/list` are discovered metadata only. They are disabled until explicitly enabled. For example, `CONNECTED (1/56 tools)` means the server is connected, 56 tools were discovered, and one is currently registered for the agent.

## Lifecycle behavior

Discover validates the endpoint, requests `MCP_CONNECT` approval, and performs the remote handshake only after verifying a single-use `approvalToken` receipt issued by `ApprovalReceiptIssuer` (binding the requesting actor, session ID, purpose `MCP_CONNECT`, and destination host). It returns bounded metadata and leaves no durable row, policy, vault secret, or registered tool. Connect performs a fresh governed handshake consuming a checkpoint-backed `approvalToken` receipt, persists the connection, and creates disabled policies.

Enabling a tool requests `MCP_TOOL_ENABLE` approval, verifies connectivity, and registers only the selected tool. A removed or schema-changed upstream tool is not silently re-enabled. Pause and disconnect stop new invocations, drain accepted work for a bounded interval, deregister tools, and clean up credentials.

Reconfiguration uses `PATCH /api/v1/mcp/connect/{name}`. It stages the new endpoint or headers, requires an `MCP_CONNECT` `approvalToken` receipt before network I/O, and applies the replacement atomically with compensation on failure. Include `If-MCP-Revision` with the card’s current revision; a stale revision returns `409`. Enabled policy is retained only when both tool name and schema hash are unchanged. `rotate-headers` remains as a compatibility alias.

On restart, durable enabled connections reconnect with the persisted explicit tool policy. Tools are not automatically re-enabled merely because the remote server advertises them again. Reconnect failures are recorded as connection state/error metadata and cleanup retries are owner-scoped and idempotent.

## Chat commands

Type `/mcp` with no arguments to print status and help:

```text
/mcp                         status plus help
/mcp help                    print help
/mcp list                    list durable connections
/mcp setup <name>            issue a UI link to add a server
/mcp edit <name>             issue a UI link to edit a server
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

MCP lifecycle approval is intentionally separate from the global agent `/approve` and `/deny` flow. The REST equivalents are `GET /api/v1/mcp/connect/approvals`, `POST /api/v1/mcp/connect/approvals/{taskId}/approve`, and `POST /api/v1/mcp/connect/approvals/{taskId}/reject`.

## Setup and edit links

`/mcp setup <name>` and `/mcp edit <name>` issue a short-lived browser handoff link. The token is HMAC-SHA-256 protected, user-bound, single-use, and valid for ten minutes. The UI redeems it immediately and replaces browser history so the token is not retained in the URL history. A redeemed, expired, or mismatched token returns `403` and must be issued again.

Set `AMSHA_MCP_SETUP_UI_BASE_URL` when links are issued from Telegram or another remote channel. Without it, the link is relative (`/#/mcp/setup?...`) and works only when opened on the same Amsha origin. Channel type and channel ID are included when available for issuance; the browser redemption remains bound to the same Amsha user.

## User scope

Connections are user-private; workspace sharing is not part of the current model. Authenticated REST requests use the authenticated principal, which always takes precedence over client headers.

For local web development with `AMSHA_WEB_REQUIRE_AUTH=false`, the browser sends its stable per-tab `X-AMSHA-PLATFORM-ID` continuity header to MCP REST resources. This is not authentication and is accepted only in that explicitly unauthenticated web mode. It aligns browser REST operations with the same WebSocket/chat platform identity.

Native CLI chat uses the claimed local bootstrap owner when one exists, and falls back to `cli-user` before onboarding. Restart the rebuilt native binary after changing this behavior. Different authenticated users, unauthenticated browser tabs, persistence backends, and data directories intentionally have different scopes.

Skills are different: the current skill store and registry are global, with no `userId` on `SkillDto`; UI and chat therefore see the same skills, but a REST-created skill is not private to one user.

## `.mcp.json` import

Import is opt-in and upload-only. The preview accepts at most 256 KiB, parses local JSON without network I/O, never launches STDIO, and produces a ten-minute single-use preview ID. Apply selects one supported entry and routes it through the normal approval-gated connect or reconfigure flow. The database and vault remain the source of truth; a failed import cannot replace a working connection.

## REST contract

All management requests use the normal Amsha API authentication and JSON responses. Mutating operations return `202 Accepted` with a pending approval task when approval is needed.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/mcp/connect` | List durable connections and state/count/revision metadata. |
| `POST` | `/api/v1/mcp/connect/discover` | Preview a remote server without persistence. |
| `POST` | `/api/v1/mcp/connect` | Create or update a durable connection. |
| `GET` | `/api/v1/mcp/connect/approvals` | Restore pending MCP approvals. |
| `POST` | `/api/v1/mcp/connect/approvals/{taskId}/approve` | Approve and execute a pending operation. |
| `POST` | `/api/v1/mcp/connect/approvals/{taskId}/reject` | Reject a pending operation. |
| `PATCH` | `/api/v1/mcp/connect/{name}` | Reconfigure endpoint or credentials. |
| `POST` | `/api/v1/mcp/connect/{name}/rotate-headers` | Compatibility credential-rotation alias. |
| `POST` | `/api/v1/mcp/connect/{name}/activate` | Activate/reconnect. |
| `POST` | `/api/v1/mcp/connect/{name}/pause` | Pause and drain. |
| `POST` | `/api/v1/mcp/connect/{name}/resume` | Resume through reconnect. |
| `POST` | `/api/v1/mcp/connect/{name}/reconnect` | Reconnect. |
| `GET` | `/api/v1/mcp/connect/{name}/tools` | List discovered tool policies and schemas. |
| `POST` | `/api/v1/mcp/connect/{name}/tools/{toolName}/enable` | Enable one tool after approval. |
| `DELETE` | `/api/v1/mcp/connect/{name}/tools/{toolName}` | Disable one tool. |
| `DELETE` | `/api/v1/mcp/connect/{name}` | Disconnect and clean up. |
| `POST` | `/api/v1/mcp/import/preview` | Parse a bounded uploaded config locally. |
| `POST` | `/api/v1/mcp/import/apply` | Apply one preview entry through normal lifecycle approval. |

Legacy read-only inspection remains at `GET /api/v1/mcp/status` and `GET /api/v1/mcp/tools`. Inbound MCP protocol testing remains documented in [`rest-http/12_mcp.http`](../rest-http/12_mcp.http).

## Security and credentials

Header names are validated as HTTP tokens; duplicates and transport-owned/hop-by-hop headers are rejected. Header values are stored only in `SecretVault` under `USER:<userId>` and resolved just in time. Durable records retain only opaque vault references and non-secret header names. Secrets never appear in chat, Telegram, URLs, import previews, API responses, logs, or audit payloads.

Operation authorization is enforced via single-use checkpoint-backed `approvalToken` receipts issued by `ApprovalReceiptIssuer` upon durable approval rather than ambient or boolean approval flags. The receipt cryptographically binds the requesting actor (`userId`), session ID, outbound purpose (`MCP_CONNECT`), and destination host, and is verified and consumed exactly once during handshake initiation.

## Test the complete flow

1. Open `#/mcp` and run **Test and discover** for a valid remote HTTP MCP endpoint.
2. Approve the `MCP_CONNECT` task.
3. Confirm the form clears and the server card shows `CONNECTED (0/N tools)`.
4. Enable one known tool and approve the `MCP_TOOL_ENABLE` task.
5. Confirm `/mcp` reports `CONNECTED (1/N tools)` and `/mcp tools <name>` lists the discovered metadata.
6. In the same chat scope, request the enabled tool explicitly and verify the returned data is from the MCP server, not model memory.
7. Edit the endpoint or credentials, approve the reconfiguration, and verify the revision/counts update.

Focused implementation checks:

```bash
./mvnw -q -pl mcp,app -am \
  -Dtest=McpConnectResourceTest,McpApprovalServiceTest,McpReflexTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix ui run build
python3 ./scripts/verify_docs.py
```

The REST Client file contains inbound protocol, outbound self-connect, secure-header, error, and agent-registration examples.

## P4 Safety-Gated Protocol-Adapter Evaluation — Retain Native Bridge (2026-08-28)

**Evaluation ID `P4-MCP-01` — Decision: RETAIN.** `dev.langchain4j:langchain4j-mcp:1.17.2-beta27` is BOM-managed but not on the application classpath (`amsha-mcp` 193 tests passing). LangChain4j `1.17.2` MCP is tightly coupled to `McpToolProvider`/`ToolSpecification`/`ToolExecutor` for `AiServices` direct execution and exposes no standalone transport/DTO layer decouplable beneath `McpClientService`/`ExternalMcpToolAdapter`. Adopting it would bypass `ToolInvocationGuard`/`PreflightState`/`LoopStuckDetector`, `NETWORK_OUTBOUND`/`EXECUTE_SHELL` sensitive gating, `HostExecSkillGrant`, HITL `MCP_CONNECT`/`MCP_TOOL_ENABLE`, `SecretVault` `USER:<userId>` zeroing, user-scoped isolation, `FINISH` invariant, and GraalVM `McpReflectionConfig` verification; adding the beta artifact would also violate the `1.17.2` version ceiling (no ad-hoc pins per ADR §Non-goals).

| Dimension | Amsha Native | LangChain4j 1.17.2 | Outcome |
|---|---|---|---|
| Multi-Tenant User Isolation | `USER:<userId>` per-tab | Process-global | **Amsha Superior** |
| Security & HITL Pipeline | `ToolInvocationGuard` + approvals | Bypassed via `ToolExecutor` | **Amsha Superior** |
| Credential Protection | `SecretVault`, zeroed `finally` | In-memory config | **Amsha Superior** |
| Post-Tool Terminal `FINISH` | Enforced | Unenforced | **Amsha Superior** |
| GraalVM Native Image | Verified, no proxies | Unproven | **Amsha Superior** |
| Transport Protocols | HTTP/SSE + Governed STDIO + NDJSON | HTTP/SSE + STDIO | **Parity** |

Disposition per [ADR-LangChain4j](../Ideas-Pending/Architecture/ADR-LangChain4j-Capability-Adoption-and-Amsha-Boundaries.md#mcp) and [Issue 07](../.scratch/lc4j-capability-adoption/issues/07-p4-mcp-protocol-adapter-gate.md): retain native bridge, no version override, P4 gate closed. Evidence: [`.scratch/lc4j-capability-adoption/evidence/06-mcp-evaluation/README.md`](../.scratch/lc4j-capability-adoption/evidence/06-mcp-evaluation/README.md); tracker: [`.scratch/lc4j-capability-adoption/spec.md`](../.scratch/lc4j-capability-adoption/spec.md) P4 row.
