# amsha-mcp

MCP (Model Context Protocol) bridge: an inbound MCP server that exposes Amsha Agent's
internal tools, and an outbound MCP client for dynamic tool loading from remote servers.

## Outbound connections and credential headers

`POST /api/v1/mcp/connect` registers a remote HTTP/SSE MCP server, performs the MCP
handshake, and loads its tools into the agent loop. Connections may include HTTP header
credentials:

```json
POST /api/v1/mcp/connect
{
  "name": "vendor-docs",
  "url": "https://mcp.example.com/mcp",
  "headers": { "Authorization": "Bearer …", "X-Tenant-Id": "…" }
}
```

- Header **values** are stored only in `SecretVault`, under a stable owner-scoped
  partition `USER:<userId>`. The durable connection record (`McpConnectionStorePort`,
  with SQLite / PostgreSQL / filesystem adapters) keeps only an opaque vault reference
  and the normalized, non-secret header name.
- Values are resolved just-in-time by `McpConnectionCredentialPort.resolveHeaders(...)`
  into an `AutoCloseable ResolvedMcpHeaders` that zeroes its secret bytes after the
  request. They never leave `SecretVault` into logs, audit payloads, tool results, or
  API responses.
- Validation: RFC 7230 token name grammar, lower-cased for case-insensitive uniqueness,
  rejection of transport-owned / hop-by-hop / framing headers, and endpoint URLs without
  user-info credentials. Registration is HITL-gated (`NETWORK_OUTBOUND` / admin API key).
- Durable enabled connections are reconnected on startup by
  `McpConnectionLifecycleService.reconnectEnabledConnections()`; failed credential
  cleanup is retried idempotently via owner-scoped retry records.

## Configuration

- `amsha.mcp.server-enabled` (Env: `AMSHA_MCP_SERVER_ENABLED`, default `true`): Enable the outbound MCP connection REST API.
- `amsha.mcp.setup-token-secret` (Env: `AMSHA_MCP_SETUP_TOKEN_SECRET`, default empty): HMAC-SHA-256 secret for ten-minute, single-use, user-bound channel-to-UI setup links. Set a stable random value in production; an empty value creates a process-local secret and invalidates links on restart.
- `amsha.mcp.health-check-interval-ms` (Env: `AMSHA_MCP_HEALTH_CHECK_INTERVAL_MS`, default `15000`): Interval to check health of active SSE/HTTP outbound MCP connections.
- `amsha.mcp.podman.health-check-interval-ms` (Env: `AMSHA_MCP_PODMAN_HEALTH_CHECK_INTERVAL_MS`, default `15000`): Interval to check health of active Podman/Stdio outbound MCP connections.

## Key types

- `McpClientService` — connect/disconnect and just-in-time header application at request time.
- `McpConnectionLifecycleService` — validation, durable persist, reconnect, and cleanup.
- `McpConnectionCredentialPort` / `DefaultMcpConnectionCredentialPort` — ephemeral header
  reference store and JIT resolution.
- `McpConnectionStorePort` + `McpConnectionDto` / `McpConnectionHeaderSecretRefDto` —
  persistence port and non-secret DTOs (three backend adapters).

See [Architecture_Invariants.md](../docs/Architecture_Invariants.md) §5.20.
