# ADR: Dedicated MCP Management UI and Channel Control

- **Status**: Proposed
- Date: 2026-08-03
- Last reviewed: 2026-08-04
- Decision owners: Amsha Agent maintainers
- Scope: user-private outbound MCP server connections and their management surfaces

## Context

Amsha already has the core pieces of an outbound MCP client:

- `McpClientService` performs MCP initialization, initialized notification, `tools/list`,
  and remote tool invocation.
- `McpConnectionLifecycleService` validates endpoints, stages credentials in
  `SecretVault`, persists connections, reconnects enabled connections, and retries
  credential cleanup.
- SQLite, PostgreSQL, and filesystem MCP connection adapters exist.
- `McpConnectResource` exposes connect, list, and disconnect REST operations.
- `McpSelfRegisterTool` supports HTTP and STDIO registration through an LLM-driven,
  approval-aware tool path.
- `McpReflex` handles bare `/mcp`, but currently only reports status and tools.
- The first-run MCP onboarding component is a draft list, not ongoing connection
  management.

The current surfaces are not one lifecycle. In particular:

1. REST, `register_mcp_server`, future UI code, and future reflex commands could diverge
   in approval, validation, persistence, and cleanup behavior.
2. `McpConnectResource.connect()` invokes the client without an approval receipt, so the
   governed handshake path is not consistently connected to the existing HITL receipt
   flow.
3. `McpClientService.registerTools()` currently registers every tool returned by
   `tools/list`. That conflicts with a discovery-before-activation UX.
4. `McpConnectionStorePort` is user-scoped today; it does not provide workspace sharing.
5. The UI has no dedicated MCP tab, and `McpReflex.matches()` accepts only exact `/mcp`.

The target use cases are generic remote MCP servers such as public no-auth test servers,
Tapetide, and Sarvam. Their authentication schemes vary: no credentials, bearer tokens,
API-key headers, or several custom headers. Credentials must not be placed in
`application.yaml`, `.env`, onboarding payloads, chat history, Telegram messages, audit
records, or tool results.

## Decision

Build a standalone MCP management surface backed by one shared service. The UI, REST
API, chat commands, Telegram commands, and `register_mcp_server` all delegate to that
service.

Version 1 is explicitly user-private. Workspace sharing is deferred until the identity
ADR's typed workspace vault scope and explicit grants are available. Version 1 targets
remote HTTP MCP; STDIO remains a later, separately privileged host-execution feature.

## Goals

- Add a top-level `#/mcp` UI tab, separate from first-run onboarding.
- Support provider-neutral no-auth, bearer, API-key, and custom-header configuration.
- Make provider presets convenience metadata, not provider-specific code paths.
- Discover tools before any `tools/call` operation.
- Enable explicitly selected tools immediately without restarting Amsha.
- Preserve user isolation across UI, REST, chat, Telegram, and reconnect.
- Keep all outbound operations governed by network policy and HITL.
- Provide safe MCP status and lifecycle commands in chat and Telegram.
- Preserve durable reconnect and retryable credential cleanup.

## Non-goals

- Storing provider credentials in configuration or repository files.
- Accepting raw MCP credentials inline in chat or Telegram commands.
- Invoking a discovered tool during connection testing.
- Launching arbitrary STDIO processes from the browser in version 1.
- Bypassing `PreflightState`, `LoopStuckDetector`, `ToolInvocationGuard`, HITL, or
  outbound network policy.
- Creating Tapetide- or Sarvam-specific MCP client implementations.

## Architecture

### Service placement and responsibilities

Add `McpManagementService` to the `mcp` module. It composes:

- `McpConnectionLifecycleService` for validation, vault references, persistence,
  reconnect, and cleanup;
- `McpClientService` for governed protocol handshakes and invocation;
- `DynamicToolCapabilityRegistry` for user-scoped active tools;
- approval receipt and HITL services;
- audit and persistence ports.

`McpConnectionLifecycleService` remains the lifecycle/storage boundary. It must not be
duplicated. `McpManagementService` owns orchestration, state transitions,
discover-vs-connect semantics, tool policy, and activation.

`McpConnectResource`, `McpReflex`, and `McpSelfRegisterTool` become thin adapters. They
must not directly combine persistence, client calls, registry mutation, and credential
handling.

The service exposes operations equivalent to:

```text
listConnections(userId)
getConnection(name, userId)
discover(intent, approvalContext)
connect(intent, approvalReceipt)
enableTools(name, toolNames, userId)
disableTools(name, toolNames, userId)
pause(name, userId)
resume(name, userId)
reconnect(name, approvalContext)
rotateHeaders(name, secretHeaders, userId)
reconfigure(name, endpoint?, secretHeaders?, expectedRevision, approvalContext)
disconnect(name, userId)
listTools(name, userId)
```

Channel modules use channel/core APIs and do not import app resources.

### Lifecycle

The lifecycle is:

```text
intent
  -> local validation
  -> temporary vault staging, when secrets exist
  -> mandatory audit of requested operation
  -> MCP_CONNECT HITL approval
  -> initialize + notifications/initialized + tools/list
  -> persist successful user-private connection
  -> persist all discovered tools disabled
  -> explicitly enable selected tools
  -> register enabled dynamic tools
```

Handshake and tool discovery are network I/O and never execute inside a persistence
transaction. A failed, rejected, or expired operation compensates temporary vault
references and leaves no active dynamic tools. Approval receipts are single-use,
host-bound, user-bound, expiry-bound, and consumed only at the governed network boundary.

