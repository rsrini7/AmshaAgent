-- Execution state and coordination

-- Advisory locks for distributed coordination (§T5-3)
CREATE TABLE IF NOT EXISTS advisory_lock (
    lock_key   TEXT NOT NULL PRIMARY KEY,
    owner_id   TEXT,
    expires_at TEXT
);

-- Agent checkpoints (session state, HITL flow)
CREATE TABLE IF NOT EXISTS agent_checkpoint (
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
    retry_after_ms           BIGINT,
    system_note              TEXT,
    context_data             TEXT,
    error_code               TEXT,
    trace_id                 TEXT,
    root_cause_class         TEXT,
    parent_checkpoint_id     BIGINT,
    depth                    INTEGER NOT NULL DEFAULT 0,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL,
    UNIQUE(session_id, agent_role)
);

-- Reasoning steps per checkpoint
CREATE TABLE IF NOT EXISTS reasoning_step (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
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
    root_cause_class TEXT,
    CONSTRAINT uq_reasoning_step_checkpoint_id UNIQUE (checkpoint_id, id)
);

-- Schema contract marker for reset-only baseline validation (§12.1)
CREATE TABLE IF NOT EXISTS schema_contract_marker (
    contract_name    TEXT NOT NULL PRIMARY KEY,
    contract_version INTEGER NOT NULL,
    applied_at       TEXT NOT NULL
);

INSERT OR REPLACE INTO schema_contract_marker (contract_name, contract_version, applied_at)
VALUES ('tool-attempt-settlement', 1, CURRENT_TIMESTAMP);

