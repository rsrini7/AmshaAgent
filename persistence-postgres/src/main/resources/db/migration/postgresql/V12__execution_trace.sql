CREATE TABLE execution_trace (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    turn_number INTEGER NOT NULL,
    sequence BIGINT NOT NULL,
    stage TEXT NOT NULL,
    station TEXT,
    status TEXT,
    risk_level TEXT,
    detail TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    duration_ms BIGINT,
    iteration INTEGER,
    attempt INTEGER,
    timestamp TIMESTAMPTZ NOT NULL,
    paradigm TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exec_trace_run_seq ON execution_trace(run_id, sequence ASC);
CREATE INDEX idx_exec_trace_session ON execution_trace(session_id, created_at DESC);
CREATE INDEX idx_exec_trace_created_at ON execution_trace(created_at);
