-- V2: Add agent_cron_run table for cron job execution history
-- One row per actual cron job execution.

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

CREATE INDEX IF NOT EXISTS idx_cron_run_cronid ON agent_cron_run(cronId);
CREATE INDEX IF NOT EXISTS idx_cron_run_status ON agent_cron_run(status);
CREATE INDEX IF NOT EXISTS idx_cron_run_started ON agent_cron_run(startedAt);