The existing REST path must be changed to use this approval-aware flow. A UI or REST
implementation that silently bypasses HITL is not acceptable.

`approvalTaskId` is retained only while the connection is `PENDING_APPROVAL` or
`CONNECTING`. It is nulled on `CONNECTED`, `PAUSED`, `FAILED`, `DEGRADED`, and
`DISCONNECTED`; the approval and outcome remain in the mandatory audit trail.

### Reconfiguration / edit

Reconfiguration is a first-class lifecycle operation. Users must be able to change an
existing connection's endpoint or credentials without first deleting the connection.
Endpoint and credential changes are applied atomically as one operation; a failed,
rejected, expired, or conflicting edit leaves the previous working connection and
vault references unchanged.

The standard edit flow is:

1. The UI opens an Edit connection panel populated with safe metadata only. Existing
   secret values are never returned; blank credential fields mean keep the current
   vault references, while an explicit clear action removes a selected credential.
2. Test and save validates the proposed endpoint and headers, stages replacement
   secrets, and requests `MCP_CONNECT` approval before any governed handshake. After
   approval, the service performs the handshake and `tools/list` before committing.
3. After approval, the service persists the new endpoint and vault references,
   refreshes discovered tool metadata, reconnects the server, and returns the new
   revision. On any failure it compensates staged secrets and retains the old state.
4. New tools and schema-changed tools remain disabled. An enabled policy is retained
   only when the tool name and schema hash are unchanged. Removed tools remain as
   durable policy rows with `available=false` and `enabled=false`; they are not
   registered or counted. If a removed tool reappears, it is marked available again
   but remains disabled until explicit activation. No edit may implicitly enable a
   newly discovered capability.

Every edit carries `If-MCP-Revision` and returns a safe `409 Conflict` when the row
changed since the UI loaded it. Endpoint changes and credential rotation use the same
shared service operation and approval/audit path. The existing `rotate-headers` route
may remain as a narrow compatibility alias, but it must delegate to reconfiguration.

### Local MCP configuration import

`.mcp.json` is a common convention, but it is not a single universal schema. Amsha
does not currently detect or import it. Version 1 provides an explicit **Import MCP
config** action with these rules:

- The v1 input is an authenticated browser file-picker upload through
  `POST /api/v1/mcp/import/preview`. It is a bounded multipart request (maximum
  256 KiB, or the lower configured limit) containing file content and a display
  filename. It never accepts an arbitrary filesystem path.
- Amsha does not scan the home directory, repository parents, working directories,
  or any server-side root in v1. A future server-side import mode would require a
  separately allowlisted root and path-traversal protection; it is not part of this
  contract.
- Import first produces a bounded preview. It recognizes only supported remote HTTP
  MCP entries and reports unsupported or malformed entries without executing them.
- Preview is local parsing only and performs no network I/O. Endpoint validation and
  remote discovery happen only when Apply enters the normal connect/reconfigure flow.
- Imported name, endpoint, transport, and non-secret metadata populate the normal
  discover/reconfigure flow. All discovered tools remain disabled until explicit
  activation.
- Raw tokens, header values, environment substitutions, and command environment values
  are never copied into connection metadata, logs, chat, or audit payloads. Credentials
  require explicit user confirmation and vault staging; unresolved environment values
  require user re-entry or an explicit vault-key mapping.
- STDIO `command`, `args`, and `env` entries are not launched by the browser import
  path. They remain unsupported or disabled until a separately approved host-execution
  flow exists.
- Importing an entry with an existing user/name pair becomes a normal reconfiguration
  operation with HITL, revision checks, rollback, and tool-policy reconciliation. A
  failed import cannot replace the working connection.
- Preview returns only safe metadata and a short-lived preview operation ID. Apply
  consumes that ID and enters the normal connect/reconfigure approval flow; preview
  is throwaway and does not create a durable connection or retain raw secrets.
- The persisted MCP connection and vault are the source of truth after import.
  `.mcp.json` is not watched or synchronized automatically; external file changes
  require an explicit re-import.

### Startup reconnect

The existing boot reconnect timer remains a governed continuation of an already
approved, persisted connection. It may use the existing pre-approved reconnect path,
but only for a connection whose persisted user authorization and enabled tool policy
already exist. Startup reconnect performs the handshake and `tools/list`, refreshes
schemas, and registers only tools whose policy is explicitly enabled and present in the
current response. Newly discovered tools remain disabled. It does not create new
approval tasks or activate new capabilities.

Manual reconnect, endpoint changes, credential rotation, and any operation that changes
the authorized destination re-enter MCP_CONNECT approval. A failed boot reconnect
transitions the row to `DEGRADED` or `FAILED` with a safe error code and leaves enabled
policy rows dormant until a successful governed reconnect.

### Connection state

Use explicit durable state so UI and channel behavior does not infer connection health
from runtime tool count:

```text
DISCONNECTED
PENDING_APPROVAL
CONNECTING
CONNECTED
PAUSED
DEGRADED
FAILED
```

`enabled` is the desired lifecycle flag; `state` is observed/operation state. `PAUSED`
is the only retained state with `enabled=false`; `DISCONNECTED` may have `enabled=true`
when startup or health recovery is still desired. `CONNECTED`, `DEGRADED`, and `FAILED`
may retain `enabled=true` so governed retry can occur. `PENDING_APPROVAL` and
`CONNECTING` are transient states for an enabled operation.