-- Tool execution attempts (§12.4)
CREATE TABLE IF NOT EXISTS tool_attempt (
    workspace_id                TEXT NOT NULL,
    attempt_id                  TEXT NOT NULL,
    session_id                  TEXT NOT NULL,
    operation_id                TEXT NOT NULL,
    attempt_number              INTEGER NOT NULL,
    tool_name                   TEXT NOT NULL,
    action_fingerprint          TEXT NOT NULL,
    fingerprint_version         INTEGER NOT NULL DEFAULT 1,
    target_fingerprint          TEXT,
    idempotency_mode_snapshot   TEXT NOT NULL,
    status                      TEXT NOT NULL,
    fencing_token               BIGINT NOT NULL DEFAULT 0,
    execution_kind              TEXT,
    execution_handle            TEXT,
    process_start_identity      TEXT,
    exit_code                   INTEGER,
    sanitized_observation       TEXT,
    created_at                  TEXT NOT NULL,
    launch_requested_at         TEXT,
    running_at                  TEXT,
    settled_at                  TEXT,
    PRIMARY KEY (workspace_id, attempt_id),
    UNIQUE (workspace_id, operation_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_tool_attempt_ws_session_status ON tool_attempt (workspace_id, session_id, status);
CREATE INDEX IF NOT EXISTS idx_tool_attempt_ws_op ON tool_attempt (workspace_id, operation_id);

-- Action claims for AT_MOST_ONCE execution (§12.4)
CREATE TABLE IF NOT EXISTS tool_action_claim (
    workspace_id        TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    tool_name           TEXT NOT NULL,
    fingerprint_version INTEGER NOT NULL DEFAULT 1,
    action_fingerprint  TEXT NOT NULL,
    claim_state         TEXT NOT NULL,
    operation_id        TEXT NOT NULL,
    attempt_id          TEXT NOT NULL,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    PRIMARY KEY (workspace_id, session_id, tool_name, fingerprint_version, action_fingerprint)
);

-- Durable action locks for indeterminate and unapproved outcomes (§12.4)
CREATE TABLE IF NOT EXISTS tool_action_lock (
    lock_id                 TEXT NOT NULL,
    workspace_id            TEXT NOT NULL,
    session_id              TEXT NOT NULL,
    origin_operation_id     TEXT NOT NULL,
    origin_attempt_id       TEXT NOT NULL,
    action_fingerprint      TEXT NOT NULL,
    target_fingerprint      TEXT,
    scope                   TEXT NOT NULL,
    reason                  TEXT NOT NULL,
    status                  TEXT NOT NULL,
    created_at              TEXT NOT NULL,
    cleared_at              TEXT,
    resolution_id           TEXT,
    PRIMARY KEY (workspace_id, lock_id),
    CHECK (scope != 'TARGET_RESOURCE' OR target_fingerprint IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_tool_action_lock_lookup ON tool_action_lock (workspace_id, session_id, action_fingerprint);
CREATE INDEX IF NOT EXISTS idx_tool_action_lock_target ON tool_action_lock (workspace_id, target_fingerprint);

-- Transactional outbox for mandatory HMAC audit events (§12.4, §12.5)
CREATE TABLE IF NOT EXISTS tool_attempt_outbox (
    event_id                 TEXT NOT NULL PRIMARY KEY,
    idempotency_key          TEXT NOT NULL UNIQUE,
    workspace_id             TEXT NOT NULL,
    session_id               TEXT NOT NULL,
    attempt_id               TEXT,
    resolution_id            TEXT,
    event_kind               TEXT NOT NULL,
    safe_metadata_json       TEXT NOT NULL,
    created_at               TEXT NOT NULL,
    delivery_attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at          TEXT NOT NULL,
    lease_id                 TEXT,
    lease_until              TEXT,
    delivered_at             TEXT,
    delivered_audit_event_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_tool_outbox_pending ON tool_attempt_outbox (delivered_at, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_tool_outbox_lease ON tool_attempt_outbox (lease_id, lease_until);

-- Lock resolutions and one-shot retry authorizations (§12.4, §12.7)
CREATE TABLE IF NOT EXISTS tool_attempt_resolution (
    resolution_id                 TEXT NOT NULL,
    lock_id                       TEXT NOT NULL,
    workspace_id                  TEXT NOT NULL,
    actor_id                      TEXT NOT NULL,
    resolution_kind               TEXT NOT NULL,
    resolution_status             TEXT NOT NULL,
    origin_attempt_id             TEXT NOT NULL,
    action_fingerprint            TEXT NOT NULL,
    target_fingerprint            TEXT,
    evidence_reference_or_digest  TEXT NOT NULL,
    authorization_outbox_event_id TEXT NOT NULL,
    resolved_outbox_event_id      TEXT,
    created_at                    TEXT NOT NULL,
    consumed_at                   TEXT,
    PRIMARY KEY (workspace_id, resolution_id)
);

CREATE INDEX IF NOT EXISTS idx_tool_resolution_lock ON tool_attempt_resolution (workspace_id, lock_id);

-- Join table connecting checkpoints and reasoning steps to attempts (§12.4, §12.6)
CREATE TABLE IF NOT EXISTS reasoning_step_attempt (
    checkpoint_id     BIGINT NOT NULL,
    attempt_id        TEXT NOT NULL,
    session_id        TEXT NOT NULL,
    workspace_id      TEXT NOT NULL,
    reasoning_step_id BIGINT NOT NULL,
    PRIMARY KEY (checkpoint_id, attempt_id),
    CONSTRAINT fk_reasoning_step_attempt_step
        FOREIGN KEY (checkpoint_id, reasoning_step_id)
        REFERENCES reasoning_step (checkpoint_id, id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reasoning_step_attempt_step ON reasoning_step_attempt (checkpoint_id, reasoning_step_id);

-- SQLite foreign keys are not enabled globally; enforce the composite relationship and cascade via triggers (§12.4)
CREATE TRIGGER IF NOT EXISTS trg_reasoning_step_attempt_fk_ins
BEFORE INSERT ON reasoning_step_attempt
FOR EACH ROW
WHEN (SELECT 1 FROM reasoning_step WHERE checkpoint_id = NEW.checkpoint_id AND id = NEW.reasoning_step_id) IS NULL
BEGIN
    SELECT RAISE(ABORT, 'FOREIGN KEY constraint failed: reasoning_step_attempt requires matching reasoning_step(checkpoint_id, id)');
END;

CREATE TRIGGER IF NOT EXISTS trg_reasoning_step_attempt_fk_upd
BEFORE UPDATE OF checkpoint_id, reasoning_step_id ON reasoning_step_attempt
FOR EACH ROW
WHEN (SELECT 1 FROM reasoning_step WHERE checkpoint_id = NEW.checkpoint_id AND id = NEW.reasoning_step_id) IS NULL
BEGIN
    SELECT RAISE(ABORT, 'FOREIGN KEY constraint failed: reasoning_step_attempt requires matching reasoning_step(checkpoint_id, id)');
END;

CREATE TRIGGER IF NOT EXISTS trg_reasoning_step_attempt_cascade_del
AFTER DELETE ON reasoning_step
FOR EACH ROW
BEGIN
    DELETE FROM reasoning_step_attempt
    WHERE checkpoint_id = OLD.checkpoint_id AND reasoning_step_id = OLD.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_reasoning_step_attempt_prevent_step_key_upd
BEFORE UPDATE OF checkpoint_id, id ON reasoning_step
FOR EACH ROW
WHEN (OLD.checkpoint_id != NEW.checkpoint_id OR OLD.id != NEW.id)
     AND (SELECT 1 FROM reasoning_step_attempt WHERE checkpoint_id = OLD.checkpoint_id AND reasoning_step_id = OLD.id) IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'FOREIGN KEY constraint failed: cannot update reasoning_step key columns referenced by reasoning_step_attempt');
END;
