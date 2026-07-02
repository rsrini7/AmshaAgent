CREATE TABLE IF NOT EXISTS extracted_memories (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    record_type     TEXT NOT NULL,
    content         TEXT NOT NULL,
    confidence      REAL NOT NULL DEFAULT 0.5,
    content_hash    TEXT NOT NULL,
    source_event_ids TEXT,
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(session_id, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_extracted_session ON extracted_memories(session_id);
CREATE INDEX IF NOT EXISTS idx_extracted_user ON extracted_memories(user_id);
CREATE INDEX IF NOT EXISTS idx_extracted_type ON extracted_memories(record_type);
CREATE INDEX IF NOT EXISTS idx_extracted_hash ON extracted_memories(content_hash);
