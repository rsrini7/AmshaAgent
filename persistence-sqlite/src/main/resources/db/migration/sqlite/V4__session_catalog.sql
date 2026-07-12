CREATE TABLE IF NOT EXISTS session_catalog (
    id TEXT PRIMARY KEY,
    owner_key TEXT NOT NULL,
    channel_type TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    session_id TEXT NOT NULL UNIQUE,
    role TEXT,
    goal_preview TEXT,
    created_at TEXT NOT NULL,
    last_used_at TEXT NOT NULL,
    seq INTEGER NOT NULL,
    active INTEGER NOT NULL,
    status TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_session_catalog_active
    ON session_catalog(owner_key, channel_type, channel_id) WHERE active = 1;
CREATE INDEX IF NOT EXISTS idx_session_catalog_scope_used
    ON session_catalog(owner_key, channel_type, channel_id, last_used_at);
CREATE INDEX IF NOT EXISTS idx_session_catalog_scope_active
    ON session_catalog(owner_key, channel_type, channel_id, active);
