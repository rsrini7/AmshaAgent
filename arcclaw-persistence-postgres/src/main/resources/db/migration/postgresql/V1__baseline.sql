-- ArcClaw V1 Baseline — PostgreSQL schema
-- All CREATE TABLE / INDEX statements mirror the JPA entities.
-- Data folder is deleted on schema changes, so no ALTER / data migration needed.

-- Advisory locks for distributed coordination (§T5-3)
CREATE TABLE IF NOT EXISTS advisory_lock (
    lock_key   TEXT NOT NULL PRIMARY KEY,
    owner_id   TEXT,
    expires_at TIMESTAMP WITH TIME ZONE
);

-- Agent checkpoints (session state, HITL flow)
CREATE TABLE IF NOT EXISTS agent_checkpoint (
    checkpoint_id            BIGSERIAL PRIMARY KEY,
    version                  INTEGER NOT NULL,
    session_id               TEXT NOT NULL,
    agent_role               TEXT NOT NULL,
    status                   TEXT NOT NULL,
    autonomy_level           TEXT,
    state_json               TEXT,
    channel_platform_id      TEXT,
    channel_type             TEXT,
    original_goal            TEXT,
    failed_at                TIMESTAMP WITH TIME ZONE,
    completed_at             TIMESTAMP WITH TIME ZONE,
    fail_reason              TEXT,
    pending_tool_name        TEXT,
    pending_tool_role        TEXT,
    batch_tool_decisions     TEXT,
    pending_tool_parameters  TEXT,
    pre_executed_results     TEXT,
    max_iterations           INTEGER,
    iterations_used          INTEGER NOT NULL DEFAULT 0,
    retry_after_ms           BIGINT,
    system_note              TEXT,
    context_data             TEXT,
    error_code               TEXT,
    trace_id                 TEXT,
    root_cause_class         TEXT,
    parent_checkpoint_id     BIGINT,
    depth                    INTEGER NOT NULL DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(session_id, agent_role)
);

-- Cron job schedule entries
CREATE TABLE IF NOT EXISTS agent_cron (
    id                  TEXT PRIMARY KEY,
    sessionId           TEXT NOT NULL,
    userId              TEXT NOT NULL,
    chatId              TEXT NOT NULL,
    channelType         TEXT,
    channelPlatformId   TEXT,
    cronExpression      TEXT NOT NULL,
    timezone            TEXT NOT NULL DEFAULT 'UTC',
    runonce             BOOLEAN NOT NULL DEFAULT FALSE,
    goal                TEXT NOT NULL,
    nextFireTime        TIMESTAMP WITH TIME ZONE NOT NULL,
    lastFireTime        TIMESTAMP WITH TIME ZONE,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    executionRole       TEXT,
    errorReason         TEXT,
    lastDeliveryId      TEXT,
    createdAt           TIMESTAMP WITH TIME ZONE NOT NULL,
    updatedAt           TIMESTAMP WITH TIME ZONE
);

-- Agent cron job runs (execution history)
CREATE TABLE IF NOT EXISTS agent_cron_run (
    id              TEXT PRIMARY KEY,
    cronId          TEXT NOT NULL,
    sessionId       TEXT NOT NULL,
    status          TEXT NOT NULL,
    startedAt       TIMESTAMP WITH TIME ZONE NOT NULL,
    finishedAt      TIMESTAMP WITH TIME ZONE,
    errorReason     TEXT,
    iterationsUsed  INTEGER NOT NULL DEFAULT 0,
    inputTokens     INTEGER NOT NULL DEFAULT 0,
    outputTokens    INTEGER NOT NULL DEFAULT 0,
    usdCost         NUMERIC(10,6) NOT NULL DEFAULT 0.0,
    createdAt       TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (cronId) REFERENCES agent_cron(id) ON DELETE CASCADE
);

-- Agent skills (YAML-defined)
CREATE TABLE IF NOT EXISTS agent_skill (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    definitionYaml  TEXT NOT NULL,
    createdAt       TIMESTAMP WITH TIME ZONE,
    updatedAt       TIMESTAMP WITH TIME ZONE
);

-- Immutable audit event log (WORM-protected by triggers below)
CREATE TABLE IF NOT EXISTS audit_event (
    id               UUID NOT NULL PRIMARY KEY,
    sessionId        TEXT NOT NULL,
    userId           TEXT,
    agentRole        TEXT NOT NULL,
    goalHash         TEXT NOT NULL,
    outcome          TEXT NOT NULL,
    eventType        TEXT NOT NULL,
    eventDetail      TEXT,
    inputTokens      INTEGER NOT NULL,
    outputTokens     INTEGER NOT NULL,
    usdCost          REAL NOT NULL,
    timestamp        TIMESTAMP WITH TIME ZONE NOT NULL,
    iterationsUsed   INTEGER NOT NULL DEFAULT 0,
    app_version      TEXT,
    idempotency_key  TEXT,
    sequence         BIGINT NOT NULL,
    hmacHash         TEXT NOT NULL,
    prevHash         TEXT NOT NULL
);

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

-- Encrypted secrets (AES-256-GCM-SIV ciphertext per key per session)
CREATE TABLE IF NOT EXISTS encrypted_secrets (
    session_id             TEXT NOT NULL,
    key_name               TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    ciphertext             BYTEA NOT NULL,
    nonce                  BYTEA NOT NULL,
    PRIMARY KEY (session_id, key_name)
);

-- RAG text chunks
CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_id   TEXT PRIMARY KEY,
    text       TEXT NOT NULL,
    source     TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Reasoning steps per checkpoint
CREATE TABLE IF NOT EXISTS reasoning_step (
    id               BIGSERIAL PRIMARY KEY,
    checkpoint_id    BIGINT NOT NULL,
    sequence_num     INTEGER NOT NULL,
    thoughtJson      TEXT,
    action           TEXT,
    actionInputJson  TEXT,
    observation      TEXT,
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    success          BOOLEAN,
    error_code       TEXT,
    trace_id         TEXT,
    root_cause_class TEXT
);

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

-- ── Indexes ──────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_cron_nextfire ON agent_cron(nextFireTime) WHERE status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_cron_status    ON agent_cron(status);
CREATE INDEX IF NOT EXISTS idx_cron_userid    ON agent_cron(userId);

CREATE INDEX IF NOT EXISTS idx_cron_run_cronid ON agent_cron_run(cronId);
CREATE INDEX IF NOT EXISTS idx_cron_run_status ON agent_cron_run(status);
CREATE INDEX IF NOT EXISTS idx_cron_run_started ON agent_cron_run(startedAt);

CREATE INDEX IF NOT EXISTS idx_audit_session   ON audit_event (sessionId);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_event (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_userId    ON audit_event (userId);

-- ── WORM triggers (audit_event immutability) ────────────────────────────

CREATE OR REPLACE FUNCTION prevent_audit_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_audit_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS prevent_audit_update ON audit_event;
CREATE TRIGGER prevent_audit_update
BEFORE UPDATE ON audit_event
FOR EACH ROW EXECUTE FUNCTION prevent_audit_update();

DROP TRIGGER IF EXISTS prevent_audit_delete ON audit_event;
CREATE TRIGGER prevent_audit_delete
BEFORE DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION prevent_audit_delete();

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