`DEGRADED` is entered by a health-check or reconnect failure after a previously
successful connection, or when an enabled tool policy cannot be re-established from the
current `tools/list`. `FAILED` is used for an initial or terminal validation/approval
failure. A successful governed reconnect returns the row to `CONNECTED`; a user pause
returns it to `PAUSED` and disables retry.

Migration maps existing rows with `enabled=true` to `DISCONNECTED` before the startup
reconnect pass and existing rows with `enabled=false` to `PAUSED`. Migration never
assumes that an in-memory tool count proves a durable connection is connected.

Disable semantics are precise:

- Atomically mark the connection or tool policy disabled.
- Reject new invocations before any outbound request with a safe not-allowed result.
- Allow already accepted invocations to finish within the existing bounded invocation
  timeout.
- Deregister disabled tools after accepted work drains.
- `disconnect` disables first, waits only for the bounded drain, deregisters all tools,
  and schedules idempotent vault cleanup. A timeout leaves a retryable cleanup record;
  it does not permit new work.

### User-private scope

When web authentication is disabled for local development, the browser sends its stable per-tab
`sessionStorage` platform ID in `X-AMSHA-PLATFORM-ID` to MCP REST endpoints. This value is a continuity
scope, not an authenticated identity, and is accepted only when `amsha.channel.web.require-auth=false`.
An authenticated principal always takes precedence, so chat/WebSocket and MCP REST use the same local
scope without weakening authenticated deployments.

Every version 1 connection, credential reference, tool policy, dynamic registry entry,
setup operation, and command result is scoped to the authenticated `userId`.
`McpConnectionStorePort` continues to expose user-private operations such as
`listByUserId`. No workspace column, workspace-sharing flag, or cross-user fallback is
introduced by this ADR. Workspace sharing requires a separate grant and vault-scope
change.

The list operation must return durable `PAUSED`, `DISCONNECTED`, and `FAILED` rows even
when their active dynamic tool count is zero.

## Persistence model

Extend the existing user-scoped `McpConnectionDto` rather than creating a second
connection aggregate:

```text
McpConnectionDto
  id, userId, name, endpoint, transport, category
  enabled, state, approvalTaskId, revision
  lastErrorCode, createdAt, updatedAt
```

`approvalTaskId` is a safe HITL task identifier. `lastErrorCode` is a bounded
classification, never a provider response body.

Add a user-scoped `McpConnectionToolPolicyDto` and corresponding port operations:

```text
McpConnectionToolPolicyDto
  id, connectionId, userId, toolName, available, enabled, description
  inputSchema, schemaHash, discoveredAt, revision, updatedAt
```

`available` means the tool was present in the latest successful `tools/list`; `enabled`
is the user's explicit activation decision. Current tools are upserted with
`available=true`. Tools absent from a successful refresh remain as policy rows with
`available=false` and are forced to `enabled=false`. Reappearing tools become
available again but remain disabled. The registry registers only policies where both
flags are true, and enabled/discovered counts include only available tools.
`description` and `inputSchema` are bounded, sanitized, non-secret fields with explicit
size limits; `schemaHash` identifies the exact discovered schema. This gives the tool
policy a durable home for UI display and restart recovery. On reconnect, the current
`tools/list` response refreshes these fields for known tools, but new tools remain
disabled. An enable request registers only a currently available tool using its current
schema; if the server cannot be reached, enable fails closed rather than using stale
execution metadata.

The unique key is `(connectionId, userId, toolName)`. If the existing HITL store cannot
retain pending setup/discovery operation ID, expiry, and staged-vault cleanup metadata,
add a small `McpConnectionOperationDto` and store port. It must contain no raw secret.

Implement the same contract in:

- SQLite tables and Flyway migrations;
- PostgreSQL tables and semantically matching migrations;
- filesystem revisioned manifests and cleanup state.

The SQLite and PostgreSQL migrations add the `available` column with the legacy-safe
default `true`; migration/reconciliation then marks tools absent from the next
successful discovery unavailable and disabled. Filesystem manifests persist the same
field and semantics.

The persistence contract covers user filtering, optimistic revisions, state transitions,
tool policy replacement, pending-operation cleanup, and retryable credential cleanup.
No adapter returns raw header values through a DTO.

## API contract

The mutating API includes `PATCH /api/v1/mcp/connect/{name}` for endpoint and
credential reconfiguration. `POST /api/v1/mcp/connect/{name}/rotate-headers` remains
the header-only compatibility alias.

Config import is a separate preview/apply workflow: preview uses
`POST /api/v1/mcp/import/preview` and apply uses `POST /api/v1/mcp/import/apply`.
Version 1 accepts only an authenticated browser file-picker upload through the
preview endpoint
as a bounded multipart request (maximum 256 KiB, or the lower configured limit),
carrying file content and a display filename. It never accepts arbitrary filesystem
paths, scans a server-side root, or creates a second persistence model.

Preserve the existing compatibility surface while making the lifecycle explicit:

