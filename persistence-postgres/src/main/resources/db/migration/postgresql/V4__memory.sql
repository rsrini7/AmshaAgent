-- Chat, compacted, retrieval, and tiered memory

-- Extensions (pgvector is optional — only loaded if available)
DO $$ BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector not available — archival vector search degraded to BM25-only';
END $$;

-- Chat memory (JSON blob per session)
CREATE TABLE IF NOT EXISTS chat_memory (
    memory_id     TEXT PRIMARY KEY,
    messages_json TEXT NOT NULL,
    metadata_json TEXT
);

-- Context compaction summaries
CREATE TABLE IF NOT EXISTS compaction_summary (
    session_id         TEXT PRIMARY KEY,
    summary            TEXT NOT NULL,
    compacted_at       TIMESTAMP WITH TIME ZONE,
    messages_compacted INTEGER NOT NULL,
    user_id            TEXT,
    tenant_id          TEXT
);

-- RAG text chunks
CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_id   TEXT PRIMARY KEY,
    text       TEXT NOT NULL,
    source     TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- pgvector extension for vector similarity search
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        CREATE EXTENSION IF NOT EXISTS vector;

        -- Embedding storage table for RAG vector search.
        -- Dimension 768 matches nomic-embed-text and jina-embeddings-v5-text-nano.
        -- pgvector requires EXACT match between model output and column dimension.
        -- If you switch models, recreate this table with the new dimension.
        -- HNSW index max is 2000 dimensions; for larger models (3072), drop the index.
        EXECUTE 'CREATE TABLE IF NOT EXISTS rag_chunk_embedding (
            chunk_id    TEXT PRIMARY KEY REFERENCES rag_chunk(chunk_id) ON DELETE CASCADE,
            embedding   vector(768)
        )';

        -- HNSW index for fast approximate nearest neighbor search
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding_vec
            ON rag_chunk_embedding
            USING hnsw (embedding vector_cosine_ops)';
    END IF;
END $$;

-- Tiered Memory: Core Memory blocks (Phase 1)
CREATE TABLE IF NOT EXISTS core_memory_blocks (
    block_id     TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    block_key    TEXT NOT NULL,
    content      TEXT NOT NULL,
    version      INTEGER NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, block_key)
);

-- Tiered Memory: Recall Memory (Phase 2)
-- Exact conversation history preservation with full-text search
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
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    metadata_json  TEXT,
    UNIQUE(session_id, turn_number, content_hash)
);

ALTER TABLE recall_messages ADD COLUMN IF NOT EXISTS content_tsv tsvector;
CREATE INDEX IF NOT EXISTS idx_recall_content_fts ON recall_messages USING GIN(content_tsv);

CREATE OR REPLACE FUNCTION recall_messages_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('english', NEW.content);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS recall_messages_tsv_trigger ON recall_messages;
CREATE TRIGGER recall_messages_tsv_trigger
    BEFORE INSERT OR UPDATE ON recall_messages
    FOR EACH ROW EXECUTE FUNCTION recall_messages_tsv_update();

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
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    access_count    INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_archival_user ON archival_chunks(user_id);
CREATE INDEX IF NOT EXISTS idx_archival_type ON archival_chunks(chunk_type);
CREATE INDEX IF NOT EXISTS idx_archival_created ON archival_chunks(created_at);

ALTER TABLE archival_chunks ADD COLUMN IF NOT EXISTS content_tsv tsvector;
CREATE INDEX IF NOT EXISTS idx_archival_content_fts ON archival_chunks USING GIN(content_tsv);

CREATE OR REPLACE FUNCTION archival_chunks_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('english', NEW.content);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS archival_chunks_tsv_trigger ON archival_chunks;
CREATE TRIGGER archival_chunks_tsv_trigger
    BEFORE INSERT OR UPDATE ON archival_chunks
    FOR EACH ROW EXECUTE FUNCTION archival_chunks_tsv_update();

-- pgvector: embedding column for vector similarity search (Phase 5b)
-- Only created if pgvector extension loaded successfully
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        ALTER TABLE archival_chunks ADD COLUMN IF NOT EXISTS embedding vector(768);
        CREATE INDEX IF NOT EXISTS idx_archival_embedding ON archival_chunks
            USING hnsw (embedding vector_cosine_ops);
    ELSE
        RAISE NOTICE 'pgvector not loaded — skipping embedding column and HNSW index';
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════
