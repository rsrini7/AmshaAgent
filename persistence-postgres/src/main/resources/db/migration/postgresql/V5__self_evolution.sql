-- Self-evolution events, extracted memories, and directives

-- Self-Evolution trace events (Phase 8)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS evolution_events (
    event_id        TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    role            TEXT,
    content         TEXT,
    tool_name       TEXT,
    turn_number     INTEGER,
    metadata_json   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(session_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_evolution_session ON evolution_events(session_id);
CREATE INDEX IF NOT EXISTS idx_evolution_user ON evolution_events(user_id);
CREATE INDEX IF NOT EXISTS idx_evolution_type ON evolution_events(event_type);
CREATE INDEX IF NOT EXISTS idx_evolution_created ON evolution_events(created_at);

CREATE TABLE IF NOT EXISTS extracted_memories (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    record_type     TEXT NOT NULL,
    content         TEXT NOT NULL,
    confidence      REAL NOT NULL DEFAULT 0.5,
    content_hash    TEXT NOT NULL,
    source_event_ids TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(session_id, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_extracted_session ON extracted_memories(session_id);
CREATE INDEX IF NOT EXISTS idx_extracted_user ON extracted_memories(user_id);
CREATE INDEX IF NOT EXISTS idx_extracted_type ON extracted_memories(record_type);
CREATE INDEX IF NOT EXISTS idx_extracted_hash ON extracted_memories(content_hash);

-- Synthesized directives (Phase 10: Directive Synthesis)
CREATE TABLE IF NOT EXISTS synthesized_directives (
    id                   TEXT PRIMARY KEY,
    user_id              TEXT NOT NULL,
    text                 TEXT NOT NULL,
    state                TEXT NOT NULL DEFAULT 'CANDIDATE',
    confidence           DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    source_record_ids    TEXT,
    synthesis_prompt_hash TEXT,
    evaluation_hits      INTEGER NOT NULL DEFAULT 0,
    evaluation_misses    INTEGER NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE(user_id, text)
);

CREATE INDEX IF NOT EXISTS idx_directives_user ON synthesized_directives(user_id);
CREATE INDEX IF NOT EXISTS idx_directives_state ON synthesized_directives(state);
CREATE INDEX IF NOT EXISTS idx_directives_user_state ON synthesized_directives(user_id, state);