```text
GET    /api/v1/mcp/connect
POST   /api/v1/mcp/connect/discover
POST   /api/v1/mcp/connect
GET    /api/v1/mcp/connect/{name}/tools
POST   /api/v1/mcp/connect/{name}/activate
POST   /api/v1/mcp/connect/{name}/tools/{toolName}/enable
DELETE /api/v1/mcp/connect/{name}/tools/{toolName}
POST   /api/v1/mcp/connect/{name}/pause
POST   /api/v1/mcp/connect/{name}/resume
POST   /api/v1/mcp/connect/{name}/reconnect
POST   /api/v1/mcp/connect/{name}/rotate-headers
DELETE /api/v1/mcp/connect/{name}
```

`GET /api/v1/mcp/connect/approvals` returns live, user-private approval tasks so the
MCP panel can restore an enable/connect approval after a browser refresh. It never
returns headers or approval receipts. `GET /api/v1/mcp/connect` returns durable user-private rows even when no dynamic tools
are active. Each row contains at least:

```text
name, endpoint, state, transport, enabledToolCount,
discoveredToolCount, enabled, revision, lastErrorCode
```

The response may include safe timestamps and approval state, but never header values or
provider error bodies. `GET /api/v1/mcp/status` and `GET /api/v1/mcp/tools` remain the
separate aggregate/admin views and are not replaced by the connection list.

All mutating connection and tool-policy requests carry an expected revision. Concurrent
reconnect, rotate, pause, enable, or disconnect operations fail with a safe conflict and
must not overwrite newer state. The UI refreshes the row and asks the user to retry.

This is a deliberate behavioral change behind the existing paths: `GET /mcp/connect`
will no longer infer the complete response from active registry tools, and
`POST /mcp/connect` will no longer report implicit tool activation through
`toolsRegistered`. Existing `McpConnectResourceTest`, REST examples, and validation
scripts must be updated to assert durable state, discovered count, and zero enabled
tools until explicit activation. The separate `/mcp/status` and `/mcp/tools` endpoints
retain their current administrative roles.

### Reconfigure

`PATCH /api/v1/mcp/connect/{name}` is the canonical edit contract. The expected
revision is supplied only through the `If-MCP-Revision` header; it is not accepted in
the JSON body. The body may contain `url`, replacement `headers`, and boolean
`clearCredentials`; `clearCredentials: true` must be sent with an empty header map
and removes stored vault references, while `false` preserves them when headers are
empty. The response never returns header values. A request without an
approval receipt returns a pending MCP operation; the approved request repeats
validation and handshake before committing the replacement. `rotate-headers` uses the
same operation, vault staging, revision, audit, and rollback behavior.

The UI must show the original row while an edit is pending, indicate the pending
operation, and replace it only after the service reports success. A failed test must
not disconnect the current server. A successful endpoint edit may briefly drain and
pause new invocations while the connection is swapped, then returns `CONNECTED`,
`DEGRADED`, or `FAILED` with a bounded error code.

### Discover

`POST /mcp/connect/discover` is a discover-only operation. It validates the endpoint,
obtains approval, performs the MCP handshake and `tools/list`, and returns bounded tool
metadata plus an operation state. It does not persist a durable connection and does not
register dynamic tools. Secret values are temporarily vault-staged and compensated on
success, failure, rejection, or expiry.

Discovery is intentionally a throwaway preview. A later `connect` starts a new operation,
stages its own secret reference, and repeats the handshake and `tools/list`; it does not
reuse the discovery operation or its staged secret. This avoids stale schemas and makes
temporary-secret ownership unambiguous. The UI may carry non-secret endpoint and name
fields forward, but it must treat discovery results as preview data.

### Connect

`POST /mcp/connect` creates or updates a durable connection. It obtains approval when
needed, performs the handshake and `tools/list`, persists the connection and disabled
tool-policy rows, and returns `CONNECTED`, `PENDING_APPROVAL`, or `FAILED`. It does not
activate tools.

### Tool activation and credentials

Tool enable/disable endpoints accept tool names only and register/deregister through the
shared service. Credential rotation uses `rotate-headers`; header values are submitted
only over the authenticated request and are never returned.

All responses contain safe state, counts, names, and bounded error codes. Provider error
bodies, credentials, vault values, and raw tool results are excluded.

## Secure link for authenticated channel setup

Chat and Telegram cannot be used as a raw credential transport. For an authenticated
`/mcp connect` command, create a short-lived setup operation and send a link using:

```text
/#/mcp/setup?token=<opaque-setup-token>
```

The token is a cryptographically random, single-use value. Persist only its keyed
digest, with these attributes:

- TTL: 10 minutes;
- bound to initiating `userId`;
- bound to source channel type and channel ID;
- bound to the pending operation and requested server name;
- consumed only after the authenticated browser principal matches the bound user;
- not consumed on a principal mismatch;
- invalid after successful redemption, expiry, rejection, or operation completion.

The digest is an HMAC-SHA-256 value using the server's existing secret material; the
raw setup token is never persisted. The browser redeems it with
`POST /api/v1/mcp/setup/redeem`, then the backend marks it consumed after validating
the authenticated user and pending operation.

The fragment keeps the token out of ordinary HTTP request logs. The UI redeems it
immediately on load and calls `history.replaceState` before rendering the setup form so
the token is not retained in browser history. The browser redeems it
over HTTPS, enters the remote MCP credential, and submits the secret directly to the
authenticated setup API. The setup token is separate from `ApprovalReceiptIssuer`: the
setup token authorizes credential-entry continuation, while the approval receipt
authorizes the outbound network operation.

