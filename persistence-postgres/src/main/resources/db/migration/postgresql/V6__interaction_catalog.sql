-- Sessions, prompts, and MCP connection catalog

-- Session registry (channel/user → stable session ID mapping)
CREATE TABLE IF NOT EXISTS session_registry (
    id           BIGSERIAL PRIMARY KEY,
    channel_type TEXT NOT NULL,
    channel_id   TEXT NOT NULL,
    user_id      TEXT NOT NULL,
    session_id   TEXT NOT NULL UNIQUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(channel_type, channel_id, user_id)
);

-- Prompt Registry (versioned, multi-backend)
CREATE TABLE IF NOT EXISTS prompt_registry (
    id                TEXT PRIMARY KEY,
    role_id           TEXT NOT NULL,
    section_key       TEXT NOT NULL,
    content           TEXT NOT NULL,
    version           INTEGER NOT NULL DEFAULT 1,
    status            TEXT NOT NULL DEFAULT 'ACTIVE',
    rollout_percentage INTEGER NOT NULL DEFAULT 100,
    audit_metadata    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(role_id, section_key)
);

CREATE INDEX IF NOT EXISTS idx_prompt_role ON prompt_registry(role_id);
CREATE INDEX IF NOT EXISTS idx_prompt_status ON prompt_registry(status);

CREATE TABLE IF NOT EXISTS session_catalog (
    id TEXT PRIMARY KEY,
    owner_key TEXT NOT NULL,
    channel_type TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    session_id TEXT NOT NULL UNIQUE,
    role TEXT,
    goal_preview TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    seq INTEGER NOT NULL,
    active BOOLEAN NOT NULL,
    status TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_session_catalog_active
    ON session_catalog(owner_key, channel_type, channel_id) WHERE active = TRUE;
CREATE INDEX IF NOT EXISTS idx_session_catalog_scope_used
    ON session_catalog(owner_key, channel_type, channel_id, last_used_at);
CREATE INDEX IF NOT EXISTS idx_session_catalog_scope_active
    ON session_catalog(owner_key, channel_type, channel_id, active);

-- Durable outbound MCP connection metadata. Secret header values remain in SecretVault.
CREATE TABLE mcp_connection (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL,
    name        TEXT NOT NULL,
    transport   TEXT NOT NULL,
    endpoint    TEXT NOT NULL,
    category    TEXT NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(user_id, name)
);

CREATE TABLE mcp_connection_header_secret_ref (
    id              TEXT PRIMARY KEY,
    connection_id   TEXT NOT NULL REFERENCES mcp_connection(id) ON DELETE CASCADE,
    user_id         TEXT NOT NULL,
    normalized_header_name TEXT NOT NULL,
    vault_key_ref   TEXT NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(connection_id, normalized_header_name)
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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_mcp_cleanup_failure_user ON mcp_connection_cleanup_failure(user_id);
CREATE INDEX idx_mcp_cleanup_failure_connection ON mcp_connection_cleanup_failure(connection_id);
