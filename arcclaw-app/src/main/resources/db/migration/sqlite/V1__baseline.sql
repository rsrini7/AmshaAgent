-- ArcClaw V1 Baseline — SQLite schema
-- All CREATE TABLE / INDEX / TRIGGER statements extracted from JPA entities and schema enforcers.
-- No ALTER statements: during active development the data folder is deleted, so schema is always fresh.

-- Advisory locks for distributed coordination (§T5-3)
CREATE TABLE advisory_lock (
    lock_key   TEXT NOT NULL PRIMARY KEY,
    owner_id   TEXT,
    expires_at TEXT
);

-- Agent checkpoints (session state, HITL flow)
CREATE TABLE agent_checkpoint (
    checkpoint_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    version                  INTEGER NOT NULL,
    session_id               TEXT NOT NULL,
    agent_role               TEXT NOT NULL,
    status                   TEXT NOT NULL,
    autonomy_level           TEXT,
    state_json               TEXT,
    channel_platform_id      TEXT,
    channel_type             TEXT,
    original_goal            TEXT,
    failed_at                TEXT,
    completed_at             TEXT,
    fail_reason              TEXT,
    pending_tool_name        TEXT,
    pending_tool_role        TEXT,
    batch_tool_decisions     TEXT,
    pending_tool_parameters  TEXT,
    pre_executed_results     TEXT,
    max_iterations           INTEGER,
    iterations_used          INTEGER NOT NULL DEFAULT 0,
    retry_after_ms           INTEGER,
    system_note              TEXT,
    context_data             TEXT,
    error_code               TEXT,
    trace_id                 TEXT,
    root_cause_class         TEXT,
    parent_checkpoint_id     INTEGER,
    depth                    INTEGER NOT NULL DEFAULT 0,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL,
    UNIQUE(session_id, agent_role)
);

-- Cron job schedule entries
CREATE TABLE agent_cron (
    id                  TEXT PRIMARY KEY,
    sessionId           TEXT NOT NULL,
    userId              TEXT NOT NULL,
    chatId              TEXT NOT NULL,
    channelType         TEXT,
    channelPlatformId   TEXT,
    cronExpression      TEXT NOT NULL,
    timezone            TEXT NOT NULL DEFAULT 'UTC',
    runonce             INTEGER NOT NULL DEFAULT 0,
    goal                TEXT NOT NULL,
    nextFireTime        TEXT NOT NULL,
    lastFireTime        TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    executionRole       TEXT,
    errorReason         TEXT,
    lastDeliveryId      TEXT,
    createdAt           TEXT NOT NULL,
    updatedAt           TEXT
);

-- Agent skills (YAML-defined)
CREATE TABLE agent_skill (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    definitionYaml  TEXT NOT NULL,
    createdAt       TEXT,
    updatedAt       TEXT
);

-- Immutable audit event log (WORM-protected by triggers below)
CREATE TABLE audit_event (
    id               BLOB NOT NULL PRIMARY KEY,
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
    timestamp        TEXT NOT NULL,
    iterationsUsed   INTEGER NOT NULL DEFAULT 0,
    app_version      TEXT,
    idempotency_key  TEXT,
    sequence         BIGINT NOT NULL,
    hmacHash         TEXT NOT NULL,
    prevHash         TEXT NOT NULL
);

-- Chat memory (JSON blob per session)
CREATE TABLE chat_memory (
    memory_id     TEXT PRIMARY KEY,
    messages_json TEXT NOT NULL,
    metadata_json TEXT
);

-- Context compaction summaries (backed by FTS5 for full-text search)
CREATE TABLE compaction_summary (
    sessionId          TEXT PRIMARY KEY,
    summary            TEXT NOT NULL,
    compactedAt        TEXT,
    messagesCompacted  INTEGER NOT NULL,
    user_id            TEXT,
    created_at         TEXT
);

-- Encrypted secrets (AES-256-GCM-SIV ciphertext per key per session)
CREATE TABLE encrypted_secrets (
    session_id            TEXT NOT NULL,
    key_name              TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    ciphertext            BLOB NOT NULL,
    nonce                 BLOB NOT NULL,
    PRIMARY KEY (session_id, key_name)
);

-- RAG text chunks
CREATE TABLE rag_chunk (
    chunk_id   TEXT PRIMARY KEY,
    text       TEXT NOT NULL,
    source     TEXT,
    created_at TEXT NOT NULL
);

-- Reasoning steps per checkpoint
CREATE TABLE reasoning_step (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    checkpoint_id    BIGINT NOT NULL,
    sequence_num     INTEGER NOT NULL,
    thoughtJson      TEXT,
    action           TEXT,
    actionInputJson  TEXT,
    observation      TEXT,
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    success          INTEGER,
    error_code       TEXT,
    trace_id         TEXT,
    root_cause_class TEXT
);

-- Session registry (channel/user → stable session ID mapping)
CREATE TABLE session_registry (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_type TEXT NOT NULL,
    channel_id   TEXT NOT NULL,
    user_id      TEXT NOT NULL,
    session_id   TEXT NOT NULL UNIQUE,
    created_at   TEXT NOT NULL,
    UNIQUE(channel_type, channel_id, user_id)
);

-- ── Indexes ──────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_cron_nextfire ON agent_cron(nextFireTime) WHERE status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_cron_status    ON agent_cron(status);
CREATE INDEX IF NOT EXISTS idx_cron_userid    ON agent_cron(userId);

CREATE INDEX IF NOT EXISTS idx_audit_session   ON audit_event (sessionId);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_event (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_userId    ON audit_event (userId);

-- ── WORM triggers (audit_event immutability) ────────────────────────────

CREATE TRIGGER IF NOT EXISTS prevent_audit_update
BEFORE UPDATE ON audit_event
BEGIN
    SELECT RAISE(ABORT, 'audit_event rows are immutable');
END;

CREATE TRIGGER IF NOT EXISTS prevent_audit_delete
BEFORE DELETE ON audit_event
BEGIN
    SELECT RAISE(ABORT, 'audit_event rows are immutable');
END;