If another principal opens the link, the UI shows an authorization error and the backend
does not reveal operation details or consume the token. Telegram receives the link and
status only; it never receives the provider token.

## Transport decision gate

The current transport model is `STDIO | HTTP_SSE`. Before UI implementation, Phase 0
must verify the current Tapetide and Sarvam MCP documentation and identify whether each
requires legacy HTTP/SSE or Streamable HTTP.

Add `STREAMABLE_HTTP` and its governed client behavior before shipping a UI option that
claims to support it. Transport negotiation belongs behind the MCP transport abstraction;
it must not create provider-specific paths. The UI exposes only transports with a tested
adapter. STDIO remains excluded from the browser UI until a separate host-execution
design is approved.

The existing `register_mcp_server` STDIO branch is not removed by this ADR. It remains
available only through its explicitly approved host-execution path in version 1. To keep
the adapter boundary consistent, `McpSelfRegisterTool` delegates STDIO registration to
a transport-specific management operation in the `mcp` module; it does not launch a
process or mutate the registry directly. That operation retains command allowlisting,
environment-secret handling, sandbox/host-exec grants, audit, reconnect, and cleanup
requirements. The dedicated UI and `/mcp` commands expose remote HTTP only in version 1.

The enable path must call the registry's user-scoped registration API rather than
reimplementing registration. The registry remains authoritative for the 128-tool
per-server limit, internal-name collision checks, scope checks, and duplicate handling;
the management service converts those failures into safe enable errors and leaves the
policy disabled.

## User interfaces

### Dedicated UI tab

Add a top-level `#/mcp` route and navigation entry, implemented as a dedicated MCP panel
rather than another onboarding step.

The panel contains:

- connected-server cards with name, host, transport, state, tool count, and last action;
- an Add MCP server form;
- a Custom MCP option and verified provider metadata presets;
- endpoint, display name, transport, category, and authentication fields;
- a Test and discover action that invokes no tool;
- discovered-tool metadata with explicit enable/disable controls;
- reconnect, pause/resume, rotate credentials, and remove actions;
- Edit/reconfigure action for endpoint and credentials without deleting the connection;
- Explicit Import MCP config action with preview and safe-file rules;
- approval progress and safe failure remediation.

Presets may prefill a verified endpoint and header name, but never a credential. Until
Tapetide and Sarvam endpoint/authentication documentation is verified, ship Custom MCP
only. Presets are static UI metadata and do not create provider-specific code paths.

The UI uses the authenticated `ArcClient`, handles optimistic revision conflicts, and
refreshes after approval or reconnect. It never puts secret values in URL parameters,
browser history, local storage, draft JSON, or error messages.

Edit uses a modal or inline panel with safe metadata prefilled and secret fields blank;
it displays `Stored securely` rather than existing header values. Empty secret fields
preserve current vault references, while an explicit clear action removes them. Test
and save leaves the existing row active until the reconfiguration operation is
approved and committed. It never puts secret values in URL parameters, browser
history, local storage, draft JSON, or error messages.

## Chat and Telegram control

Keep bare `/mcp` as a read-only status command. Change reflex matching to parse a command
whose first token is `/mcp`; recognized subcommands are consumed by the reflex and must
not fall through to the agent loop. Unknown subcommands return compact help.

Supported commands:

```text
/mcp
/mcp help
/mcp list
/mcp tools <server>
/mcp setup <name>
/mcp edit <server>
/mcp connect <name> <url>
/mcp enable <server> [tool ...]
/mcp disable <server> [tool ...]
/mcp pause <server>
/mcp resume <server>
/mcp reconnect <server>
/mcp disconnect <server>
/mcp approve <taskId>
/mcp deny <taskId>
```

`/mcp connect` may start a no-auth flow, subject to approval. For authenticated servers
it creates the secure setup link described above. It never accepts bearer tokens or
custom header values in message text.

`/mcp setup <name>` issues an onboarding setup link. `/mcp edit <server>` issues the same short-lived, user/channel-bound link with edit intent; redeeming it opens the existing server's reconfiguration panel with secret fields blank. The reflex derives channel type and conversation ID from `MessageContext`, so Telegram, Discord, Slack, and future channel plugins use the same path without channel-specific MCP logic. The command never accepts credentials, and the link never contains credential values. Editing still uses the normal `RECONFIGURE` approval before network I/O and preserves the existing durable connection until the approved change commits.

MCP lifecycle approvals use `/mcp approve <taskId>` and the REST endpoint
`POST /api/v1/mcp/connect/approvals/{taskId}/approve`; these surfaces accept only
MCP lifecycle task IDs. The global `/approve <taskId>` and `/deny <taskId>` flow
handles agent checkpoint approvals and is not a control path for MCP tasks.
Telegram approval callbacks retain stale-approval denial, user/channel
binding, and `flushPendingNotifications()` behavior. Responses contain only names,
hosts, states, tool counts, task IDs, and next actions. Telegram output continues through
`stripGarbledLines()`.

The web chat and Telegram paths use the shared inbound pipeline and the same management
service. Only rendering differs by channel. Version 1 produces status through the MCP
panel and `/mcp`; it does not automatically send a new chat notice after every connect.

### Existing agent tool

`register_mcp_server` remains available for natural-language setup. It may pass header
values in its approved tool parameters once, but the shared service must immediately
vault-stage and JIT-resolve them. Header values must never appear in tool results, chat
history, audit payloads, logs, or error messages. The agent tool cannot create a second
authorization or persistence path.

