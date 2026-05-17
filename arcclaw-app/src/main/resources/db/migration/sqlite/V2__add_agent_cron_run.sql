-- V2: Add agent_cron_run table for cron job execution history
-- One row per actual cron job execution.

CREATE TABLE agent_cron_run (
    id              TEXT PRIMARY KEY,
    cronId          TEXT NOT NULL,
    sessionId       TEXT NOT NULL,
    status          TEXT NOT NULL,
    startedAt       TEXT NOT NULL,
    finishedAt      TEXT,
    errorReason     TEXT,
    iterationsUsed  INTEGER NOT NULL DEFAULT 0,
    inputTokens     INTEGER NOT NULL DEFAULT 0,
    outputTokens    INTEGER NOT NULL DEFAULT 0,
    usdCost         TEXT NOT NULL DEFAULT '0.0',
    createdAt       TEXT NOT NULL,
    FOREIGN KEY (cronId) REFERENCES agent_cron(id) ON DELETE CASCADE
);

CREATE INDEX idx_cron_run_cronid ON agent_cron_run(cronId);
CREATE INDEX idx_cron_run_status ON agent_cron_run(status);
CREATE INDEX idx_cron_run_started ON agent_cron_run(startedAt);
