-- Secrets and immutable audit history

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

-- Encrypted secrets (AES-256-GCM-SIV ciphertext per key per session)
CREATE TABLE IF NOT EXISTS encrypted_secrets (
    session_id             TEXT NOT NULL,
    key_name               TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    ciphertext             BYTEA NOT NULL,
    nonce                  BYTEA NOT NULL,
    PRIMARY KEY (session_id, key_name)
);

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