## Approval policy by operation

Approval requirements are explicit:

| Operation | Approval | Reason |
| --- | --- | --- |
| Discover | `MCP_CONNECT` / `NETWORK_OUTBOUND` HITL | Remote handshake and tool metadata retrieval |
| Connect | `MCP_CONNECT` / `NETWORK_OUTBOUND` HITL | New durable remote destination |
| Manual reconnect | `MCP_CONNECT` / `NETWORK_OUTBOUND` HITL | New outbound handshake initiated by a user |
| Rotate headers | `MCP_CONNECT` / `NETWORK_OUTBOUND` HITL | Credential-bearing handshake after mutation |
| Reconfigure endpoint or credentials | `MCP_CONNECT` / `NETWORK_OUTBOUND` HITL | Changes the authorized remote destination or credential-bearing handshake |
| Enable tools | `MCP_TOOL_ENABLE` HITL plus audit-before-mutation | Grants executable dynamic capability |
| Disable tools | Authenticated user mutation plus audit | Removes capability; no outbound call |
| Pause/resume | Authenticated user mutation plus audit | Changes desired lifecycle state |
| Disconnect | Authenticated user mutation plus audit | Drains and removes the connection |
| Startup reconnect | Persisted authorization continuation | No new destination or capability is authorized |

`MCP_TOOL_ENABLE` is a typed approval purpose and requires the mandatory
`MCP_TOOL_POLICY_ENABLED` audit event when a tool policy is granted executable
capability. If the existing approval SPI needs a new purpose, add it rather than reusing
a misleading provider-connect label. Chat and Telegram responses include
the returned task ID for discover, connect, reconnect, rotate, and enable; `/mcp approve`
and `/mcp deny` are the MCP lifecycle channel controls. The global `/approve` and
`/deny` commands remain reserved for agent checkpoint approvals. `McpApprovalService`
retains validated pending header maps in process memory for at most 10 minutes while
approval is outstanding. Restart-safe and multi-instance durable pending approvals are
deferred; this limitation must be explicit in operations documentation and is not a
production-ready HA guarantee. Pause, disable, resume, and disconnect show
confirmation and audit status but do not create a network approval task.

## Security invariants

- Every remote URL passes `OutboundHttpPolicyService`, including redirects and DNS
  revalidation.
- Non-local endpoints require HTTPS and reject URL user-info credentials.
- Header names use existing RFC token and forbidden-header validation.
- Secret bytes are resolved just in time, applied to the request, and zeroed on close.
- No LLM call, outbound network call, or tool execution occurs inside a transaction.
- Every mutating operation performs mandatory audit-before-mutation with redacted data.
- User scope is resolved from the authenticated principal, never submitted user IDs or
  chat text.
- Dynamic tools remain subject to `PreflightState`, `LoopStuckDetector`,
  `ToolInvocationGuard`, category policy, and HITL.
- Optional classifier failures preserve deterministic policy decisions and never log
  secret or provider payloads.
- New JSON/JDBI/persistence types are registered in the owning reflection config; no
  type-level reflection shortcut is introduced.

Config import adds no exception to the security model: local files are untrusted input,
import parsers are bounded and schema-validated, secrets are vault-only, and imported
configuration still passes endpoint policy, approval, audit-before-mutation, and
explicit tool activation.

## Delivery plan

### Phase 0: compatibility and transport gate

- Confirm Tapetide and Sarvam endpoint, authentication, and transport documentation.
- Add regression tests for current bare `/mcp` status behavior.
- Define transport capability contracts and implement any required Streamable HTTP
  adapter before the UI exposes that transport.
- Confirm the user-private scope boundary in API and persistence contracts.

### Phase 1: shared service and state model

#### 1A: registration safety and shared boundary

- Add `McpManagementService` in `mcp` and delegate REST and `register_mcp_server` to it.
- Change registration so `tools/list` never implicitly enables every tool.
- Add tool-policy tests, registry-limit tests, and update `McpConnectResourceTest` and
  `McpReflexTest` for the deliberate behavior change.

#### 1B: lifecycle and state

- Extend connection state/revision/error metadata and add tool-policy records/ports.
- Implement discover-only, connect-with-disabled-tools, activation, rotation, pause,
  resume, reconnect, disconnect, and compensation.
- Add unit tests for state transitions, idempotency, approval, scope, drain behavior,
  startup reconnect, and secret cleanup.

### Phase 2: API and persistence

- Add the discover, tools, lifecycle, and credential-rotation endpoints.
- Add SQLite/PostgreSQL migrations and filesystem parity.
- Make list return durable rows independently of runtime tool count.
- Add reflection registration and native-image checks.
- Update REST examples and validation scripts.

### Phase 3: dedicated UI

- Add `#/mcp`, navigation, `ArcClient` methods, API types, and the MCP panel.
- Implement generic remote MCP setup, secure secret entry, discovery, tool selection,
  and lifecycle actions.
- Add setup-token redemption and authenticated browser binding.
- Add verified provider metadata only after Phase 0 documentation checks.

### Phase 3A: edit and explicit config import

- Add Edit connection UI for endpoint and credential changes, with masked secret
  fields, explicit credential clearing, approval progress, rollback messaging, and
  `If-MCP-Revision` conflict reload/retry handling.
