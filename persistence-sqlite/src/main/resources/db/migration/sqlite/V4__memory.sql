-- Chat, compacted, retrieval, and tiered memory

-- Chat memory (JSON blob per session)
CREATE TABLE IF NOT EXISTS chat_memory (
    memory_id     TEXT PRIMARY KEY,
    messages_json TEXT NOT NULL,
    metadata_json TEXT
);

-- Context compaction summaries (backed by FTS5 for full-text search)
CREATE TABLE IF NOT EXISTS compaction_summary (
    session_id         TEXT PRIMARY KEY,
    summary            TEXT NOT NULL,
    compacted_at       TEXT,
    messages_compacted INTEGER NOT NULL,
    user_id            TEXT,
    tenant_id          TEXT
);

-- RAG text chunks
CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_id   TEXT PRIMARY KEY,
    text       TEXT NOT NULL,
    source     TEXT,
    created_at TEXT NOT NULL
);

-- Tiered Memory: Core Memory blocks (Phase 1)
CREATE TABLE IF NOT EXISTS core_memory_blocks (
    block_id     TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    block_key    TEXT NOT NULL,
    content      TEXT NOT NULL,
    version      INTEGER NOT NULL DEFAULT 1,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    UNIQUE(user_id, block_key)
);

-- Tiered Memory: Recall Memory (Phase 2)
-- Exact conversation history preservation with FTS5 searchability
CREATE TABLE IF NOT EXISTS recall_messages (
    message_id     TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    user_id        TEXT,
    turn_number    INTEGER NOT NULL,
    content_hash   TEXT NOT NULL,
    role           TEXT NOT NULL,
    message_type   TEXT NOT NULL,
    content        TEXT NOT NULL,
    tool_name      TEXT,
    tool_call_id   TEXT,
    has_code       BOOLEAN DEFAULT FALSE,
    has_error      BOOLEAN DEFAULT FALSE,
    created_at     TEXT NOT NULL,
    metadata_json  TEXT,
    UNIQUE(session_id, turn_number, content_hash)
);

CREATE VIRTUAL TABLE IF NOT EXISTS recall_messages_fts USING fts5(
    content,
    content='recall_messages',
    content_rowid='rowid'
);

CREATE TRIGGER IF NOT EXISTS recall_ai AFTER INSERT ON recall_messages BEGIN
  INSERT INTO recall_messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER IF NOT EXISTS recall_ad AFTER DELETE ON recall_messages BEGIN
  INSERT INTO recall_messages_fts(recall_messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
END;

CREATE TRIGGER IF NOT EXISTS recall_au AFTER UPDATE ON recall_messages BEGIN
  INSERT INTO recall_messages_fts(recall_messages_fts, rowid, content) VALUES('delete', old.rowid, old.content);
  INSERT INTO recall_messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE INDEX IF NOT EXISTS idx_recall_session ON recall_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_recall_user ON recall_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_recall_created ON recall_messages(created_at);

-- Tiered Memory: Archival Memory (Phase 5)
-- Semantic long-term knowledge with typed chunks, provenance, and access tracking
CREATE TABLE IF NOT EXISTS archival_chunks (
    chunk_id        TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    content         TEXT NOT NULL,
    chunk_type      TEXT NOT NULL,
    source_session  TEXT,
    source_message  TEXT,
    tags            TEXT,
    confidence      REAL NOT NULL DEFAULT 0.5,
    access_count    INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_archival_user ON archival_chunks(user_id);
CREATE INDEX IF NOT EXISTS idx_archival_type ON archival_chunks(chunk_type);
CREATE INDEX IF NOT EXISTS idx_archival_created ON archival_chunks(created_at);

CREATE VIRTUAL TABLE IF NOT EXISTS archival_chunks_fts USING fts5(
    content,
    content='archival_chunks',
    content_rowid='rowid'
);

CREATE TRIGGER IF NOT EXISTS archival_ai AFTER INSERT ON archival_chunks BEGIN
    INSERT INTO archival_chunks_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER IF NOT EXISTS archival_ad AFTER DELETE ON archival_chunks BEGIN
    INSERT INTO archival_chunks_fts(archival_chunks_fts, rowid, content) VALUES('delete', old.rowid, old.content);
END;

CREATE TRIGGER IF NOT EXISTS archival_au AFTER UPDATE ON archival_chunks BEGIN
    INSERT INTO archival_chunks_fts(archival_chunks_fts, rowid, content) VALUES('delete', old.rowid, old.content);
    INSERT INTO archival_chunks_fts(rowid, content) VALUES (new.rowid, new.content);
END;
