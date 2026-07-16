-- Synthesized directives (Phase 10: Directive Synthesis)
CREATE TABLE IF NOT EXISTS synthesized_directives (
    id                   TEXT PRIMARY KEY,
    user_id              TEXT NOT NULL,
    text                 TEXT NOT NULL,
    state                TEXT NOT NULL DEFAULT 'CANDIDATE',
    confidence           REAL NOT NULL DEFAULT 0.5,
    source_record_ids    TEXT,
    synthesis_prompt_hash TEXT,
    evaluation_hits      INTEGER NOT NULL DEFAULT 0,
    evaluation_misses    INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE(user_id, text)
);

CREATE INDEX IF NOT EXISTS idx_directives_user ON synthesized_directives(user_id);
CREATE INDEX IF NOT EXISTS idx_directives_state ON synthesized_directives(state);
CREATE INDEX IF NOT EXISTS idx_directives_user_state ON synthesized_directives(user_id, state);
