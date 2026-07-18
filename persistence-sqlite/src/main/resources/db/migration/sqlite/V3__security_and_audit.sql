-- Secrets and immutable audit history

-- Immutable audit event log (WORM-protected by triggers below)
CREATE TABLE IF NOT EXISTS audit_event (
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

-- Encrypted secrets (AES-256-GCM-SIV ciphertext per key per session)
CREATE TABLE IF NOT EXISTS encrypted_secrets (
    session_id            TEXT NOT NULL,
    key_name              TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    ciphertext            BLOB NOT NULL,
    nonce                 BLOB NOT NULL,
    PRIMARY KEY (session_id, key_name)
);

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
