-- Prompt Registry table
-- Stores all system prompt content blocks with versioning, rollout, and audit support.
CREATE TABLE IF NOT EXISTS prompt_registry (
    id                TEXT PRIMARY KEY,
    role_id           TEXT NOT NULL,
    section_key       TEXT NOT NULL,
    content           TEXT NOT NULL,
    version           INTEGER NOT NULL DEFAULT 1,
    status            TEXT NOT NULL DEFAULT 'ACTIVE',
    rollout_percentage INTEGER NOT NULL DEFAULT 100,
    audit_metadata    TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    UNIQUE(role_id, section_key, version)
);

CREATE INDEX IF NOT EXISTS idx_prompt_role ON prompt_registry(role_id);
CREATE INDEX IF NOT EXISTS idx_prompt_status ON prompt_registry(status);