- Add browser `.mcp.json` upload, bounded safe preview, unsupported-entry reporting,
  explicit apply/reject, and normal connect/reconfigure approval flow. Never expose
  raw imported secrets or execute imported STDIO entries.
- Reconcile `available`, `enabled`, schema hashes, counts, and registry state after
  a successful edit or import; newly discovered or schema-changed tools remain
  disabled.

### Phase 4: chat and Telegram parity

- Extend `McpReflex` with tokenized subcommand parsing while preserving bare `/mcp`.
- Add secure-link setup for authenticated connections.
- Add approval, success, failure, and cleanup rendering for web chat and Telegram.
- Add Telegram tests for stale approvals, channel binding, and notification flushing.

### Phase 5: external validation and rollout

- Exercise a no-auth public server with handshake and `tools/list` only.
- Exercise Tapetide with one explicitly selected low-risk tool.
- Exercise Sarvam only after the external lifecycle is proven and its credit budget is
  agreed.
- Document credential rotation, pause, removal, recovery, and transport support.

## Verification matrix

| Area | Minimum verification |
| --- | --- |
| Reflex | bare `/mcp`, subcommand parsing, unknown-command consumption, no secret echo |
| REST | owner isolation, approval states, discover-only behavior, safe errors, lifecycle, reconfiguration rollback, secret non-disclosure, revision conflicts; update `McpConnectResourceTest` |
| Core MCP | `McpReflexTest`, management-service state tests, registry-limit tests, startup reconnect policy |
| UI | add/discover/connect/edit endpoint/edit credentials/import config/enable/pause/resume/rotate/remove, secret masking, rollback, approval progress, and revision conflicts |
| Provenance | UI and assistant claim data was “returned by MCP” only when the current operation has correlated `MCP_TOOL_INVOKED` and `TOOL_EXECUTED` evidence |
| Telegram | `/mcp`, approval, setup link, stale approval, channel binding, compact output |
| Transport | initialize, initialized notification, tools/list, required SSE/Streamable HTTP |
| Tool policy | all-discovered-disabled default, explicit enable, disable drain, reconnect policy |
| Secrets | vault-only values, rotation, failed-connect compensation, deletion cleanup |
| Persistence | SQLite/PostgreSQL/filesystem contract and migration parity |
| Native | reflection configuration for new DTOs and persistence types |
| API/UI contract | OpenAPI generation or equivalent client/type updates and `ArcClient` methods |
| REST assets | `rest-http/12_mcp.http` and MCP validation scripts |
| Audit | existing MCP events plus any new event types tested for mandatory classification |
| Documentation | `python3 ./scripts/verify_docs.py` |

For Java changes, run `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`, then
run affected-module compilation and focused MCP/channel tests. Dependency-module changes
also require the prescribed clean install check.

## Alternatives considered

### Keep setup only in onboarding

Rejected. Onboarding is a first-run draft and commit flow; MCP needs ongoing status,
rotation, pause, tool selection, reconnect, and removal.

### Add only UI calls to the existing REST resource

Rejected. Chat, Telegram, and the agent tool would continue to diverge, and the existing
approval gap would remain in a second path.

### Accept credentials in slash commands

Rejected. Chat and Telegram messages are durable operational data and are not an
appropriate secret transport.

### Enable every discovered tool automatically

Rejected. Discovery is not consent to execute every tool. Explicit activation fixes the
current registration behavior while retaining immediate use after review.

### Implement provider-specific connectors

Rejected for the initial release. Generic authenticated MCP headers and a transport
abstraction cover public servers, Tapetide, Sarvam, and future providers without
multiplying code paths.

## Review observations addressed

This section records how implementation review feedback was incorporated. It is not a
second specification; the sections above remain authoritative.

### Phase 1 implementation review

| Observation | Resolution |
| --- | --- |
| Discover persisted durable connections | `McpManagementService.discover` is throwaway: no persistence, no tool policy, `clearHttpConnectionState` in `finally`. |
| REST bypassed HITL | `McpApprovalService` plus `202 ACCEPTED` pending responses; `issueForExplicitApproval` mints single-use receipts consumed at the network boundary. |
| `register_mcp_server` auto-enabled all tools | Delegates to `connectNewInternal` only; all discovered tools persist disabled. |
| Legacy dual paths in `McpConnectResource` | Resource is a thin adapter over `McpManagementService`; legacy orchestration removed. |
| Missing tests | Added `McpManagementServiceTest`, `McpApprovalServiceTest`, updated `McpConnectResourceTest` and `McpReflexTest`, registry drain coverage. |
| No optimistic revision | `If-MCP-Revision` header and `McpRevisionConflictException` on mutating operations. |
| Missing `rotate-headers` | `POST /api/v1/mcp/connect/{name}/rotate-headers` with approval flow. |
| No `MCP_TOOL_ENABLE` purpose | `OutboundPurpose.MCP_TOOL_ENABLE`; enable receipts use a distinct purpose from connect. |
| Setup token gaps | HMAC digest, vault-backed grant persistence, `channelId` binding, `POST /api/v1/mcp/setup/redeem`. |
| Disable without drain | `DynamicToolCapabilityRegistry.drainAndDeregister` used from `McpManagementSupport`. |
| Boot reconnect failure state | Startup reconnect failure transitions durable rows to `DEGRADED` with bounded `lastErrorCode`. |
| Non-ADR `DISCOVERED` state | Removed; ADR state machine only. |

### Follow-up review nits

