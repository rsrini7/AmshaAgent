-- Durable outbound MCP connection metadata. Secret header values remain in SecretVault.
-- SQLite foreign-key enforcement is not enabled globally, so adapters must delete header rows
-- before deleting their connection row; do not rely on this cascade at runtime.
CREATE TABLE mcp_connection (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL,
    name        TEXT NOT NULL,
    transport   TEXT NOT NULL,
    endpoint    TEXT NOT NULL,
    category    TEXT NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    UNIQUE(user_id, name)
);

CREATE TABLE mcp_connection_header_secret_ref (
    id              TEXT PRIMARY KEY,
    connection_id   TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    normalized_header_name TEXT NOT NULL,
    vault_key_ref   TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    UNIQUE(connection_id, normalized_header_name),
    FOREIGN KEY (connection_id) REFERENCES mcp_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_mcp_connection_user ON mcp_connection(user_id);
CREATE INDEX idx_mcp_header_connection ON mcp_connection_header_secret_ref(connection_id);

CREATE TABLE mcp_connection_cleanup_failure (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    normalized_header_name TEXT NOT NULL,
    vault_key_ref TEXT NOT NULL,
    reason TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_mcp_cleanup_failure_user ON mcp_connection_cleanup_failure(user_id);
CREATE INDEX idx_mcp_cleanup_failure_connection ON mcp_connection_cleanup_failure(connection_id);
