-- Execution state and coordination

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
