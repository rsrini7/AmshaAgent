-- Scheduling, run history, and skills

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
    nextFireTime        TEXT NOT NULL,
    lastFireTime        TEXT,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    executionRole       TEXT,
    errorReason         TEXT,
    lastDeliveryId      TEXT,
    createdAt           TEXT NOT NULL,
    updatedAt           TEXT
);

-- Agent cron job runs (execution history)
CREATE TABLE IF NOT EXISTS agent_cron_run (
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
    usdCost         NUMERIC(10,6) NOT NULL DEFAULT 0.0,
    createdAt       TEXT NOT NULL,
    FOREIGN KEY (cronId) REFERENCES agent_cron(id) ON DELETE CASCADE
);

-- Agent skills (YAML-defined)
CREATE TABLE IF NOT EXISTS agent_skill (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    definitionYaml  TEXT NOT NULL,
    createdAt       TEXT,
    updatedAt       TEXT
);

-- ── Indexes ──────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_cron_nextfire ON agent_cron(nextFireTime) WHERE status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_cron_status    ON agent_cron(status);
CREATE INDEX IF NOT EXISTS idx_cron_userid    ON agent_cron(userId);

CREATE INDEX IF NOT EXISTS idx_cron_run_cronid ON agent_cron_run(cronId);
CREATE INDEX IF NOT EXISTS idx_cron_run_status ON agent_cron_run(status);
CREATE INDEX IF NOT EXISTS idx_cron_run_started ON agent_cron_run(startedAt);