| Observation | Resolution |
| --- | --- |
| `CONNECTING` documented but unused | `connectInternal` sets `CONNECTING` before governed handshake and clears `approvalTaskId` on `CONNECTED` or `DEGRADED`. |
| Tool-enable audit event unspecified | Added mandatory `SecurityEventType.MCP_TOOL_POLICY_ENABLED`; emitted on successful enable. |
| Two approval command surfaces unclear | Documented above: `/mcp approve` and `/mcp deny` for MCP lifecycle tasks; global `/approve` and `/deny` remain agent-checkpoint only. |
| Pending credentials only in memory | Documented limitation: `McpApprovalService` retains validated header maps in-process for at most 10 minutes; restart-safe durable pending approvals deferred. On startup, orphaned durable `PENDING_APPROVAL` rows are fail-closed to `DISCONNECTED` or `PAUSED`, clear `approvalTaskId`, and emit `APPROVAL_DENIED` with `APPROVAL_LOST_ON_RESTART`. |
| Reject left `PENDING_APPROVAL` stale | `rejectApproval` clears task-bound durable state to `DISCONNECTED` or `PAUSED` with `lastErrorCode=APPROVAL_DENIED`. |
| Legacy `connectForUser` registered all tools | Deprecated overloads delegate to `connectForUserWithEnabledTools` with an empty enabled set. |

### Still deferred (not blockers for Phase 1–3 merge)

- Phase 0 transport verification and `STREAMABLE_HTTP` adapter before UI exposes that transport.
- Phase 4 Telegram integration tests (stale approval, `flushPendingNotifications`, channel binding).
- Phase 5 external provider exercises (public no-auth, Tapetide, Sarvam).
- Restart-safe, multi-instance durable MCP approval queue (explicit release gate below).

## Implementation lessons and resolved integration risks

The implementation and external MCP exercise exposed several risks that are part of
this design, not incidental debugging details:

- **Durable owner versus channel target:** after WebSocket authentication,
  `ChannelUser.platformId` is the durable owner (`usr_...`), while `channelId` is the
  browser platform/session identifier. Approval checkpoints intentionally retain the
  owner for authorization. WebSocket delivery therefore maintains an explicit
  owner-to-active-connection mapping and uses the channel target first, with owner
  fallback for resumed replies. A sender must never assume that the authenticated
  owner ID is a socket ID.
- **Approval creation versus notification delivery:** a checkpoint can be correctly
  persisted while its approval card or resumed final answer is lost at the channel
  boundary. Durable approval state is the source of truth; the UI must refresh pending
  operations on socket reconnect, and delivery code must test both the initial approval
  notification and the post-approval result.
- **Resume identity differs from initial-turn identity:** resume reconstructs channel
  recipients from persisted checkpoint data. Tests must cover the full sequence
  `request -> approval -> resume -> tool result -> final delivery`, not only the initial
  approval. Owner/session lookup is required for refreshes and resumed responses.
- **Restart and refresh are not approval replay:** process-local pending approvals may
  be denied during startup recovery, and a missed one-time notification must not be
  treated as a new approval. The UI must show stale/denied state and require a fresh
  operation rather than repeatedly submitting an old task ID.
- **Tool provenance must be explicit:** a model can produce a plausible answer from
  conversation history after an MCP server reconnects, without invoking the tool in the
  current turn. The UI and assistant must claim “returned by MCP” only when the current
  operation has correlated `MCP_TOOL_INVOKED` and `TOOL_EXECUTED` evidence. A fresh
  session is a useful diagnostic, but provenance must come from the operation trace.
- **Local config is not a universal contract:** `.mcp.json` schemas and secret formats
  vary across MCP clients. Treating a file in the working directory as authoritative
  would create silent configuration drift and could execute untrusted STDIO commands.
  Import must therefore be explicit, bounded, previewed, and normalized into the
  normal user-scoped store/vault flow.
- **Native runtime parity:** channel fixes are ineffective until the native binary is
  rebuilt and restarted. Operational verification records source revision, binary
  rebuild time, WebSocket owner mapping, approval, MCP invocation, execution, and final
  delivery evidence in one trace.
- **External provider behavior:** startup reconnect proves only that the server and
  tool metadata were registered. It does not prove that a user prompt invoked the tool.
  External validation must distinguish `MCP_SERVER_CONNECTED` from
  `MCP_TOOL_INVOKED` and `TOOL_EXECUTED`.

These risks require focused tests and log-safe correlation IDs; secret values, tool
parameters, provider responses, and authorization headers remain excluded from logs.

## Release gates

The current approval bridge is process-local. Restart-safe persistence for pending MCP approval operations is an additional gate before this ADR can move to Accepted; the in-process implementation must not be treated as production-ready across restart or multi-instance deployment.

The edit/reconfiguration gate also requires endpoint and credential changes to be
atomic, vault-safe, revision-checked, approval-aware, and covered by rollback and
tool-policy reconciliation tests. The UI must not expose existing secret values.

For the v1 browser-upload import, the additional gate is: upload size and boundary
tests, malformed-entry tests, secret redaction tests, unsupported-STDIO tests,
explicit apply/reject behavior, and proof that external files do not silently mutate
durable connections.

This ADR remains Proposed until the Phase 0 transport check, user-private persistence
contract, discover/connect API split, and secure-link contract are accepted by the
maintainers. It may move to Accepted once those gates are resolved and before Phase 1
implementation begins.
